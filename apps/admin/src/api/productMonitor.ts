import { apiClient } from './client'
import { numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type { ProductMonitorItem, ProductMonitorLog, ProductMonitorOverview } from '../types/operations'

export async function fetchProductMonitorOverview(): Promise<ProductMonitorOverview> {
  const { data } = await apiClient.get<unknown>('/api/admin/goods-monitor')
  const overview = unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)

  return {
    items: Array.isArray(overview.items) ? overview.items.map((item) => normalizeProductMonitorItem(item as Record<string, unknown>)) : [],
    logs: Array.isArray(overview.logs) ? overview.logs.map((item) => normalizeProductMonitorLog(item as Record<string, unknown>)) : []
  }
}

export async function scanProductMonitor() {
  const { data } = await apiClient.post<unknown>('/api/admin/goods-monitor/scan')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>)
}

export async function scanProductMonitorChannel(channelId: ProductMonitorItem['channelId']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/goods-monitor/channels/${channelId}/scan`)
  return data
}

function normalizeProductMonitorItem(item: Record<string, unknown>): ProductMonitorItem {
  return {
    channelId: text(item.channelId),
    goodsId: text(item.goodsId),
    goodsName: text(item.goodsName, '-'),
    supplierId: text(item.supplierId),
    supplierName: text(item.supplierName, '-'),
    supplierGoodsId: text(item.supplierGoodsId, '-'),
    primaryChannel: Boolean(item.primaryChannel),
    status: text(item.status, 'WAITING'),
    lastScanAt: text(item.lastScanAt),
    nextScanAt: text(item.nextScanAt),
    lastResult: text(item.lastResult ?? item.status, 'WAITING'),
    lastMessage: text(item.lastMessage),
    scanCount: numberValue(item.scanCount, 0),
    changeCount: numberValue(item.changeCount, 0)
  }
}

function normalizeProductMonitorLog(item: Record<string, unknown>): ProductMonitorLog {
  return {
    id: text(item.id),
    channelId: text(item.channelId),
    goodsId: text(item.goodsId),
    goodsName: text(item.goodsName, '-'),
    supplierId: text(item.supplierId),
    supplierName: text(item.supplierName, '-'),
    supplierGoodsId: text(item.supplierGoodsId, '-'),
    result: text(item.result, 'NO_CHANGE'),
    message: text(item.message),
    changes: stringArray(item.changes),
    scannedAt: text(item.scannedAt),
    nextScanAt: text(item.nextScanAt)
  }
}
