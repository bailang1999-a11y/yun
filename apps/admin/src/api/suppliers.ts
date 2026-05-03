import { apiClient } from './client'
import { numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type {
  RemoteGoods,
  RemoteGoodsSyncPayload,
  RemoteGoodsSyncResult,
  SourceClonePayload,
  SourceCloneResult,
  Supplier,
  SupplierCreatePayload
} from '../types/operations'

export async function fetchSuppliers() {
  const { data } = await apiClient.get<unknown>('/api/admin/suppliers')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeSupplier)
}

export async function createSupplier(payload: SupplierCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/suppliers', payload)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateSupplier(supplierId: Supplier['id'], payload: SupplierCreatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}`, payload)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteSupplier(supplierId: Supplier['id']) {
  const { data } = await apiClient.post(`/api/admin/suppliers/${supplierId}/delete`)

  return data
}

export async function setSupplierEnabled(supplierId: Supplier['id'], enabled: boolean) {
  const action = enabled ? 'enable' : 'disable'
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/${action}`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function refreshSupplierBalance(supplierId: Supplier['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/balance`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function testSupplierConnection(supplierId: Supplier['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/test`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function syncSupplierGoods(
  supplierId: Supplier['id'],
  payload: RemoteGoodsSyncPayload = { page: 1, limit: 20, cateId: 0, keyword: '' }
) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/sync-goods`, payload)

  return normalizeRemoteGoodsSyncResult(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchSourceConnectGoods(
  supplierId: Supplier['id'],
  payload: RemoteGoodsSyncPayload = { page: 1, limit: 20, cateId: 0, keyword: '' }
) {
  const { data } = await apiClient.post<unknown>(`/api/admin/source-connect/suppliers/${supplierId}/remote-goods`, payload)

  return normalizeRemoteGoodsSyncResult(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function cloneSourceGoods(supplierId: Supplier['id'], payload: SourceClonePayload): Promise<SourceCloneResult> {
  const { data } = await apiClient.post<unknown>(`/api/admin/source-connect/suppliers/${supplierId}/clone`, payload)
  const result = unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)

  return {
    createdCount: numberValue(result.createdCount, 0),
    skippedCount: numberValue(result.skippedCount, 0),
    failedCount: numberValue(result.failedCount, 0),
    items: Array.isArray(result.items)
      ? result.items.map((item) => {
          const record = (item || {}) as Record<string, unknown>
          return {
            supplierGoodsId: text(record.supplierGoodsId),
            supplierGoodsName: text(record.supplierGoodsName),
            status: text(record.status),
            goodsId: text(record.goodsId),
            channelId: text(record.channelId),
            message: text(record.message)
          }
        })
      : []
  }
}

function remoteChannelLabels(value: unknown, enabled: unknown, enabledLabel: string) {
  const labels = stringArray(value)
  if (labels.length > 0) return labels
  if (text(value)) return text(value).split(/[,，/]/).map((item) => item.trim()).filter(Boolean)
  return enabled === true ? [enabledLabel] : []
}

function normalizeSupplier(item: Record<string, unknown>): Supplier {
  const config = item.integrationConfig && typeof item.integrationConfig === 'object' ? (item.integrationConfig as Record<string, unknown>) : {}

  return {
    id: text(item.id),
    name: text(item.name, '未命名供应商'),
    baseUrl: text(item.baseUrl),
    appKey: text(item.appKey ?? item.appId ?? config.appId ?? config.userId),
    appSecretMasked: text(item.appSecretMasked),
    platformType: text(item.platformType ?? item.platformCode ?? config.platformType, 'CUSTOM'),
    userId: text(item.userId ?? config.userId),
    appId: text(item.appId ?? config.appId),
    apiKeyMasked: text(item.apiKeyMasked ?? config.apiKeyMasked ?? item.appSecretMasked),
    callbackUrl: text(item.callbackUrl ?? config.callbackUrl),
    timeoutSeconds: numberValue(item.timeoutSeconds ?? config.timeoutSeconds),
    balance: numberValue(item.balance),
    status: text(item.status, 'UNKNOWN'),
    remark: text(item.remark),
    lastSyncAt: text(item.lastSyncAt)
  }
}

function normalizeRemoteGoodsSyncResult(item: Record<string, unknown>): RemoteGoodsSyncResult {
  const goodsSource = item.goods ?? item.list ?? item.records ?? item.items ?? item.data
  const goods = Array.isArray(goodsSource) ? goodsSource.map((goodsItem) => normalizeRemoteGoods((goodsItem || {}) as Record<string, unknown>)) : []

  return {
    syncedAt: text(item.syncedAt ?? item.syncTime ?? item.updatedAt ?? item.createdAt),
    total: numberValue(item.total ?? item.totalCount ?? goods.length),
    goods
  }
}

function normalizeRemoteGoods(item: Record<string, unknown>): RemoteGoods {
  return {
    supplierGoodsId: text(item.supplierGoodsId ?? item.goodsId ?? item.id ?? item.productId),
    name: text(item.name ?? item.goodsName ?? item.title, '未命名商品'),
    type: text(item.type ?? item.goodsType ?? item.deliveryType, '-'),
    price: numberValue(item.price ?? item.salePrice ?? item.supplierPrice ?? item.goodsPrice ?? item.goods_price),
    faceValue: numberValue(item.faceValue ?? item.parValue ?? item.originalPrice),
    stock: numberValue(item.stock ?? item.inventory ?? item.stockNum ?? item.stock_num),
    status: text(item.status ?? item.upstreamStatus, 'UNKNOWN'),
    availablePlatforms: remoteChannelLabels(
      item.availablePlatforms ?? item.available_platforms ?? item.availableChannels ?? item.saleChannels,
      item.canBuy,
      '可售'
    ),
    forbiddenPlatforms: remoteChannelLabels(
      item.forbiddenPlatforms ?? item.forbidden_platforms ?? item.forbiddenChannels ?? item.disabledChannels,
      item.canNoBuy,
      '禁售'
    )
  }
}
