const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'

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
