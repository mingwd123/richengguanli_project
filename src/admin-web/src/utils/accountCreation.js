const EMAIL_PATTERN = /^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$/i
const PHONE_PATTERN = /^1[3-9]\d{9}$/

function validateUserProfileFields(form, { requireEmail = true, requireContact = false } = {}) {
  const email = form.email.trim().toLowerCase()
  const phone = form.phone.trim()
  const nickname = form.nickname.trim()
  const timezone = form.timezone.trim() || 'Asia/Shanghai'

  if (requireContact && !email && !phone) return '邮箱和手机号至少填写一项'
  if (requireEmail && !email) return '请输入正确的邮箱地址'
  if (email && (email.length > 254 || !EMAIL_PATTERN.test(email))) return '请输入正确的邮箱地址'
  if (phone && !PHONE_PATTERN.test(phone)) return '请输入正确的中国大陆手机号'
  if (nickname.length > 50) return '昵称不能超过 50 个字符'
  try {
    new Intl.DateTimeFormat('zh-CN', { timeZone: timezone }).format()
  } catch {
    return '请输入有效的 IANA 时区，例如 Asia/Shanghai'
  }
  return ''
}

function normalizedUserProfile(form) {
  return {
    email: form.email.trim().toLowerCase(),
    phone: form.phone.trim(),
    nickname: form.nickname.trim(),
    timezone: form.timezone.trim() || 'Asia/Shanghai',
  }
}

function normalizedProfileVersion(value) {
  if (value === null || value === undefined || value === '') return null
  const version = Number(value)
  return Number.isInteger(version) && version >= 0 ? version : null
}

export function isValidPassword(password) {
  return typeof password === 'string'
    && password.length >= 8
    && new TextEncoder().encode(password).length <= 72
    && /[A-Za-z]/.test(password)
    && /\d/.test(password)
}

export function validateUserCreateForm(form) {
  const profileValidationError = validateUserProfileFields(form)
  if (profileValidationError) return profileValidationError
  if (!isValidPassword(form.password)) return '密码至少 8 位且包含字母和数字，最多 72 字节'
  return ''
}

export function validateUserEditForm(form) {
  if (!Number.isInteger(Number(form.id)) || Number(form.id) <= 0) return '请选择要编辑的用户'
  if (normalizedProfileVersion(form.profileVersion) === null) return '用户资料版本无效，请刷新后重试'
  return validateUserProfileFields(form, { requireEmail: false, requireContact: true })
}

export function canEditUserLoginIdentifiers(role) {
  return role === 'super_admin'
}

export function userLoginIdentifiersChanged(form, original) {
  const current = normalizedUserProfile(form)
  const initial = normalizedUserProfile({
    email: original?.email || '',
    phone: original?.phone || '',
    nickname: '',
    timezone: 'Asia/Shanghai',
  })
  return current.email !== initial.email || current.phone !== initial.phone
}

export function validateAdminCreateForm(form) {
  const username = form.username.trim()
  if (!username) return '请输入管理员账号'
  if (username.length > 50) return '管理员账号不能超过 50 个字符'
  if (!isValidPassword(form.password)) return '密码至少 8 位且包含字母和数字，最多 72 字节'
  return ''
}

export function buildUserCreatePayload(form) {
  const profile = normalizedUserProfile(form)
  const payload = {
    email: profile.email,
    password: form.password,
    timezone: profile.timezone,
  }
  if (profile.phone) payload.phone = profile.phone
  if (profile.nickname) payload.nickname = profile.nickname
  return payload
}

export function buildUserEditPayload(form, { original, canEditLoginIdentifiers = true } = {}) {
  const profile = normalizedUserProfile(form)
  const profileVersion = normalizedProfileVersion(original?.profileVersion ?? form.profileVersion)
  if (canEditLoginIdentifiers) return { ...profile, profileVersion }

  const initial = normalizedUserProfile({
    email: original?.email || '',
    phone: original?.phone || '',
    nickname: profile.nickname,
    timezone: profile.timezone,
  })
  return { ...profile, email: initial.email, phone: initial.phone, profileVersion }
}

export function buildAdminCreatePayload(form) {
  return {
    username: form.username.trim(),
    password: form.password,
    role: 'admin',
  }
}

export function accountCreationErrorMessage(error) {
  if (Number(error?.code) === 403) return '当前账号没有创建该账号的权限'
  return accountFieldErrorMessage(error) || error?.message || '创建失败，请稍后重试'
}

export function accountUpdateErrorMessage(error) {
  if (Number(error?.code) === 403) return '当前账号没有编辑用户的权限'
  if (Number(error?.code) === 404 || error?.message === 'user not found') return '用户不存在或已被删除'
  return accountFieldErrorMessage(error) || error?.message || '保存失败，请稍后重试'
}

function accountFieldErrorMessage(error) {
  const messages = {
    'user information changed': '用户资料已被其他管理员修改，请刷新后重试',
    'email already registered': '该邮箱已注册',
    'email or phone already registered': '邮箱或手机号已被其他用户使用',
    'email or phone is required': '邮箱和手机号至少填写一项',
    'email format is invalid': '邮箱格式不正确',
    'email is required': '请输入邮箱地址',
    'phone already registered': '该手机号已注册',
    'admin username already exists': '该管理员账号已存在',
    'username is invalid': '管理员账号格式不正确',
    'password format is invalid': '密码至少 8 位且包含字母和数字，最多 72 字节',
    'phone format is invalid': '手机号格式不正确',
    'nickname is invalid': '昵称格式不正确',
    'timezone is invalid': '时区格式不正确',
    'username is required': '请输入管理员账号',
    'role is invalid': '管理员角色不正确',
    'profileVersion must be a non-negative integer': '用户资料版本无效，请刷新后重试',
  }
  return messages[error?.message] || ''
}
