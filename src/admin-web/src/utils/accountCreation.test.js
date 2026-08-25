import { describe, expect, it } from 'vitest'
import {
  accountUpdateErrorMessage,
  buildAdminCreatePayload,
  buildUserCreatePayload,
  buildUserEditPayload,
  canEditUserLoginIdentifiers,
  isValidPassword,
  userLoginIdentifiersChanged,
  validateAdminCreateForm,
  validateUserCreateForm,
  validateUserEditForm,
} from './accountCreation'

describe('account creation helpers', () => {
  it('validates the password policy', () => {
    expect(isValidPassword('Admin12345')).toBe(true)
    expect(isValidPassword('12345678')).toBe(false)
    expect(isValidPassword('abcdefgh')).toBe(false)
    expect(isValidPassword('A1short')).toBe(false)
    expect(isValidPassword(undefined)).toBe(false)
  })

  it('normalizes a user payload and omits blank optional fields', () => {
    const form = {
      email: '  User@Example.COM ', password: 'Admin12345', phone: ' ', nickname: '', timezone: '',
    }
    expect(validateUserCreateForm(form)).toBe('')
    expect(buildUserCreatePayload(form)).toEqual({
      email: 'user@example.com', password: 'Admin12345', timezone: 'Asia/Shanghai',
    })
  })

  it('rejects invalid user fields before submission', () => {
    const form = {
      email: 'invalid', password: 'password', phone: '123', nickname: 'A'.repeat(51), timezone: 'Mars/Olympus',
    }
    expect(validateUserCreateForm(form)).toBe('请输入正确的邮箱地址')
    expect(validateUserCreateForm({ ...form, email: 'user@example.com' })).toBe('请输入正确的中国大陆手机号')
  })

  it('validates and normalizes editable user profile fields without status or password', () => {
    const form = {
      id: 18,
      email: '  Member@Example.COM ',
      phone: ' 13800138000 ',
      nickname: ' 小明 ',
      timezone: ' Asia/Shanghai ',
      profileVersion: 3,
      status: 'disabled',
      password: 'should-not-be-sent',
    }
    expect(validateUserEditForm(form)).toBe('')
    expect(buildUserEditPayload(form)).toEqual({
      email: 'member@example.com',
      phone: '13800138000',
      nickname: '小明',
      timezone: 'Asia/Shanghai',
      profileVersion: 3,
    })
  })

  it('keeps blank editable optional fields so they can be cleared', () => {
    expect(buildUserEditPayload({
      id: 18, email: 'member@example.com', phone: ' ', nickname: '', timezone: '', profileVersion: 4,
    })).toEqual({
      email: 'member@example.com', phone: '', nickname: '', timezone: 'Asia/Shanghai', profileVersion: 4,
    })
  })

  it('supports legacy phone-only users but requires at least one login identifier', () => {
    const phoneOnly = {
      id: 18, email: '', phone: '13800138000', nickname: 'User', timezone: 'Asia/Shanghai', profileVersion: 0,
    }
    expect(validateUserEditForm(phoneOnly)).toBe('')
    expect(validateUserEditForm({ ...phoneOnly, phone: '' })).toBe('邮箱和手机号至少填写一项')
    expect(validateUserEditForm({ ...phoneOnly, email: 'invalid' })).toBe('请输入正确的邮箱地址')
  })

  it('maps user update errors to actionable Chinese messages', () => {
    expect(accountUpdateErrorMessage({ code: 409, message: 'email already registered' })).toBe('该邮箱已注册')
    expect(accountUpdateErrorMessage({ code: 404, message: 'user not found' })).toBe('用户不存在或已被删除')
    expect(accountUpdateErrorMessage({ code: 403, message: 'forbidden' })).toBe('当前账号没有编辑用户的权限')
    expect(accountUpdateErrorMessage({ code: 409, message: 'user information changed' }))
      .toBe('用户资料已被其他管理员修改，请刷新后重试')
    expect(accountUpdateErrorMessage({ code: 409, message: 'email or phone already registered' }))
      .toBe('邮箱或手机号已被其他用户使用')
    expect(accountUpdateErrorMessage({ code: 400, message: 'email or phone is required' }))
      .toBe('邮箱和手机号至少填写一项')
  })

  it('limits login identifier editing to super administrators', () => {
    expect(canEditUserLoginIdentifiers('super_admin')).toBe(true)
    expect(canEditUserLoginIdentifiers('admin')).toBe(false)
    expect(canEditUserLoginIdentifiers(undefined)).toBe(false)
  })

  it('detects normalized login identifier changes for confirmation', () => {
    const original = { email: 'user@example.com', phone: '13800138000' }
    expect(userLoginIdentifiersChanged({
      email: ' User@Example.COM ', phone: ' 13800138000 ', nickname: '', timezone: '',
    }, original)).toBe(false)
    expect(userLoginIdentifiersChanged({
      email: 'new@example.com', phone: '13800138000', nickname: '', timezone: '',
    }, original)).toBe(true)
  })

  it('preserves original login identifiers for normal administrators', () => {
    const form = {
      id: 18,
      email: 'changed@example.com',
      phone: '13900139000',
      nickname: '可修改昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 7,
    }
    expect(buildUserEditPayload(form, {
      original: { email: 'original@example.com', phone: '13800138000', profileVersion: 7 },
      canEditLoginIdentifiers: false,
    })).toEqual({
      email: 'original@example.com',
      phone: '13800138000',
      nickname: '可修改昵称',
      timezone: 'Asia/Shanghai',
      profileVersion: 7,
    })
  })

  it('rejects a missing profile version before updating', () => {
    expect(validateUserEditForm({
      id: 18, email: 'member@example.com', phone: '', nickname: 'User', timezone: 'Asia/Shanghai',
    })).toBe('用户资料版本无效，请刷新后重试')
    expect(validateUserEditForm({
      id: 18, email: 'member@example.com', phone: '', nickname: 'User', timezone: 'Asia/Shanghai', profileVersion: null,
    })).toBe('用户资料版本无效，请刷新后重试')
  })

  it('always creates a normal administrator payload', () => {
    const form = { username: '  operator ', password: 'Admin12345', role: 'super_admin' }
    expect(validateAdminCreateForm(form)).toBe('')
    expect(buildAdminCreatePayload(form)).toEqual({
      username: 'operator', password: 'Admin12345', role: 'admin',
    })
  })
})
