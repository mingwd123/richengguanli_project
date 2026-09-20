import { beforeEach, describe, expect, it, vi } from 'vitest'
import { REMEMBERED_LOGIN_KEY, clearRememberedLogin, readRememberedLogin, saveRememberedLogin } from './rememberedLogin'

function storageStub() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, String(value)),
    removeItem: (key: string) => values.delete(key)
  }
}

describe('remembered login storage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('localStorage', storageStub())
  })

  it('round-trips account and password without storing them as plain text', () => {
    saveRememberedLogin('13800138000', 'Abc12345')

    expect(readRememberedLogin()).toEqual({ account: '13800138000', password: 'Abc12345' })
    const raw = localStorage.getItem(REMEMBERED_LOGIN_KEY) as string
    expect(raw).not.toContain('13800138000')
    expect(raw).not.toContain('Abc12345')
  })

  it('keeps non-ASCII passwords intact', () => {
    saveRememberedLogin('用户@example.com', '密码A1b2')

    expect(readRememberedLogin()).toEqual({ account: '用户@example.com', password: '密码A1b2' })
  })

  it('ignores damaged or incomplete records instead of throwing', () => {
    localStorage.setItem(REMEMBERED_LOGIN_KEY, 'not-base64-json')
    expect(readRememberedLogin()).toBeNull()

    saveRememberedLogin('13800138000', '')
    expect(readRememberedLogin()).toBeNull()
  })

  it('clears the record on request', () => {
    saveRememberedLogin('13800138000', 'Abc12345')
    clearRememberedLogin()

    expect(readRememberedLogin()).toBeNull()
  })

  it('degrades to null when storage is unavailable', () => {
    vi.stubGlobal('localStorage', undefined)

    expect(readRememberedLogin()).toBeNull()
    expect(() => saveRememberedLogin('13800138000', 'Abc12345')).not.toThrow()
    expect(() => clearRememberedLogin()).not.toThrow()
  })
})
