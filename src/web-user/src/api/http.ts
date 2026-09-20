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
  let response: Response
  try {
    response = await transport(`${API_BASE}${path}`, { ...options, headers })
  } catch {
    const error: any = new Error('无法连接到后端，请先启动后端服务（127.0.0.1:8080）')
    error.code = 'NETWORK_UNAVAILABLE'
    throw error
  }
  const body = await response.json().catch(() => ({ code: response.status, message: '请求失败' }))
  if (body.code !== 0) {
    const error: any = new Error(body.message || '请求失败')
    error.code = body.code
    error.data = body.data
    throw error
  }
  return body.data as T
}

/** multipart 上传：不能手工设置 Content-Type，否则浏览器无法生成 boundary。 */
export async function apiUpload<T = any>(path: string, form: FormData, token = ''): Promise<T> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const transport = window.__DAYLIANE_HTTP_FETCH__ || window.fetch.bind(window)
  let response: Response
  try {
    response = await transport(`${API_BASE}${path}`, { method: 'POST', body: form, headers })
  } catch {
    const error: any = new Error('无法连接到后端，请先启动后端服务（127.0.0.1:8080）')
    error.code = 'NETWORK_UNAVAILABLE'
    throw error
  }
  const body = await response.json().catch(() => ({ code: response.status, message: '上传失败' }))
  if (body.code !== 0) {
    const error: any = new Error(body.message || '上传失败')
    error.code = body.code
    throw error
  }
  return body.data as T
}

export async function apiDownload(path: string, token = ''): Promise<Blob> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const transport = window.__DAYLIANE_HTTP_FETCH__ || window.fetch.bind(window)
  let response: Response
  try {
    response = await transport(`${API_BASE}${path}`, { headers })
  } catch {
    throw new Error('无法连接到后端，请先启动后端服务（127.0.0.1:8080）')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: '下载失败' }))
    throw new Error(body.message || '下载失败')
  }
  return response.blob()
}
