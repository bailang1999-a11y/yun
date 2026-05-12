import { apiClient } from './client'
import { numberValue, text } from './normalize'
import { type ApiEnvelope, unwrapValue } from './response'
import type { SmsLoginSetting, SmsLoginSettingPayload } from '../types/operations'

type AnyRecord = Record<string, unknown>

function isRecord(value: unknown): value is AnyRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringMap(value: unknown) {
  if (!isRecord(value)) return {}
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, text(item)]))
}

function normalizeSetting(item: Record<string, unknown>): SmsLoginSetting {
  return {
    enabled: Boolean(item.enabled),
    adminLoginEnabled: Boolean(item.adminLoginEnabled),
    h5LoginEnabled: Boolean(item.h5LoginEnabled),
    webLoginEnabled: Boolean(item.webLoginEnabled),
    provider: text(item.provider, 'TENCENT'),
    adminMobile: text(item.adminMobile),
    codeLength: numberValue(item.codeLength, 6),
    ttlSeconds: numberValue(item.ttlSeconds, 300),
    cooldownSeconds: numberValue(item.cooldownSeconds, 60),
    maxAttempts: numberValue(item.maxAttempts, 5),
    genericConfig: stringMap(item.genericConfig),
    tencentConfig: stringMap(item.tencentConfig),
    aliyunConfig: stringMap(item.aliyunConfig)
  }
}

export async function fetchSmsLoginSetting() {
  const { data } = await apiClient.get<unknown>('/api/admin/sms-login-settings')
  return normalizeSetting(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateSmsLoginSetting(payload: SmsLoginSettingPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/sms-login-settings', payload)
  return normalizeSetting(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function sendAdminLoginSms(account: string, captchaTicket = '', captchaRandstr = '') {
  const { data } = await apiClient.post<unknown>('/api/admin/auth/sms/send', { account, terminal: 'admin', captchaTicket, captchaRandstr })
  return text(unwrapValue(data as ApiEnvelope<string>), '验证码已发送')
}
