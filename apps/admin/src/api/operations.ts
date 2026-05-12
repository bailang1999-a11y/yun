import { apiClient } from './client'
import { numberValue, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type { OperationLog, PaymentRecord, RefundRecord, SmsLog, SystemSetting } from '../types/operations'

export async function fetchSettings() {
  const { data } = await apiClient.get<unknown>('/api/admin/settings')

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateSettings(payload: SystemSetting) {
  const { data } = await apiClient.post<unknown>('/api/admin/settings', payload)

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchPayments() {
  const { data } = await apiClient.get<unknown>('/api/admin/payments')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizePayment)
}

export async function fetchRefunds() {
  const { data } = await apiClient.get<unknown>('/api/admin/refunds')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeRefund)
}

export async function fetchSmsLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/sms-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeSmsLog)
}

export async function fetchOperationLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/operation-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeOperationLog)
}

function normalizeSettings(item: Record<string, unknown>): SystemSetting {
  const receivers = item.notificationReceivers
  return {
    siteName: text(item.siteName, '喜易云'),
    logoUrl: text(item.logoUrl),
    customerService: text(item.customerService),
    companyName: text(item.companyName),
    icpRecordNo: text(item.icpRecordNo),
    policeRecordNo: text(item.policeRecordNo),
    disclaimer: text(item.disclaimer),
    paymentMode: text(item.paymentMode, 'MOCK'),
    autoRefundEnabled: Boolean(item.autoRefundEnabled),
    smsProvider: text(item.smsProvider, 'TENCENT'),
    smsEnabled: Boolean(item.smsEnabled),
    upstreamSyncSeconds: numberValue(item.upstreamSyncSeconds, 30),
    autoShelfEnabled: Boolean(item.autoShelfEnabled),
    autoPriceEnabled: Boolean(item.autoPriceEnabled),
    registrationEnabled: item.registrationEnabled !== false,
    registrationType: text(item.registrationType, 'MOBILE'),
    defaultUserGroupId: text(item.defaultUserGroupId, '1'),
    notificationReceivers: receivers && typeof receivers === 'object' ? (receivers as Record<string, string>) : {}
  }
}

function normalizePayment(item: Record<string, unknown>): PaymentRecord {
  return {
    paymentNo: text(item.paymentNo),
    orderNo: text(item.orderNo),
    userId: text(item.userId),
    method: text(item.method),
    amount: numberValue(item.amount),
    status: text(item.status),
    channelTradeNo: text(item.channelTradeNo),
    createdAt: text(item.createdAt),
    paidAt: text(item.paidAt)
  }
}

function normalizeRefund(item: Record<string, unknown>): RefundRecord {
  return {
    refundNo: text(item.refundNo),
    orderNo: text(item.orderNo),
    paymentNo: text(item.paymentNo),
    userId: text(item.userId),
    amount: numberValue(item.amount),
    status: text(item.status),
    reason: text(item.reason),
    createdAt: text(item.createdAt),
    refundedAt: text(item.refundedAt)
  }
}

function normalizeSmsLog(item: Record<string, unknown>): SmsLog {
  return {
    id: text(item.id),
    orderNo: text(item.orderNo),
    mobile: text(item.mobile),
    templateType: text(item.templateType),
    content: text(item.content),
    status: text(item.status),
    errorMessage: text(item.errorMessage),
    createdAt: text(item.createdAt)
  }
}

function normalizeOperationLog(item: Record<string, unknown>): OperationLog {
  return {
    id: text(item.id),
    operator: text(item.operator),
    action: text(item.action),
    resourceType: text(item.resourceType),
    resourceId: text(item.resourceId),
    remark: text(item.remark),
    createdAt: text(item.createdAt)
  }
}
