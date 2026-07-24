const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'

export async function apiRequest(path, options = {}, token = '') {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const body = await res.json().catch(() => ({ code: res.status, message: 'request failed' }))
  if (body.code !== 0) throw new Error(body.message || 'request failed')
  return body.data
}
