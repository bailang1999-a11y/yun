import { apiClient } from './client'
import { unwrapResponse, unwrapValue } from './response'
import type { PaymentChannel, PaymentChannelPayload } from '../types/operations'

type AnyRecord = Record<string, unknown>

function isRecord(value: unknown): value is AnyRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(value: unknown, fallback = '') {
  return value === null || value === undefined ? fallback : String(value)
}

function numberValue(value: unknown, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function stringMap(value: unknown) {
  if (!isRecord(value)) return {}
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, text(item)]))
}

function normalizeChannel(item: unknown): PaymentChannel {
  const record = isRecord(item) ? item : {}
  const terminals = Array.isArray(record.terminals) ? record.terminals.map((value) => text(value)).filter(Boolean) : []
  return {
    id: text(record.id),
    code: text(record.code),
    name: text(record.name, '支付通道'),
    type: text(record.type, 'CUSTOM'),
    terminals,
    status: text(record.status, 'ENABLED'),
    sort: numberValue(record.sort, 10),
    config: stringMap(record.config),
    remark: text(record.remark),
    createdAt: text(record.createdAt) || undefined,
    updatedAt: text(record.updatedAt) || undefined
  }
}

export async function fetchPaymentChannels() {
  const { data } = await apiClient.get<unknown>('/api/admin/payment-channels')
  return unwrapResponse<unknown[]>(data as unknown[]).map(normalizeChannel)
}

export async function createPaymentChannel(payload: PaymentChannelPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/payment-channels', payload)
  return normalizeChannel(unwrapValue(data))
}

export async function updatePaymentChannel(id: PaymentChannel['id'], payload: PaymentChannelPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/payment-channels/${encodeURIComponent(String(id))}`, payload)
  return normalizeChannel(unwrapValue(data))
}

export async function enablePaymentChannel(id: PaymentChannel['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/payment-channels/${encodeURIComponent(String(id))}/enable`)
  return normalizeChannel(unwrapValue(data))
}

export async function disablePaymentChannel(id: PaymentChannel['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/payment-channels/${encodeURIComponent(String(id))}/disable`)
  return normalizeChannel(unwrapValue(data))
}

export async function deletePaymentChannel(id: PaymentChannel['id']) {
  await apiClient.post(`/api/admin/payment-channels/${encodeURIComponent(String(id))}/delete`)
}
