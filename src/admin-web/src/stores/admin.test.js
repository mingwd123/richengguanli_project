import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminStore } from './admin'

function response(body) {
  return { json: async () => body }
}

describe('admin user editing store', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    })
    vi.stubGlobal('document', { documentElement: { dataset: {} } })
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('updates only editable profile fields and refreshes the list and detail', async () => {
    let finishUpdate
    const updateResponse = new Promise(resolve => { finishUpdate = resolve })
    const fetchMock = vi.fn((url, options = {}) => {
      if (options.method === 'PUT') return updateResponse
      if (url.includes('?')) {
        return Promise.resolve(response({
          code: 0,
          data: { list: [{ id: 7, email: 'new@example.com', profileVersion: 4 }], total: 1, page: 1, size: 20 },
        }))
      }
      return Promise.resolve(response({
        code: 0,
        data: { id: 7, email: 'new@example.com', phone: '', nickname: '新昵称', timezone: 'Asia/Shanghai', profileVersion: 4 },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const store = useAdminStore()
    store.token = 'admin-token'
    store.profile = { id: 1, role: 'super_admin' }
    store.beginUserEdit({
      id: 7,
      email: ' Old@Example.com ',
      phone: '',
      nickname: '新昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 3,
      status: 'disabled',
      password: 'secret',
    })
    store.userEditForm.email = ' New@Example.com '

    const confirmLoginIdentifierChange = vi.fn().mockResolvedValue(true)
    const firstSave = store.updateUser({ confirmLoginIdentifierChange })
    expect(store.userEditLoading).toBe(true)
    await expect(store.updateUser()).resolves.toBe(false)
    await Promise.resolve()
    expect(confirmLoginIdentifierChange).toHaveBeenCalledTimes(1)
    expect(finishUpdate).toBeTypeOf('function')
    finishUpdate(response({ code: 0, data: { id: 7 } }))

    await expect(firstSave).resolves.toBe(true)
    const updateCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
    expect(updateCall[0]).toBe('http://127.0.0.1:8080/api/v1/admin/users/7')
    expect(JSON.parse(updateCall[1].body)).toEqual({
      email: 'new@example.com',
      phone: '',
      nickname: '新昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 3,
    })
    expect(store.page.list).toEqual([{ id: 7, email: 'new@example.com', profileVersion: 4 }])
    expect(store.currentDetail).toMatchObject({ id: 7, email: 'new@example.com' })
    expect(store.userEditLoading).toBe(false)
    expect(store.toast).toBe('用户资料已更新')
  })

  it('retains the form and shows a Chinese error when saving fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({
      code: 409,
      message: 'email already registered',
    })))

    const store = useAdminStore()
    store.token = 'admin-token'
    store.beginUserEdit({
      id: 9,
      email: 'duplicate@example.com',
      phone: '13800138000',
      nickname: '保留输入',
      timezone: 'Asia/Shanghai',
      profileVersion: 5,
    })

    await expect(store.updateUser()).resolves.toBe(false)
    expect({ ...store.userEditForm }).toEqual({
      id: 9,
      email: 'duplicate@example.com',
      phone: '13800138000',
      nickname: '保留输入',
      timezone: 'Asia/Shanghai',
    })
    expect(store.userEditOriginal.profileVersion).toBe(5)
    expect(store.userEditLoading).toBe(false)
    expect(store.toast).toBe('该邮箱已注册')
    expect(store.toastType).toBe('error')
  })

  it('preserves login identifiers when a normal administrator edits a user', async () => {
    const fetchMock = vi.fn((url, options = {}) => {
      if (options.method === 'PUT') return Promise.resolve(response({ code: 0, data: { id: 12 } }))
      if (url.includes('?')) {
        return Promise.resolve(response({ code: 0, data: { list: [], total: 0, page: 1, size: 20 } }))
      }
      return Promise.resolve(response({
        code: 0,
        data: { id: 12, email: 'original@example.com', phone: '13800138000', nickname: '新昵称', profileVersion: 9 },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const store = useAdminStore()
    store.token = 'admin-token'
    store.profile = { id: 2, role: 'admin' }
    store.beginUserEdit({
      id: 12,
      email: 'original@example.com',
      phone: '13800138000',
      nickname: '旧昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 8,
    })
    store.userEditForm.email = 'changed@example.com'
    store.userEditForm.phone = '13900139000'
    store.userEditForm.nickname = '新昵称'
    const confirmLoginIdentifierChange = vi.fn().mockResolvedValue(true)

    await expect(store.updateUser({ confirmLoginIdentifierChange })).resolves.toBe(true)
    const updateCall = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT')
    expect(JSON.parse(updateCall[1].body)).toEqual({
      email: 'original@example.com',
      phone: '13800138000',
      nickname: '新昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 8,
    })
    expect(confirmLoginIdentifierChange).not.toHaveBeenCalled()
  })

  it('does not send a request when a super administrator cancels identifier confirmation', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const store = useAdminStore()
    store.token = 'admin-token'
    store.profile = { id: 1, role: 'super_admin' }
    store.beginUserEdit({
      id: 15,
      email: 'old@example.com',
      phone: '',
      nickname: 'User',
      timezone: 'Asia/Shanghai',
      profileVersion: 2,
    })
    store.userEditForm.email = 'new@example.com'
    const confirmLoginIdentifierChange = vi.fn().mockResolvedValue(false)

    await expect(store.updateUser({ confirmLoginIdentifierChange })).resolves.toBe(false)
    expect(confirmLoginIdentifierChange).toHaveBeenCalledTimes(1)
    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.userEditForm.email).toBe('new@example.com')
    expect(store.userEditLoading).toBe(false)
  })

  it('keeps the edit form when the profile version is stale', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({
      code: 409,
      message: 'user information changed',
    })))

    const store = useAdminStore()
    store.token = 'admin-token'
    store.profile = { id: 2, role: 'admin' }
    store.beginUserEdit({
      id: 21,
      email: 'member@example.com',
      phone: '',
      nickname: '未保存昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 6,
    })
    store.userEditForm.nickname = '保留这次修改'

    await expect(store.updateUser()).resolves.toBe(false)
    expect(store.userEditForm.nickname).toBe('保留这次修改')
    expect(store.userEditOriginal.profileVersion).toBe(6)
    expect(store.toast).toBe('用户资料已被其他管理员修改，请刷新后重试')
    expect(store.toastType).toBe('error')
  })
})
