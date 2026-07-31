import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './http'

describe('admin api client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('adds the bearer token and unwraps successful responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ json: async () => ({ code: 0, data: { ok: true } }) })
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest('/profile', {}, 'token-value')).resolves.toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:8080/api/v1/profile', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token-value' })
    }))
  })

  it('surfaces API errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: async () => ({ code: 403, message: 'forbidden' }) }))
    await expect(apiRequest('/admin-users')).rejects.toThrow('forbidden')
  })
})
