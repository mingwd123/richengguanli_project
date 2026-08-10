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
    expect(buildAiKeyPayload({ name: ' Backup ', apiKey: '   ', enabled: false, remark: ' standby ' })).toEqual({
      name: 'Backup',
      enabled: false,
      remark: 'standby',
    })
  })

  it('submits a trimmed new key', () => {
    expect(buildAiKeyPayload({ name: ' Primary ', apiKey: ' sk-new-key ', enabled: true, remark: '' })).toEqual({
      name: 'Primary',
      apiKey: 'sk-new-key',
      enabled: true,
      remark: '',
    })
  })

  it('requires a key only when creating', () => {
    const form = { name: 'Primary', apiKey: ' ' }
    expect(validateAiKeyForm(form, false)).toBe('请输入 API Key')
    expect(validateAiKeyForm(form, true)).toBe('')
    expect(validateAiKeyForm({ name: ' ', apiKey: 'sk-key' }, false)).toBe('请输入 Key 名称')
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
