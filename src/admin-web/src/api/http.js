export const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'

/** multipart 上传：不能手工设置 Content-Type，否则浏览器无法生成 boundary。 */
export async function apiUpload(path, form, token = '') {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API_BASE}${path}`, { method: 'POST', body: form, headers })
  const body = await response.json().catch(() => ({ code: response.status, message: '上传失败' }))
  if (body.code !== 0) {
    const error = new Error(body.message || '上传失败')
    error.code = body.code
    throw error
  }
  return body.data
}

export async function apiRequest(path, options = {}, token = '') {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const body = await response.json().catch(() => ({ code: response.status, message: '请求失败' }))
  if (body.code !== 0) {
    const error = new Error(body.message || '请求失败')
    error.code = body.code
    error.data = body.data
    throw error
  }
  return body.data
}
