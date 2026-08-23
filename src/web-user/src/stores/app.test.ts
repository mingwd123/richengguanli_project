import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAppStore } from './app'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function response(data: unknown, code = 0, message = '') {
  return { json: async () => ({ code, message, data }) } as Response
}

function storageStub() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, String(value)),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear()
  }
}

describe('app store request coordination', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('localStorage', storageStub())
    vi.stubGlobal('document', { documentElement: { dataset: {} } })
    vi.stubGlobal('window', { fetch: vi.fn(), focus: vi.fn(), location: { href: '' } })
    setActivePinia(createPinia())
  })

  it('keeps the latest list response when an older filter request finishes later', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
    const oldRequest = deferred<Response>()
    const latestRequest = deferred<Response>()
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation((input) => {
      const url = String(input)
      return url.includes('keyword=old') ? oldRequest.promise : latestRequest.promise
    })
    const store = useAppStore()

    const oldLoad = store.loadSchedules({ keyword: 'old' })
    const latestLoad = store.loadSchedules({ keyword: 'latest' })
    latestRequest.resolve(response({ list: [{ id: 2, title: 'Latest' }], total: 1, page: 1, size: 12 }))
    await latestLoad
    oldRequest.resolve(response({ list: [{ id: 1, title: 'Old' }], total: 1, page: 1, size: 12 }))
    await oldLoad

    expect(store.schedules.map(item => item.id)).toEqual([2])
    expect(store.schedulePage.loading).toBe(false)
  })

  it('does not restore tokens when logout happens during a refresh request', async () => {
    localStorage.setItem('dayliane_token', 'expired-access')
    localStorage.setItem('dayliane_refresh_token', 'old-refresh')
    const refreshResponse = deferred<Response>()
    const refreshStarted = deferred<void>()
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/protected')) return Promise.resolve(response(null, 401, 'expired'))
      if (url.endsWith('/auth/refresh-token')) {
        refreshStarted.resolve()
        return refreshResponse.promise
      }
      if (url.endsWith('/auth/logout')) return Promise.resolve(response(null))
      throw new Error(`Unexpected request: ${url}`)
    })
    const store = useAppStore()

    const protectedRequest = store.request('/protected').catch(error => error)
    await refreshStarted.promise
    await store.logout()
    refreshResponse.resolve(response({ accessToken: 'new-access', refreshToken: 'new-refresh' }))
    await protectedRequest

    expect(store.token).toBe('')
    expect(store.refreshToken).toBe('')
    expect(localStorage.getItem('dayliane_token')).toBeNull()
    expect(localStorage.getItem('dayliane_refresh_token')).toBeNull()
  })

  it('does not repopulate state when loadAll finishes after logout', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
    const profileResponse = deferred<Response>()
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/user/profile')) return profileResponse.promise
      if (url.includes('/task-groups?')) return Promise.resolve(response({ list: [], total: 0, page: 1, size: 100 }))
      if (url.endsWith('/home/today')) return Promise.resolve(response({ personalSchedules: [], teamTasks: [], unreadNotificationCount: 3, groups: [] }))
      if (url.endsWith('/home/upcoming')) return Promise.resolve(response({ timezone: 'Asia/Shanghai', dateFrom: '', dateTo: '', personalSchedules: [], teamTasks: [], list: [] }))
      if (url.endsWith('/notifications/preferences')) return Promise.resolve(response({ browserEnabled: false, taskAssignedEnabled: true, taskStatusEnabled: true, reminderEnabled: true, reminderPresetMinutes: [15] }))
      if (url.endsWith('/auth/logout')) return Promise.resolve(response(null))
      throw new Error(`Unexpected request: ${url}`)
    })
    const store = useAppStore()

    const loading = store.loadAll()
    await store.logout()
    profileResponse.resolve(response({ id: 1, phone: '13800138000', nickname: 'Late user', avatarUrl: '', timezone: 'Asia/Shanghai', createdAt: '' }))
    await loading

    expect(store.profile).toBeNull()
    expect(store.today.unreadNotificationCount).toBe(0)
    expect(store.schedules).toEqual([])
  })

  it('keeps the newest notification poll when polls finish out of order', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
    const firstPollResponse = deferred<Response>()
    const secondPollResponse = deferred<Response>()
    let pollCalls = 0
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation((input) => {
      const url = String(input)
      if (url.includes('/notifications?page=1&size=20')) {
        pollCalls += 1
        return pollCalls === 1 ? firstPollResponse.promise : secondPollResponse.promise
      }
      if (url.includes('/notifications?page=1&size=12')) {
        return Promise.resolve(response({ list: [{ id: 22, title: 'Newest', isRead: false }], total: 1, page: 1, size: 12 }))
      }
      if (url.endsWith('/notifications/unread-count')) return Promise.resolve(response({ count: 7 }))
      throw new Error(`Unexpected request: ${url}`)
    })
    const store = useAppStore()

    const firstPoll = store.pollNotifications()
    const secondPoll = store.pollNotifications()
    secondPollResponse.resolve(response({ list: [{ id: 2, title: 'Second poll', isRead: false }], total: 1, page: 1, size: 20 }))
    await secondPoll
    firstPollResponse.resolve(response({ list: [{ id: 1, title: 'First poll', isRead: false }], total: 1, page: 1, size: 20 }))
    await firstPoll

    expect(store.notifications.map(item => item.id)).toEqual([22])
    expect(store.today.unreadNotificationCount).toBe(7)
  })

  it('counts only actionable team-task assignments', () => {
    const store = useAppStore()
    store.myTasks = [
      { id: 1, status: 'active', assignStatus: 'pending' },
      { id: 2, status: 'unassigned', assignStatus: 'accepted' },
      { id: 3, status: 'active', assignStatus: 'rejected' },
      { id: 4, status: 'active', assignStatus: 'completed' },
      { id: 5, status: 'completed', assignStatus: 'pending' },
    ] as any

    expect(store.activeTaskCount).toBe(2)
  })

  it('sends a normalized authenticated email-code request', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ countdown: 45 }))
    const store = useAppStore()

    const result = await store.sendEmailCode({
      email: '  New.Email@Example.COM ',
      purpose: 'change_email',
      currentPassword: 'Abc12345',
    })

    expect(result.countdown).toBe(45)
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/email-code')
    expect((options?.headers as Record<string, string>).Authorization).toBe('Bearer access-token')
    expect(JSON.parse(String(options?.body))).toEqual({
      email: 'new.email@example.com',
      purpose: 'change_email',
      currentPassword: 'Abc12345',
    })
  })

  it('resets a password without persisting password fields', async () => {
    const localSetItem = vi.fn()
    const sessionSetItem = vi.fn()
    vi.stubGlobal('localStorage', { ...storageStub(), setItem: localSetItem })
    vi.stubGlobal('sessionStorage', { ...storageStub(), setItem: sessionSetItem })
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ ok: true }))
    const store = useAppStore()

    const success = await store.resetPassword({
      email: ' User@Example.com ',
      code: '123456',
      newPassword: 'NewPass123',
      confirmPassword: 'NewPass123',
    })

    expect(success).toBe(true)
    const [, options] = fetchMock.mock.calls[0]
    expect(JSON.parse(String(options?.body))).toEqual({
      email: 'user@example.com',
      code: '123456',
      newPassword: 'NewPass123',
    })
    expect(localSetItem).not.toHaveBeenCalled()
    expect(sessionSetItem).not.toHaveBeenCalled()
  })

  it('clears the local session when an email change requires authentication again', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ ok: true, reauthenticate: true }))
    const store = useAppStore()
    store.profile = {
      id: 1,
      phone: null,
      email: 'old@example.com',
      emailVerifiedAt: '2026-08-23T12:00:00',
      nickname: 'Email user',
      avatarUrl: '',
      timezone: 'Asia/Shanghai',
      createdAt: '2026-08-23T12:00:00',
    }

    const result = await store.updateEmail({
      email: 'new@example.com',
      code: '123456',
      currentPassword: 'Abc12345',
    })

    expect(result).toEqual({ success: true, reauthenticate: true })
    expect(store.token).toBe('')
    expect(store.refreshToken).toBe('')
    expect(localStorage.getItem('dayliane_token')).toBeNull()
    expect(localStorage.getItem('dayliane_refresh_token')).toBeNull()
  })

  it('clears the local session after changing the password', async () => {
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ ok: true }))
    const store = useAppStore()
    store.passwordForm.oldPassword = 'Abc12345'
    store.passwordForm.newPassword = 'Changed12345'
    store.passwordForm.confirmPassword = 'Changed12345'

    expect(await store.changePassword()).toBe(true)

    expect(store.token).toBe('')
    expect(store.refreshToken).toBe('')
    expect(localStorage.getItem('dayliane_token')).toBeNull()
    expect(localStorage.getItem('dayliane_refresh_token')).toBeNull()
  })
})
