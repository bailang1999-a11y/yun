import { apiClient } from './client'
import { stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type { AdminStaff, AdminStaffPayload } from '../types/operations'

export async function fetchAdminStaff() {
  const { data } = await apiClient.get<unknown>('/api/admin/staff')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeAdminStaff)
}

export async function createAdminStaff(payload: AdminStaffPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/staff', payload)
  return normalizeAdminStaff(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateAdminStaff(id: AdminStaff['id'], payload: AdminStaffPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/staff/${id}`, payload)
  return normalizeAdminStaff(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteAdminStaff(id: AdminStaff['id']) {
  await apiClient.post(`/api/admin/staff/${id}/delete`)
}

function normalizeAdminStaff(item: Record<string, unknown>): AdminStaff {
  return {
    id: text(item.id),
    account: text(item.account),
    nickname: text(item.nickname, '员工账号'),
    status: text(item.status, 'ENABLED'),
    permissions: stringArray(item.permissions),
    createdAt: text(item.createdAt) || undefined,
    updatedAt: text(item.updatedAt) || undefined
  }
}
