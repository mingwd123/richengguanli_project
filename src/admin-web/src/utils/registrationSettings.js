const SOURCE_LABELS = {
  admin: '后台设置',
  database: '后台设置',
  persisted: '后台设置',
  environment: '环境默认',
  default: '系统默认',
}

export function normalizeRegistrationSettings(settings) {
  if (!settings || typeof settings.registrationEnabled !== 'boolean') return null

  return {
    registrationEnabled: settings.registrationEnabled,
    source: typeof settings.source === 'string' ? settings.source.trim().toLowerCase() : '',
    updatedAt: typeof settings.updatedAt === 'string' ? settings.updatedAt.trim() : '',
  }
}

export function buildRegistrationSettingsPayload(enabled) {
  return { registrationEnabled: enabled === true }
}

export function registrationSourceLabel(source) {
  const normalizedSource = typeof source === 'string' ? source.trim().toLowerCase() : ''
  return SOURCE_LABELS[normalizedSource] || '未标注'
}
