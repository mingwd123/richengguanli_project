import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAppStore } from './app'
import { REMEMBERED_LOGIN_KEY, readRememberedLogin, saveRememberedLogin } from '../utils/rememberedLogin'

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

  it('keeps registration disabled until the public status endpoint enables it', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ registrationEnabled: true }))
    const store = useAppStore()

    expect(store.registrationEnabled).toBe(false)
    await expect(store.loadRegistrationStatus()).resolves.toBe(true)

    expect(store.registrationEnabled).toBe(true)
    expect(store.registrationStatusChecked).toBe(true)
    expect(store.registrationStatusError).toBe(false)
    expect(store.registrationStatusLoading).toBe(false)
    expect(String(fetchMock.mock.calls[0][0])).toContain('/auth/registration-status')
  })

  it('closes a previously enabled registration entry while the latest status is loading', async () => {
    const pending = deferred<Response>()
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockReturnValue(pending.promise)
    const store = useAppStore()
    store.registrationEnabled = true

    const loading = store.loadRegistrationStatus()

    expect(store.registrationEnabled).toBe(false)
    expect(store.registrationStatusLoading).toBe(true)

    pending.resolve(response({ registrationEnabled: true }))
    await expect(loading).resolves.toBe(true)
    expect(store.registrationEnabled).toBe(true)
  })

  it('fails closed when registration status cannot be loaded without affecting password recovery', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(response({ countdown: 60 }))
    const store = useAppStore()

    await expect(store.loadRegistrationStatus()).resolves.toBe(false)

    expect(store.registrationEnabled).toBe(false)
    expect(store.registrationStatusChecked).toBe(true)
    expect(store.registrationStatusError).toBe(true)
    await expect(store.sendEmailCode({
      email: 'reset@example.com',
      purpose: 'reset_password',
    })).resolves.toEqual({ countdown: 60 })
    expect(String(fetchMock.mock.calls[1][0])).toContain('/auth/email-code')
  })

  it('turns a registration-code 503 into a clear message and closes registration locally', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response(null, 503, 'registration unavailable'))
    const store = useAppStore()
    store.registrationEnabled = true

    await expect(store.sendEmailCode({
      email: 'new@example.com',
      purpose: 'register',
    })).rejects.toMatchObject({
      code: 503,
      message: '当前暂不开放新用户注册',
    })

    expect(store.registrationEnabled).toBe(false)
    expect(store.registrationStatusChecked).toBe(true)
    expect(store.registrationStatusError).toBe(false)
  })

  it('turns a final registration 503 into a clear toast and closes registration locally', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response(null, 503, 'registration unavailable'))
    const store = useAppStore()
    store.registrationEnabled = true
    store.registerForm.email = 'new@example.com'
    store.registerForm.code = '123456'
    store.registerForm.password = 'Abc12345'
    store.registerForm.confirmPassword = 'Abc12345'

    await expect(store.register()).resolves.toBe(false)

    expect(store.registrationEnabled).toBe(false)
    expect(store.toast).toBe('当前暂不开放新用户注册')
    expect(store.registerForm.code).toBe('')
    expect(store.registerForm.password).toBe('')
    expect(store.registerForm.confirmPassword).toBe('')
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

  it('clears authentication secrets after failed login and registration requests', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response(null, 401, '账号或密码错误'))
    const store = useAppStore()

    store.loginForm.account = 'user@example.com'
    store.loginForm.password = 'Wrong12345'
    expect(await store.login()).toBe(false)
    expect(store.loginForm.password).toBe('')

    store.registrationEnabled = true
    store.registerForm.email = 'new@example.com'
    store.registerForm.code = '123456'
    store.registerForm.password = 'Abc12345'
    store.registerForm.confirmPassword = 'Abc12345'
    fetchMock.mockResolvedValue(response(null, 409, '邮箱已注册'))
    expect(await store.register()).toBe(false)
    expect(store.registerForm.code).toBe('')
    expect(store.registerForm.password).toBe('')
    expect(store.registerForm.confirmPassword).toBe('')
  })

  it('leaves the login form empty until the user asks to be remembered', () => {
    const store = useAppStore()

    expect(store.rememberPassword).toBe(false)
    expect(store.loginForm.account).toBe('')
    expect(store.loginForm.password).toBe('')
    expect(localStorage.getItem(REMEMBERED_LOGIN_KEY)).toBeNull()
  })

  it('prefills account and password only from a remembered login', () => {
    saveRememberedLogin('13800138000', 'Abc12345')
    setActivePinia(createPinia())

    const store = useAppStore()

    expect(store.rememberPassword).toBe(true)
    expect(store.loginForm.account).toBe('13800138000')
    expect(store.loginForm.password).toBe('Abc12345')
  })

  it('persists the credentials after a successful login when remembering', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ accessToken: 'a', refreshToken: 'r' }))
    const store = useAppStore()

    store.loginForm.account = '13800138000'
    store.loginForm.password = 'Abc12345'
    store.rememberPassword = true

    expect(await store.login()).toBe(true)
    expect(readRememberedLogin()).toEqual({ account: '13800138000', password: 'Abc12345' })
    expect(store.loginForm.password).toBe('Abc12345')
  })

  it('drops remembered credentials when the box is unchecked or login is not remembered', async () => {
    saveRememberedLogin('13800138000', 'Abc12345')
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ accessToken: 'a', refreshToken: 'r' }))
    const store = useAppStore()

    // 取消勾选立即清除，不必等到下次登录。
    store.rememberPassword = false
    store.syncRememberedLogin()
    expect(readRememberedLogin()).toBeNull()

    // 未勾选时即便登录成功也不写入。
    store.loginForm.account = '13800138000'
    store.loginForm.password = 'Abc12345'
    expect(await store.login()).toBe(true)
    expect(readRememberedLogin()).toBeNull()
    expect(store.loginForm.password).toBe('')
  })
})

