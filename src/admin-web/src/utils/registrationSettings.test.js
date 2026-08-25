import { describe, expect, it } from 'vitest'
import {
  buildRegistrationSettingsPayload,
  normalizeRegistrationSettings,
  registrationSourceLabel,
} from './registrationSettings'

describe('registration settings helpers', () => {
  it('normalizes a valid settings response', () => {
    expect(normalizeRegistrationSettings({
      registrationEnabled: true,
      source: ' DATABASE ',
      updatedAt: ' 2026-08-25T12:30:00Z ',
    })).toEqual({
      registrationEnabled: true,
      source: 'database',
      updatedAt: '2026-08-25T12:30:00Z',
    })
  })

  it('rejects responses without an explicit boolean state', () => {
    expect(normalizeRegistrationSettings(null)).toBeNull()
    expect(normalizeRegistrationSettings({ registrationEnabled: 'false' })).toBeNull()
    expect(normalizeRegistrationSettings({})).toBeNull()
  })

  it('builds a strict boolean update payload', () => {
    expect(buildRegistrationSettingsPayload(true)).toEqual({ registrationEnabled: true })
    expect(buildRegistrationSettingsPayload(false)).toEqual({ registrationEnabled: false })
    expect(buildRegistrationSettingsPayload('true')).toEqual({ registrationEnabled: false })
  })

  it('describes known and unknown configuration sources', () => {
    expect(registrationSourceLabel('database')).toBe('后台设置')
    expect(registrationSourceLabel('environment')).toBe('环境默认')
    expect(registrationSourceLabel('default')).toBe('系统默认')
    expect(registrationSourceLabel('future-source')).toBe('未标注')
  })
})
