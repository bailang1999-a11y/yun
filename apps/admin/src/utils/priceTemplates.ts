export interface PriceTemplate {
  id: string
  name: string
  adjustMode: 'fixed' | 'percent'
  referencePrice: number
  groupRates: PriceGroupRate[]
  enabled: boolean
}

export interface PriceGroupRate {
  groupName: string
  color: string
  value: number
}

const STORAGE_KEY = 'xiyiyun_price_templates'

export const defaultPriceTemplates: PriceTemplate[] = [
  {
    id: 'retail-default',
    name: '默认加价模板',
    adjustMode: 'percent',
    referencePrice: 100,
    enabled: true,
    groupRates: [
      { groupName: '零售会员', color: '#ffb300', value: 110 },
      { groupName: '私密会员', color: '#24364d', value: 108 },
      { groupName: '高级会员', color: '#12a594', value: 106 },
      { groupName: '合作伙伴', color: '#0d9488', value: 105 },
      { groupName: '店铺会员', color: '#009688', value: 103 }
    ]
  }
]

export function loadPriceTemplates() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return [...defaultPriceTemplates]
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed) || !parsed.length) return [...defaultPriceTemplates]
    return parsed.map((item) => ({
      id: item.id,
      name: item.name,
      adjustMode: item.adjustMode || 'percent',
      referencePrice: Number(item.referencePrice) || 100,
      enabled: item.enabled !== false,
      groupRates: Array.isArray(item.groupRates) && item.groupRates.length
        ? item.groupRates
        : [{ groupName: item.groupName || '默认会员', color: '#12a594', value: 100 + (Number(item.markupPercent) || 0) }]
    }))
  } catch {
    return [...defaultPriceTemplates]
  }
}

export function savePriceTemplates(templates: PriceTemplate[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(templates))
}
