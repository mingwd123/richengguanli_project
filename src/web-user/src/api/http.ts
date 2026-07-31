const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'

declare global {
  interface Window {
    __DAYLIANE_HTTP_FETCH__?: typeof fetch
  }
}

export async function apiRequest<T = any>(path: string, options: RequestInit = {}, token = ''): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(options.headers as Record<string, string> || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const transport = window.__DAYLIANE_HTTP_FETCH__ || window.fetch.bind(window)
  const response = await transport(`${API_BASE}${path}`, { ...options, headers })
  const body = await response.json().catch(() => ({ code: response.status, message: '请求失败' }))
  if (body.code !== 0) {
    const error: any = new Error(body.message || '请求失败')
    error.code = body.code
    error.data = body.data
    throw error
  }
  return body.data as T
}
