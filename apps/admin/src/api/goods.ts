import { apiClient } from './client'
import { cleanParams, numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type {
  CardImportItem,
  Goods,
  GoodsCard,
  GoodsChannel,
  GoodsChannelCreatePayload,
  GoodsCreatePayload
} from '../types/operations'

export async function fetchGoods(query: { categoryId?: string | number; platform?: string; search?: string } = {}) {
  const { data } = await apiClient.get<unknown>('/api/admin/goods', {
    params: cleanParams(query)
  })

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGoods)
}

export async function createGoods(payload: GoodsCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/goods', normalizeGoodsPayload(payload))

  return normalizeGoods(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateGoods(goodsId: Goods['id'], payload: GoodsCreatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/goods/${goodsId}`, normalizeGoodsPayload(payload))

  return normalizeGoods(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function importGoodsCards(goodsId: Goods['id'], cards: CardImportItem[]) {
  const { data } = await apiClient.post(`/api/admin/goods/${goodsId}/cards/import`, {
    cards: cards.map((card) => `${card.cardNo},${card.password}`)
  })

  return data
}

export async function fetchGoodsCards(goodsId: Goods['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/goods/${goodsId}/cards`)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCard)
}

export async function fetchGoodsChannels(goodsId: Goods['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/goods/${goodsId}/channels`)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGoodsChannel)
}

export async function createGoodsChannel(goodsId: Goods['id'], payload: GoodsChannelCreatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/goods/${goodsId}/channels`, payload)

  return normalizeGoodsChannel(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteGoodsChannel(goodsId: Goods['id'], channelId: GoodsChannel['id']) {
  const { data } = await apiClient.post(`/api/admin/goods/${goodsId}/channels/${channelId}/delete`)

  return data
}

function normalizeGoodsPayload(payload: GoodsCreatePayload) {
  return {
    name: payload.name,
    goodsName: payload.name,
    price: payload.price,
    originalPrice: payload.originalPrice,
    status: payload.status,
    type: normalizeGoodsType(payload.deliveryType),
    cardKindId: payload.deliveryType === 'CARD' ? payload.cardKindId : undefined,
    subTitle: payload.subTitle,
    coverUrl: payload.coverUrl,
    detailImages: payload.detailImages || [],
    detailBlocks: payload.detailBlocks || [],
    benefitDurations: payload.benefitDurations || [],
    integrations: payload.integrations || [],
    pollingEnabled: payload.pollingEnabled,
    monitoringEnabled: payload.monitoringEnabled,
    stock: payload.stock,
    maxBuy: payload.maxBuy,
    requireRechargeAccount: payload.requireRechargeAccount,
    accountTypes: payload.accountTypes || [],
    priceTemplateId: payload.priceTemplateId,
    priceMode: payload.priceMode,
    priceCoefficient: payload.priceCoefficient,
    priceFixedAdd: payload.priceFixedAdd,
    description: payload.description,
    categoryId: payload.categoryId,
    platform: payload.platform || 'GENERAL',
    availablePlatforms: payload.availablePlatforms?.length ? payload.availablePlatforms : ['private'],
    forbiddenPlatforms: payload.forbiddenPlatforms || []
  }
}

function normalizeGoodsType(value: string) {
  if (value === 'AUTO' || value === 'DIRECT') return 'DIRECT'
  if (value === 'MANUAL') return 'MANUAL'
  return 'CARD'
}

function normalizeGoods(item: Record<string, unknown>): Goods {
  const cardKind = item.cardKind && typeof item.cardKind === 'object' ? item.cardKind as Record<string, unknown> : {}
  const cardKindStockSource = item.cardKindStock ?? item.cardKindUnusedCount ?? item.unusedCount ?? cardKind.stock ?? cardKind.unusedCount

  return {
    id: text(item.id),
    categoryId: text(item.categoryId),
    categoryName: text(item.categoryName),
    name: text(item.name ?? item.goodsName, '未命名商品'),
    price: numberValue(item.price ?? item.salePrice),
    originalPrice: numberValue(item.originalPrice),
    status: text(item.status, 'UNKNOWN'),
    stock: numberValue(item.stock, 0),
    deliveryType: text(item.deliveryType ?? item.type ?? item.goodsType, 'CARD'),
    cardKindId: text(item.cardKindId ?? item.kindId ?? cardKind.id),
    cardKindName: text(item.cardKindName ?? item.kindName ?? cardKind.name),
    cardKindStock: cardKindStockSource === undefined || cardKindStockSource === null ? undefined : numberValue(cardKindStockSource),
    platform: text(item.platform),
    subTitle: text(item.subTitle),
    coverUrl: text(item.coverUrl),
    detailImages: stringArray(item.detailImages),
    detailBlocks: normalizeDetailBlocks(item.detailBlocks),
    benefitDurations: stringArray(item.benefitDurations),
    integrations: normalizeIntegrations(item.integrations),
    pollingEnabled: Boolean(item.pollingEnabled),
    monitoringEnabled: Boolean(item.monitoringEnabled),
    maxBuy: numberValue(item.maxBuy, 1),
    requireRechargeAccount: Boolean(item.requireRechargeAccount),
    accountTypes: stringArray(item.accountTypes),
    priceTemplateId: text(item.priceTemplateId),
    priceMode: text(item.priceMode, 'FIXED'),
    priceCoefficient: numberValue(item.priceCoefficient, 1),
    priceFixedAdd: numberValue(item.priceFixedAdd, 0),
    availablePlatforms: stringArray(item.availablePlatforms ?? item.available_platforms),
    forbiddenPlatforms: stringArray(item.forbiddenPlatforms ?? item.forbidden_platforms),
    description: text(item.description),
    createdAt: text(item.createdAt)
  }
}

function normalizeIntegrations(value: unknown) {
  if (!Array.isArray(value)) return []
  return value.map((item) => {
    const record = (item || {}) as Record<string, unknown>
    return {
      id: text(record.id),
      supplierId: text(record.supplierId),
      supplierName: text(record.supplierName),
      platformCode: text(record.platformCode),
      supplierGoodsId: text(record.supplierGoodsId),
      supplierGoodsName: text(record.supplierGoodsName),
      supplierPrice: numberValue(record.supplierPrice),
      upstreamStatus: text(record.upstreamStatus, '正常'),
      upstreamStock: numberValue(record.upstreamStock),
      upstreamTitle: text(record.upstreamTitle),
      lastSyncAt: text(record.lastSyncAt),
      enabled: record.enabled === undefined ? true : Boolean(record.enabled)
    }
  })
}

function normalizeDetailBlocks(value: unknown) {
  if (!Array.isArray(value)) return []
  return value
    .map((item) => {
      const record = (item || {}) as Record<string, unknown>
      return {
        type: text(record.type, text(record.imageUrl) ? 'image' : 'text') as 'image' | 'text',
        imageUrl: text(record.imageUrl),
        text: text(record.text)
      }
    })
    .filter((item) => item.imageUrl || item.text)
}

function normalizeCard(item: Record<string, unknown>): GoodsCard {
  return {
    id: text(item.id),
    goodsId: text(item.goodsId),
    cardKindId: text(item.cardKindId ?? item.kindId),
    cardNo: text(item.cardNo ?? item.card_no),
    password: text(item.password ?? item.secret ?? item.preview),
    content: text(item.content),
    preview: text(item.preview ?? item.secret ?? item.password ?? item.content),
    status: text(item.status),
    orderNo: text(item.orderNo),
    usedAt: text(item.usedAt ?? item.deliveredAt),
    createdAt: text(item.createdAt ?? item.importedAt)
  }
}

function normalizeGoodsChannel(item: Record<string, unknown>): GoodsChannel {
  return {
    id: text(item.id),
    goodsId: text(item.goodsId),
    supplierId: text(item.supplierId),
    supplierName: text(item.supplierName, '未知供应商'),
    supplierGoodsId: text(item.supplierGoodsId),
    priority: numberValue(item.priority),
    timeoutSeconds: numberValue(item.timeoutSeconds),
    status: text(item.status, 'UNKNOWN'),
    createdAt: text(item.createdAt)
  }
}
