import { apiClient } from './client'
import { numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapValue } from './response'
import type { AdminAuthSession, AdminProfile } from '../types/operations'

export async function loginAdmin(account: string, password: string) {
  const { data } = await apiClient.post<unknown>('/api/admin/auth/login', { account, password })

  const session = normalizeAdminAuthSession(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
  if (!session.token) {
    throw new Error('login failed')
  }
  return session
}

export async function fetchAdminMe() {
  const { data } = await apiClient.get<unknown>('/api/admin/auth/me')

  return normalizeAdminProfile(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

function normalizeAdminProfile(item: Record<string, unknown>): AdminProfile {
  return {
    id: text(item.id),
    username: text(item.username, 'admin'),
    nickname: text(item.nickname, '运营管理员'),
    balance: numberValue(item.balance),
    permissions: stringArray(item.permissions)
  }
}

function normalizeAdminAuthSession(item: Record<string, unknown>): AdminAuthSession {
  return {
    token: text(item.token),
    profile: normalizeAdminProfile((item.profile || {}) as Record<string, unknown>)
  }
}
