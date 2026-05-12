import { apiClient } from './client'
import { text } from './normalize'
import { type ApiEnvelope, unwrapValue } from './response'
import type { CaptchaChallenge, CaptchaSetting, CaptchaSettingPayload } from '../types/operations'

function recordMap(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, text(item)]))
}

function normalizeSetting(item: Record<string, unknown>): CaptchaSetting {
  return {
    enabled: Boolean(item.enabled),
    adminLoginEnabled: Boolean(item.adminLoginEnabled),
    h5LoginEnabled: Boolean(item.h5LoginEnabled),
    webLoginEnabled: Boolean(item.webLoginEnabled),
    provider: text(item.provider, 'TENCENT'),
    tencentConfig: recordMap(item.tencentConfig),
    genericConfig: recordMap(item.genericConfig)
  }
}

function normalizeChallenge(item: Record<string, unknown>): CaptchaChallenge {
  return {
    enabled: Boolean(item.enabled),
    provider: text(item.provider, 'TENCENT'),
    appId: text(item.appId),
    scene: text(item.scene, 'login')
  }
}

export async function fetchCaptchaSetting() {
  const { data } = await apiClient.get<unknown>('/api/admin/captcha-settings')
  return normalizeSetting(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateCaptchaSetting(payload: CaptchaSettingPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/captcha-settings', payload)
  return normalizeSetting(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function testCaptchaSetting(payload: CaptchaSettingPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/captcha-settings/test', payload)
  return text(unwrapValue<unknown>(data as ApiEnvelope<unknown>), '配置测试通过')
}

export async function fetchAdminCaptchaChallenge() {
  const { data } = await apiClient.get<unknown>('/api/admin/auth/captcha-config')
  return normalizeChallenge(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}
