import { describe, expect, it } from 'vitest'
import {
  buildAiConfigPayload,
  buildAiKeyPayload,
  formatAiTestResult,
  moveAiKeyIds,
  validateAiKeyForm,
} from './aiConfigHelpers'

describe('buildAiConfigPayload', () => {
  const baseForm = {
    provider: ' deepseek ',
    modelName: ' deepseek-chat ',
    apiBaseUrl: ' https://api.deepseek.com/v1 ',
    remark: ' production ',
  }

  it('keeps provider settings separate from API keys', () => {
    expect(buildAiConfigPayload({ ...baseForm, apiKey: ' sk-not-part-of-config ' })).toEqual({
      provider: 'deepseek',
      modelName: 'deepseek-chat',
      apiBaseUrl: 'https://api.deepseek.com/v1',
      remark: 'production',
    })
  })
})

describe('AI key payload helpers', () => {
  it('omits an empty key while editing so the encrypted key is preserved', () => {
    expect(buildAiKeyPayload({ name: ' Backup ', apiKey: '   ', apiBaseUrl: ' ', enabled: false, remark: ' standby ' })).toEqual({
      name: 'Backup',
      apiBaseUrl: '',
      enabled: false,
      remark: 'standby',
    })
  })

  it('submits a trimmed new key', () => {
    expect(buildAiKeyPayload({ name: ' Primary ', apiKey: ' sk-new-key ', apiBaseUrl: ' https://relay.example.com ', enabled: true, remark: '' })).toEqual({
      name: 'Primary',
      apiKey: 'sk-new-key',
      apiBaseUrl: 'https://relay.example.com',
      enabled: true,
      remark: '',
    })
  })

  it('submits an empty Base URL so editing can restore default inheritance', () => {
    expect(buildAiKeyPayload({ name: 'Relay', apiKey: '', apiBaseUrl: '', enabled: true, remark: '' }))
      .toMatchObject({ apiBaseUrl: '' })
  })

  it('requires a key only when creating', () => {
    const form = { name: 'Primary', apiKey: ' ' }
    expect(validateAiKeyForm(form, false)).toBe('请输入 API Key')
    expect(validateAiKeyForm(form, true)).toBe('')
    expect(validateAiKeyForm({ name: ' ', apiKey: 'sk-key' }, false)).toBe('请输入 Key 名称')
    expect(validateAiKeyForm({ name: 'Primary', apiKey: 'sk-key', apiBaseUrl: 'http://relay.example.com' }, false))
      .toBe('Base URL 必须使用 HTTPS')
  })

  it.each([
    ['https://', 'Base URL 格式不正确'],
    ['https://relay.example.com/open ai', 'Base URL 格式不正确'],
    ['https://user:secret@relay.example.com', 'Base URL 必须是有效的 HTTPS 地址'],
    ['https://relay.example.com:8443', 'Base URL 必须是有效的 HTTPS 地址'],
    ['https://relay.example.com/v1?token=secret', 'Base URL 必须是有效的 HTTPS 地址'],
    ['https://relay.example.com/v1#section', 'Base URL 必须是有效的 HTTPS 地址'],
  ])('rejects unsupported Base URL %s', (apiBaseUrl, message) => {
    expect(validateAiKeyForm({ name: 'Primary', apiKey: 'sk-key', apiBaseUrl }, false)).toBe(message)
  })

  it('rejects a Base URL longer than the database limit', () => {
    const apiBaseUrl = `https://relay.example.com/${'a'.repeat(500)}`
    expect(validateAiKeyForm({ name: 'Primary', apiKey: 'sk-key', apiBaseUrl }, false))
      .toBe('Base URL 最多 500 个字符')
  })
})

describe('AI key ordering', () => {
  const keys = [{ id: 10 }, { id: 20 }, { id: 30 }]

  it('moves a key one position without changing ID types', () => {
    expect(moveAiKeyIds(keys, '20', 'up')).toEqual([20, 10, 30])
    expect(moveAiKeyIds(keys, 20, 'down')).toEqual([10, 30, 20])
  })

  it('returns null at a list boundary', () => {
    expect(moveAiKeyIds(keys, 10, 'up')).toBeNull()
    expect(moveAiKeyIds(keys, 30, 'down')).toBeNull()
  })
})

describe('AI test result formatting', () => {
  it('identifies the successful database key and attempt count', () => {
    expect(formatAiTestResult({ ok: true, source: 'database', keyName: 'Backup', attempts: [{}, {}] }))
      .toBe('Key「Backup」调用成功，尝试 2 次')
  })

  it('identifies the environment fallback and preserves errors', () => {
    expect(formatAiTestResult({ ok: true, source: 'environment', attempts: 3 }))
      .toBe('环境变量兜底 Key 调用成功，尝试 3 次')
    expect(formatAiTestResult({ ok: false, error: 'timeout' })).toBe('timeout')
  })
})
