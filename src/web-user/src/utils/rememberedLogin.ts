/**
 * 登录页「记住密码」的本地存储读写。
 *
 * 安全说明：localStorage 对同源脚本完全可读，这里的编码只是不让密码以明文形式
 * 直接躺在开发者工具里，**不构成加密保护**。共用设备上不要勾选「记住密码」。
 */
export const REMEMBERED_LOGIN_KEY = 'dayliane_login_remembered'

export interface RememberedLogin {
  account: string
  password: string
}

function encode(value: string) {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function decode(value: string) {
  try {
    const binary = atob(value)
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    return new TextDecoder().decode(bytes)
  } catch { return '' }
}

/** 读取已记住的账号密码；不存在、格式损坏或字段不全时返回 null。 */
export function readRememberedLogin(): RememberedLogin | null {
  try {
    const raw = localStorage.getItem(REMEMBERED_LOGIN_KEY)
    if (!raw) return null
    const parsed = JSON.parse(decode(raw)) as Partial<RememberedLogin> | null
    if (!parsed?.account || !parsed?.password) return null
    return { account: parsed.account, password: parsed.password }
  } catch { return null }
}

/** 登录成功后写入；浏览器隐私模式等不可写场景静默忽略，不影响登录本身。 */
export function saveRememberedLogin(account: string, password: string) {
  if (!account || !password) return
  try {
    localStorage.setItem(REMEMBERED_LOGIN_KEY, encode(JSON.stringify({ account, password })))
  } catch { /* ignore */ }
}

export function clearRememberedLogin() {
  try { localStorage.removeItem(REMEMBERED_LOGIN_KEY) } catch { /* ignore */ }
}