describe('daily schedule progress', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('localStorage', storageStub())
    vi.stubGlobal('document', { documentElement: { dataset: {} } })
    vi.stubGlobal('window', { fetch: vi.fn(), focus: vi.fn(), location: { href: '' } })
    setActivePinia(createPinia())
    localStorage.setItem('dayliane_token', 'access-token')
    localStorage.setItem('dayliane_refresh_token', 'refresh-token')
  })

  it('submits cumulative progress with the selected fatigue level', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation(async (_input, init) => {
      if (init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({ cumulativeProgress: 70, fatigueLevel: 4 })
        return response({ scheduleId: 9, progressPercent: 70, items: [] })
      }
      return response({ list: [], total: 0, page: 1, size: 12, statusCounts: {}, sectionSummaries: [] })
    })
    const store = useAppStore()

    expect(await store.submitScheduleProgress(9, 70, 4)).toBe(true)
    expect(store.scheduleProgress?.progressPercent).toBe(70)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/schedules/9/progress'))).toBe(true)
  })

  it('omits the fatigue level when only recording progress', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockImplementation(async (_input, init) => {
      if (init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({ cumulativeProgress: 20 })
        return response({ scheduleId: 9, progressPercent: 20, items: [] })
      }
      return response({ list: [], total: 0, page: 1, size: 12, statusCounts: {}, sectionSummaries: [] })
    })
    const store = useAppStore()

    expect(await store.submitScheduleProgress(9, 20, null)).toBe(true)
  })

  it('keeps the last loaded progress payload', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({
      scheduleId: 3,
      progressTrackingEnabled: true,
      progressPercent: 40,
      status: 'pending',
      completedAt: '',
      fatigueTrackingEnabled: true,
      totalCompletedLoad: 2,
      items: [{ progressDate: '2026-09-13', progressDelta: 40, cumulativeProgress: 40, fatigueLevel: 4, fatigueWeightSnapshot: 5, completedLoad: 2 }]
    }))
    const store = useAppStore()

    const progress = await store.loadScheduleProgress(3)
    expect(progress?.progressPercent).toBe(40)
    expect(store.scheduleProgress?.items).toHaveLength(1)
    expect(store.scheduleProgressLoading).toBe(false)
  })

  it('reports a failure when the correction endpoint rejects the change', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response(null, 400, 'cumulative daily progress cannot exceed 100'))
    const store = useAppStore()

    expect(await store.correctScheduleProgress(3, '2026-09-12', 90, 3)).toBe(false)
    expect(store.toast).toContain('cannot exceed 100')
  })

  it('clears stale progress before loading another schedule', async () => {
    const fetchMock = vi.mocked(window.fetch)
    const first = deferred<Response>()
    const second = deferred<Response>()
    fetchMock.mockImplementation((input) => String(input).includes('/schedules/1/progress') ? first.promise : second.promise)
    const store = useAppStore()

    const stale = store.loadScheduleProgress(1)
    const current = store.loadScheduleProgress(2)
    // 先发出的旧请求晚于当前请求返回时，不能覆盖当前任务的进度数据。
    second.resolve(response({ scheduleId: 2, progressPercent: 80, items: [{ progressDate: '2026-09-20' }] }))
    await current
    expect(store.scheduleProgress?.scheduleId).toBe(2)

    first.resolve(response({ scheduleId: 1, progressPercent: 10, items: [] }))
    await stale
    expect(store.scheduleProgress?.scheduleId).toBe(2)
    expect(store.scheduleProgress?.progressPercent).toBe(80)
  })

  it('keeps no progress data when loading fails', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ scheduleId: 9, progressPercent: 30, items: [{ progressDate: '2026-09-19' }] }))
    const store = useAppStore()
    await store.loadScheduleProgress(9)
    expect(store.scheduleProgress?.progressPercent).toBe(30)

    fetchMock.mockResolvedValue(response(null, 500, '服务器开小差了'))
    expect(await store.loadScheduleProgress(10)).toBeNull()
    expect(store.scheduleProgress).toBeNull()
    expect(store.scheduleProgressError).toContain('服务器开小差了')

    store.resetScheduleProgress()
    expect(store.scheduleProgress).toBeNull()
    expect(store.scheduleProgressError).toBe('')
  })
})
