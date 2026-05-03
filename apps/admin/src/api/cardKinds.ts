import { apiClient } from './client'
import { numberValue, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type { CardKind, CardKindCreatePayload, CardKindImportResult, GoodsCard } from '../types/operations'

export async function fetchCardKinds() {
  const { data } = await apiClient.get<unknown>('/api/admin/card-kinds')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCardKind)
}

export async function createCardKind(payload: CardKindCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/card-kinds', payload)

  return normalizeCardKind(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function importCardKindCards(cardKindId: CardKind['id'], cards: string[]) {
  const { data } = await apiClient.post<unknown>(`/api/admin/card-kinds/${cardKindId}/cards/import`, { cards })

  return normalizeCardKindImportResult(
    unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)
  )
}

export async function fetchCardKindCards(cardKindId: CardKind['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/card-kinds/${cardKindId}/cards`)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCard)
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

function normalizeCardKind(item: Record<string, unknown>): CardKind {
  return {
    id: text(item.id),
    name: text(item.name ?? item.kindName ?? item.cardKindName, '未命名卡种'),
    type: text(item.type ?? item.kindType ?? item.cardType, 'ONCE'),
    cost: numberValue(item.cost ?? item.costPrice ?? item.price),
    stock: numberValue(item.stock ?? item.inventory ?? item.availableCount ?? item.unusedCount, 0),
    unusedCount: numberValue(item.unusedCount ?? item.availableCount ?? item.stock ?? item.inventory, 0),
    usedCount: numberValue(item.usedCount, 0),
    totalCount: numberValue(item.totalCount ?? item.total ?? item.count, 0),
    createdAt: text(item.createdAt)
  }
}

function normalizeCardKindImportResult(item: Record<string, unknown>): CardKindImportResult {
  return {
    importTotal: numberValue(item.importTotal ?? item.total),
    successCount: numberValue(item.successCount ?? item.success),
    duplicateCount: numberValue(item.duplicateCount ?? item.duplicates),
    failedLines: Array.isArray(item.failedLines) ? item.failedLines.map((line) => numberValue(line)).filter(Boolean) : []
  }
}
