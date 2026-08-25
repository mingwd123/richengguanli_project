import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRegistrationStatus, loginAccount, registerAccount } from './auth'

function response(data: unknown) {
  return { json: async () => ({ code: 0, message: '', data }) } as Response
}

describe('auth api', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('window', { fetch: vi.fn() })
  })

  it('uses the unified account field for login', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ accessToken: 'access', refreshToken: 'refresh' }))

    await loginAccount({ account: 'user@example.com', password: 'Abc12345' })

    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/login')
    expect(JSON.parse(String(options?.body))).toEqual({
      account: 'user@example.com',
      password: 'Abc12345',
    })
  })

  it('loads the public registration status without credentials', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ registrationEnabled: false }))

    await expect(fetchRegistrationStatus()).resolves.toEqual({ registrationEnabled: false })

    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/registration-status')
    expect((options?.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('omits an optional phone from an email registration request', async () => {
    const fetchMock = vi.mocked(window.fetch)
    fetchMock.mockResolvedValue(response({ accessToken: 'access', refreshToken: 'refresh' }))

    await registerAccount({
      email: 'user@example.com',
      code: '123456',
      password: 'Abc12345',
      nickname: 'Email user',
      timezone: 'Asia/Shanghai',
    })

    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/register')
    expect(JSON.parse(String(options?.body))).toEqual({
      email: 'user@example.com',
      code: '123456',
      password: 'Abc12345',
      nickname: 'Email user',
      timezone: 'Asia/Shanghai',
    })
  })
})
