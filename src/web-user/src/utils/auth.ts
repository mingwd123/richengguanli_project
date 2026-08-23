export const EMAIL_PATTERN = /^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$/i
export const PHONE_PATTERN = /^1[3-9]\d{9}$/
export const EMAIL_CODE_PATTERN = /^\d{6}$/

export function normalizeEmail(email: string) {
  return email.trim().toLowerCase()
}

export function isValidEmail(email: string) {
  const normalized = normalizeEmail(email)
  return normalized.length <= 254 && EMAIL_PATTERN.test(normalized)
}

export function isValidOptionalPhone(phone: string) {
  const normalized = phone.trim()
  return normalized === '' || PHONE_PATTERN.test(normalized)
}

export function sanitizeEmailCode(code: string) {
  return code.replace(/\D/g, '').slice(0, 6)
}

export function isValidEmailCode(code: string) {
  return EMAIL_CODE_PATTERN.test(code)
}

export function isValidPassword(password: string) {
  return password.length >= 8
    && new TextEncoder().encode(password).length <= 72
    && /[A-Za-z]/.test(password)
    && /\d/.test(password)
}

export function isValidOptionalNickname(nickname: string) {
  return nickname.trim().length <= 50
}
