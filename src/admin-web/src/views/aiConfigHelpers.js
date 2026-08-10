export function buildAiConfigPayload(form) {
  return {
    provider: String(form.provider || '').trim(),
    modelName: String(form.modelName || '').trim(),
    apiBaseUrl: String(form.apiBaseUrl || '').trim(),
    remark: String(form.remark || '').trim(),
  }
}

export function buildAiKeyPayload(form) {
  const apiKey = String(form.apiKey || '').trim()
  const payload = {
    name: String(form.name || '').trim(),
    enabled: form.enabled !== false,
    remark: String(form.remark || '').trim(),
  }

  // Editing with an empty field preserves the encrypted key already stored by the backend.
  if (apiKey) payload.apiKey = apiKey

  return payload
}

export function validateAiKeyForm(form, editing = false) {
  if (!String(form.name || '').trim()) return '请输入 Key 名称'
  if (!editing && !String(form.apiKey || '').trim()) return '请输入 API Key'
  return ''
}

export function moveAiKeyIds(keys, keyId, direction) {
  if (!Array.isArray(keys) || !['up', 'down'].includes(direction)) return null

  const index = keys.findIndex(key => String(key.id) === String(keyId))
  const targetIndex = direction === 'up' ? index - 1 : index + 1
  if (index < 0 || targetIndex < 0 || targetIndex >= keys.length) return null

  const orderedIds = keys.map(key => key.id)
  ;[orderedIds[index], orderedIds[targetIndex]] = [orderedIds[targetIndex], orderedIds[index]]
  return orderedIds
}

export function formatAiTestResult(result) {
  if (!result) return ''
  if (!result.ok) return result.error || result.message || '连接测试失败'

  const source = result.source === 'environment'
    ? '环境变量兜底 Key'
    : result.keyName
      ? `Key「${result.keyName}」`
      : '数据库 Key'
  const attemptCount = Array.isArray(result.attempts)
    ? result.attempts.length
    : Number(result.attempts) || 0
  const attempts = attemptCount > 0 ? `，尝试 ${attemptCount} 次` : ''
  const separator = result.source === 'environment' ? ' ' : ''
  return `${source}${separator}调用成功${attempts}`
}
