import { apiRequest } from './http'
import type {
  EmailCodeRequest,
  EmailCodeResponse,
  LoginForm,
  ResetPasswordForm,
  UpdateEmailForm,
  UpdateEmailResponse,
} from '../types'

export interface AuthTokens {
  accessToken: string
  refreshToken: string
}

export interface RegistrationStatus {
  registrationEnabled: boolean
}

export interface RegisterPayload {
  email: string
  code: string
  password: string
  phone?: string
  nickname?: string
  timezone: string
}

export function loginAccount(payload: LoginForm) {
  return apiRequest<AuthTokens>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchRegistrationStatus() {
  return apiRequest<RegistrationStatus>('/auth/registration-status')
}

export function registerAccount(payload: RegisterPayload) {
  return apiRequest<AuthTokens>('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function sendEmailVerificationCode(payload: EmailCodeRequest, token = '') {
  return apiRequest<EmailCodeResponse>('/auth/email-code', {
    method: 'POST',
    body: JSON.stringify(payload),
  }, token)
}

export function resetAccountPassword(payload: Omit<ResetPasswordForm, 'confirmPassword'>) {
  return apiRequest<void>('/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateAccountEmail(
  request: <T>(path: string, options?: RequestInit) => Promise<T>,
  payload: UpdateEmailForm,
) {
  return request<UpdateEmailResponse>('/user/email', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
