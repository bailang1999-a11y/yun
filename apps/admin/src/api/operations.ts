import { apiClient } from './client'
import { cleanParams, numberValue, text } from './normalize'
import { type ApiEnvelope, type PageResult, unwrapPage, unwrapValue } from './response'
import type {
  OperationLog,
  PaymentRecord,
  RefundRecord,
  SmsLog,
  SystemSetting,
  WeComNotificationEvent,
  WeComRobotDelivery
} from '../types/operations'

export async function fetchSettings() {
  const { data } = await apiClient.get<unknown>('/api/admin/settings')

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateSettings(payload: SystemSetting) {
  const { data } = await apiClient.post<unknown>('/api/admin/settings', payload)

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function sendWeComRobotTest() {
  const { data } = await apiClient.post<unknown>('/api/admin/wecom-robot/test')
  return unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)
}

export async function fetchWeComRobotDeliveries(limit = 5): Promise<WeComRobotDelivery[]> {
  const { data } = await apiClient.get<unknown>('/api/admin/wecom-robot/deliveries', { params: { limit } })
  const value = unwrapValue<unknown>(data as ApiEnvelope<unknown>)
  const items = Array.isArray(value)
    ? value
    : value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>).items)
      ? (value as Record<string, unknown>).items as unknown[]
      : []

  return items
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    .map(normalizeWeComRobotDelivery)
}

export type PageQuery = { page?: number; pageSize?: number }

export async function fetchPayments(query: PageQuery = {}) {
  return (await fetchPaymentsPage(query)).items
}

export async function fetchPaymentsPage(query: PageQuery = {}): Promise<PageResult<PaymentRecord>> {
  const { data } = await apiClient.get<unknown>('/api/admin/payments', { params: cleanParams(query) })
  const page = unwrapPage<Record<string, unknown>>(data)
  return { ...page, items: page.items.map(normalizePayment) }
}

export async function fetchRefunds(query: PageQuery = {}) {
  return (await fetchRefundsPage(query)).items
}

export async function fetchRefundsPage(query: PageQuery = {}): Promise<PageResult<RefundRecord>> {
  const { data } = await apiClient.get<unknown>('/api/admin/refunds', { params: cleanParams(query) })
  const page = unwrapPage<Record<string, unknown>>(data)
  return { ...page, items: page.items.map(normalizeRefund) }
}

export async function fetchSmsLogs(query: PageQuery = {}) {
  return (await fetchSmsLogsPage(query)).items
}

export async function fetchSmsLogsPage(query: PageQuery = {}): Promise<PageResult<SmsLog>> {
  const { data } = await apiClient.get<unknown>('/api/admin/sms-logs', { params: cleanParams(query) })
  const page = unwrapPage<Record<string, unknown>>(data)
  return { ...page, items: page.items.map(normalizeSmsLog) }
}

export async function fetchOperationLogs(query: PageQuery = {}) {
  return (await fetchOperationLogsPage(query)).items
}

export async function fetchOperationLogsPage(query: PageQuery = {}): Promise<PageResult<OperationLog>> {
  const { data } = await apiClient.get<unknown>('/api/admin/operation-logs', { params: cleanParams(query) })
  const page = unwrapPage<Record<string, unknown>>(data)
  return { ...page, items: page.items.map(normalizeOperationLog) }
}

function normalizeSettings(item: Record<string, unknown>): SystemSetting {
  const receivers = item.notificationReceivers
  const wecomRobot = item.wecomRobot && typeof item.wecomRobot === 'object'
    ? item.wecomRobot as Record<string, unknown>
    : {}
  const events = Array.isArray(wecomRobot.events)
    ? wecomRobot.events.filter((event): event is WeComNotificationEvent => typeof event === 'string')
    : []
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
    notificationReceivers: receivers && typeof receivers === 'object' ? (receivers as Record<string, string>) : {},
    wecomRobot: {
      enabled: Boolean(wecomRobot.enabled),
      webhookUrl: text(wecomRobot.webhookUrl),
      events
    }
  }
}

function normalizeWeComRobotDelivery(item: Record<string, unknown>): WeComRobotDelivery {
  return {
    id: text(item.id),
    event: text(item.event),
    status: text(item.status),
    orderNo: text(item.orderNo),
    attemptCount: numberValue(item.attemptCount),
    errorMessage: text(item.errorMessage),
    createdAt: text(item.createdAt)
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
