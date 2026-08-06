import { apiClient } from './client'
import { cleanParams, numberValue, text } from './normalize'
import { type ApiEnvelope, type PageResult, unwrapPage, unwrapValue } from './response'
import type {
  GoodsChannel,
  Order,
  OrderQuery,
  OrderRefreshResult,
  OrderSummary,
  SupplierPriceTrend,
  SupplierPriceTrendPoint
} from '../types/operations'

const ORDER_UPSTREAM_OPERATION_TIMEOUT_MS = 90_000
export type OrderPageQuery = OrderQuery & { page?: number; pageSize?: number }

export async function fetchOrders(query: OrderPageQuery = {}) {
  return (await fetchOrdersPage(query)).items
}

export async function fetchOrdersPage(query: OrderPageQuery = {}): Promise<PageResult<Order>> {
  const { data } = await apiClient.get<unknown>('/api/admin/orders', {
    params: cleanParams(query)
  })
  const page = unwrapPage<Record<string, unknown>>(data)
  return {
    ...page,
    items: page.items.map(normalizeOrder)
  }
}

export async function exportOrdersExcel(query: OrderQuery = {}) {
  const response = await apiClient.get<Blob>('/api/admin/orders/export', {
    params: cleanParams(query),
    responseType: 'blob'
  })

  return response.data
}

export async function fetchOrdersSummary(query: OrderQuery = {}): Promise<OrderSummary> {
  const { data } = await apiClient.get<unknown>('/api/admin/orders/summary', {
    params: cleanParams(query)
  })
  const value = unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)
  return {
    total: numberValue(value.total),
    externalAmount: numberValue(value.externalAmount),
    missingExternalAmountCount: numberValue(value.missingExternalAmountCount),
    activeCount: numberValue(value.activeCount),
    deliveredCount: numberValue(value.deliveredCount),
    failedCount: numberValue(value.failedCount)
  }
}

export async function fetchOrderDetail(orderNo: string) {
  const { data } = await apiClient.get<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function refreshUnfinishedOrders(): Promise<OrderRefreshResult> {
  const { data } = await apiClient.post<unknown>(
    '/api/admin/orders/refresh-unfinished',
    undefined,
    { timeout: ORDER_UPSTREAM_OPERATION_TIMEOUT_MS }
  )
  const value = unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)

  return {
    total: numberValue(value.total),
    refreshed: numberValue(value.refreshed),
    changed: numberValue(value.changed),
    failed: numberValue(value.failed),
    firstError: text(value.firstError) || undefined
  }
}

