import { describe, expect, it } from 'vitest'
import {
  isValidEmail,
  isValidEmailCode,
  isValidOptionalPhone,
  isValidOptionalNickname,
  isValidPassword,
  normalizeEmail,
  sanitizeEmailCode,
} from './auth'

describe('auth validation', () => {
  it('normalizes and validates email addresses', () => {
    expect(normalizeEmail('  User.Name@Example.COM ')).toBe('user.name@example.com')
    expect(isValidEmail('user.name@example.com')).toBe(true)
    expect(isValidEmail('user@example')).toBe(false)
    expect(isValidEmail('user @example.com')).toBe(false)
    expect(isValidEmail('user@-example.com')).toBe(false)
  })

  it('allows an omitted phone but validates a supplied phone', () => {
    expect(isValidOptionalPhone('')).toBe(true)
    expect(isValidOptionalPhone('  ')).toBe(true)
    expect(isValidOptionalPhone('13800138000')).toBe(true)
    expect(isValidOptionalPhone('12000000000')).toBe(false)
    expect(isValidOptionalPhone('1380013800')).toBe(false)
  })

  it('keeps verification codes numeric and six digits long', () => {
    expect(sanitizeEmailCode('12a34-567')).toBe('123456')
    expect(isValidEmailCode('123456')).toBe(true)
    expect(isValidEmailCode('12345')).toBe(false)
    expect(isValidEmailCode('12345a')).toBe(false)
  })

  it('requires passwords to contain letters and numbers', () => {
    expect(isValidPassword('Abc12345')).toBe(true)
    expect(isValidPassword('abcdefgh')).toBe(false)
    expect(isValidPassword('12345678')).toBe(false)
    expect(isValidPassword('Abc1234')).toBe(false)
    expect(isValidPassword('A1234567'.repeat(10))).toBe(false)
    expect(isValidOptionalNickname('a'.repeat(50))).toBe(true)
    expect(isValidOptionalNickname('a'.repeat(51))).toBe(false)
  })
})
