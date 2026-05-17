import { apiClient } from './client'
import { numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type {
  RemoteCategory,
  RemoteGoods,
  RemoteGoodsSyncPayload,
  RemoteGoodsSyncResult,
  SourceClonePayload,
  SourceCloneResult,
  Supplier,
  SupplierCreatePayload
} from '../types/operations'

const SOURCE_CONNECT_SYNC_TIMEOUT_MS = 90_000
const SOURCE_CONNECT_CLONE_TIMEOUT_MS = 180_000

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
  const { data } = await apiClient.post<unknown>(
    `/api/admin/source-connect/suppliers/${supplierId}/remote-goods`,
    payload,
    { timeout: SOURCE_CONNECT_SYNC_TIMEOUT_MS }
  )

  return normalizeRemoteGoodsSyncResult(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function cloneSourceGoods(supplierId: Supplier['id'], payload: SourceClonePayload): Promise<SourceCloneResult> {
  const { data } = await apiClient.post<unknown>(
    `/api/admin/source-connect/suppliers/${supplierId}/clone`,
    payload,
    { timeout: SOURCE_CONNECT_CLONE_TIMEOUT_MS }
  )
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
  const categories = normalizeRemoteCategories(item.categories ?? item.cates ?? item.categoryList)
  const categoryNames = categoryNameMap(categories)
  const goods = Array.isArray(goodsSource)
    ? goodsSource.map((goodsItem) => normalizeRemoteGoods((goodsItem || {}) as Record<string, unknown>, categoryNames))
    : []

  return {
    syncedAt: text(item.syncedAt ?? item.syncTime ?? item.updatedAt ?? item.createdAt),
    total: numberValue(item.total ?? item.totalCount ?? goods.length),
    goods,
    categories
  }
}

function normalizeRemoteGoods(item: Record<string, unknown>, categoryNames: Map<string, string> = new Map()): RemoteGoods {
  const raw = item.raw && typeof item.raw === 'object' ? (item.raw as Record<string, unknown>) : {}
  const type = text(item.type ?? item.goodsType ?? item.deliveryType ?? raw.goods_type, '-')
  const categoryId = text(item.categoryId ?? item.cateId ?? item.cate_id ?? item.category_id ?? raw.cate_id ?? raw.category_id)

  return {
    supplierGoodsId: text(item.supplierGoodsId ?? item.goodsId ?? item.id ?? item.productId),
    name: text(item.name ?? item.goodsName ?? item.title, '未命名商品'),
    type,
    typeLabel: remoteGoodsTypeLabel(type),
    categoryId,
    categoryName: categoryNames.get(categoryId) || text(item.categoryName ?? item.cateName ?? raw.cate_name),
    price: numberValue(item.price ?? item.salePrice ?? item.supplierPrice ?? item.goodsPrice ?? item.goods_price),
    faceValue: numberValue(item.faceValue ?? item.parValue ?? item.originalPrice),
    stock: numberValue(item.stock ?? item.inventory ?? item.stockNum ?? item.stock_num),
    status: text(item.status ?? item.upstreamStatus, 'UNKNOWN'),
    connected: item.connected === true,
    localGoodsId: text(item.localGoodsId ?? item.goodsLocalId),
    localGoodsName: text(item.localGoodsName ?? item.goodsLocalName),
    channelId: text(item.channelId ?? item.goodsChannelId),
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

function normalizeRemoteCategories(value: unknown, level = 0): RemoteCategory[] {
  if (!Array.isArray(value)) return []
  return value.map((item) => {
    const record = (item || {}) as Record<string, unknown>
    const children = normalizeRemoteCategories(record.children, level + 1)
    return {
      id: text(record.id ?? record.categoryId ?? record.cateId),
      name: text(record.name ?? record.categoryName ?? record.cateName, '未命名分类'),
      pid: text(record.pid ?? record.parentId),
      level,
      children
    }
  })
}

function categoryNameMap(categories: RemoteCategory[]) {
  const map = new Map<string, string>()
  const walk = (items: RemoteCategory[]) => {
    items.forEach((item) => {
      map.set(String(item.id), item.name)
      walk(item.children)
    })
  }
  walk(categories)
  return map
}

function remoteGoodsTypeLabel(type: string) {
  if (type === '1') return '卡密'
  if (type === '2') return '直充'
  return type || '-'
}