export async function refreshOrderCallback(orderNo: string) {
  const { data } = await apiClient.post<unknown>(
    `/api/admin/orders/${encodeURIComponent(orderNo)}/refresh-callback`,
    undefined,
    { timeout: ORDER_UPSTREAM_OPERATION_TIMEOUT_MS }
  )

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function completeManualOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/complete-manual`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function retryOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(
    `/api/admin/orders/${encodeURIComponent(orderNo)}/retry`,
    undefined,
    { timeout: ORDER_UPSTREAM_OPERATION_TIMEOUT_MS }
  )

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function retryOrderWithChannel(orderNo: string, channelId: GoodsChannel['id']) {
  const { data } = await apiClient.post<unknown>(
    `/api/admin/orders/${encodeURIComponent(orderNo)}/retry-channel/${encodeURIComponent(String(channelId))}`,
    undefined,
    { timeout: ORDER_UPSTREAM_OPERATION_TIMEOUT_MS }
  )

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function refundOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/refund`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function markOrderSuccess(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/manual-success`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function markOrderFailed(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/manual-failed`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/delete`)

  return unwrapValue<string>(data as ApiEnvelope<string>)
}

function normalizeOrder(item: Record<string, unknown>): Order {
  const deliveryItems = Array.isArray(item.deliveryItems) ? item.deliveryItems.map((value) => text(value)) : []
  const externalMaxAmount = item.externalMaxAmount
  const normalizedExternalMaxAmount = externalMaxAmount === undefined
    || externalMaxAmount === null
    || (typeof externalMaxAmount === 'string' && !externalMaxAmount.trim())
    || !Number.isFinite(Number(externalMaxAmount))
    ? undefined
    : numberValue(externalMaxAmount)
  const expectedAmount = item.expectedAmount
  const normalizedExpectedAmount = expectedAmount === undefined
    || expectedAmount === null
    || (typeof expectedAmount === 'string' && !expectedAmount.trim())
    || !Number.isFinite(Number(expectedAmount))
    ? undefined
    : numberValue(expectedAmount)
  const averageRechargeDurationSeconds = item.averageRechargeDurationSeconds
  const normalizedAverageRechargeDurationSeconds = averageRechargeDurationSeconds === undefined
    || averageRechargeDurationSeconds === null
    || (typeof averageRechargeDurationSeconds === 'string' && !averageRechargeDurationSeconds.trim())
    || !Number.isFinite(Number(averageRechargeDurationSeconds))
    ? undefined
    : numberValue(averageRechargeDurationSeconds)
  const todaySuccessRatePercentage = item.todaySuccessRatePercentage
  const normalizedTodaySuccessRatePercentage = todaySuccessRatePercentage === undefined
    || todaySuccessRatePercentage === null
    || (typeof todaySuccessRatePercentage === 'string' && !todaySuccessRatePercentage.trim())
    || !Number.isFinite(Number(todaySuccessRatePercentage))
    ? undefined
    : numberValue(todaySuccessRatePercentage)
  const supplierPriceTrend = normalizeSupplierPriceTrend(item.supplierPriceTrend)
  const channelAttempts = Array.isArray(item.channelAttempts)
    ? item.channelAttempts.map((attempt) => {
        const record = typeof attempt === 'object' && attempt !== null ? (attempt as Record<string, unknown>) : {}
        return {
          channelId: text(record.channelId),
          supplierId: text(record.supplierId),
          supplierName: text(record.supplierName, '-'),
          supplierGoodsId: text(record.supplierGoodsId, '-'),
          supplierGoodsName: text(record.supplierGoodsName ?? record.upstreamTitle ?? record.goodsName),
          supplierPrice: numberValue(record.supplierPrice ?? record.upstreamPrice ?? record.price),
          upstreamStatus: text(record.upstreamStatus),
          callbackStatus: text(record.callbackStatus),
          callbackMessage: text(record.callbackMessage ?? record.errorMessage),
          rawResponse: text(record.rawResponse ?? record.responseBody),
          priority: numberValue(record.priority),
          status: text(record.status, 'UNKNOWN'),
          message: text(record.message),
          attemptedAt: text(record.attemptedAt)
        }
      })
    : []

  return {
    orderNo: text(item.orderNo),
    userId: text(item.userId),
    buyerNickname: text(item.buyerNickname ?? item.userNickname ?? item.nickname),
    buyerAccount: text(item.buyerAccount),
    goodsId: text(item.goodsId),
    goodsName: text(item.goodsName, '未知商品'),
    amount: numberValue(item.amount ?? item.payAmount ?? item.totalAmount),
    externalMaxAmount: normalizedExternalMaxAmount,
    expectedAmount: normalizedExpectedAmount,
    rejectionCode: text(item.rejectionCode),
    rejectionReason: text(item.rejectionReason ?? (text(item.status) === 'REJECTED' ? item.deliveryMessage : '')),
    rejectedAt: text(item.rejectedAt),
    averageRechargeDurationSeconds: normalizedAverageRechargeDurationSeconds,
    todaySuccessRatePercentage: normalizedTodaySuccessRatePercentage,
    supplierPriceTrend,
    unitPrice: numberValue(item.unitPrice),
    quantity: numberValue(item.quantity, 1),
    status: text(item.status, 'UNKNOWN'),
    paymentNo: text(item.paymentNo),
    payMethod: text(item.payMethod),
    orderSource: text(item.orderSource ?? item.source ?? item.terminal ?? item.clientType),
    deliveryType: text(item.deliveryType ?? item.goodsType, '-'),
    platform: text(item.platform),
    rechargeAccount: text(item.rechargeAccount),
    orderIp: text(item.orderIp ?? item.ip ?? item.clientIp ?? item.createdIp),
    orderIpLocation: text(item.orderIpLocation ?? item.ipLocation ?? item.clientIpLocation ?? item.createdIpLocation),
    buyerContact: text(item.buyerContact ?? item.contact ?? item.mobile ?? item.phone ?? item.email),
    buyerMobile: text(item.buyerMobile ?? item.mobile ?? item.phone),
    buyerEmail: text(item.buyerEmail ?? item.email),
    supplierName: text(item.supplierName),
    supplierGoodsId: text(item.supplierGoodsId),
    supplierGoodsName: text(item.supplierGoodsName),
    buyerRemark: text(item.buyerRemark),
    requestId: text(item.requestId),
    upstreamOrderNo: text(item.upstreamOrderNo),
    deliveryItems,
    channelAttempts,
    deliveryMessage: text(item.deliveryMessage),
    createdAt: text(item.createdAt),
    paidAt: text(item.paidAt),
    deliveredAt: text(item.deliveredAt)
  }
}

function normalizeSupplierPriceTrend(value: unknown): SupplierPriceTrend | undefined {
  if (!value || typeof value !== 'object') return undefined
  const item = value as Record<string, unknown>
  const points = Array.isArray(item.points)
    ? item.points.flatMap((point) => {
        if (!point || typeof point !== 'object') return []
        const record = point as Record<string, unknown>
        const direction = text(record.direction).toUpperCase()
        const normalizedDirection: SupplierPriceTrendPoint['direction'] = direction === 'UP' || direction === 'DOWN'
          ? direction
          : 'INITIAL'
        return [{
          unitPrice: numberValue(record.unitPrice),
          changeAmount: numberValue(record.changeAmount),
          direction: normalizedDirection,
          observedAt: text(record.observedAt)
        }]
      })
    : []
  if (!points.length) return undefined
  const latestDirection = text(item.latestDirection).toUpperCase()
  return {
    channelId: text(item.channelId),
    supplierId: text(item.supplierId),
    supplierName: text(item.supplierName, '未知供应商'),
    supplierGoodsId: text(item.supplierGoodsId),
    latestUnitPrice: numberValue(item.latestUnitPrice),
    latestDirection: latestDirection === 'UP' || latestDirection === 'DOWN' ? latestDirection : 'INITIAL',
    points
  }
}
