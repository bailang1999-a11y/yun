import { apiClient } from './client'
import { type ApiEnvelope, unwrapResponse } from './response'
import { type PriceTemplate } from '../utils/priceTemplates'

function normalizeTemplate(item: Record<string, unknown>): PriceTemplate {
  const groupRates = Array.isArray(item.groupRates) ? item.groupRates : []
  return {
    id: String(item.id || `tpl-${Date.now()}`),
    name: String(item.name || '价格模板'),
    adjustMode: item.adjustMode === 'fixed' ? 'fixed' : 'percent',
    referencePrice: Number(item.referencePrice) || 100,
    enabled: item.enabled !== false,
    groupRates: groupRates.map((rate) => {
      const record = (rate || {}) as Record<string, unknown>
      return {
        groupName: String(record.groupName || '默认会员'),
        color: String(record.color || '#12a594'),
        value: Number(record.value) || 100
      }
    })
  }
}

export async function fetchPriceTemplates() {
  const { data } = await apiClient.get<unknown>('/api/admin/price-templates')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeTemplate)
}

export async function savePriceTemplates(templates: PriceTemplate[]) {
  const payload = templates.map((item) => ({
    ...item,
    referencePrice: Number(item.referencePrice) || 100,
    groupRates: item.groupRates.map((rate) => ({ ...rate, value: Number(rate.value) || 100 }))
  }))
  const { data } = await apiClient.post<unknown>('/api/admin/price-templates', payload)
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeTemplate)
}
