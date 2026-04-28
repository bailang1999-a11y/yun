export interface PriceTemplate {
  id: string
  name: string
  groupName: string
  markupPercent: number
  enabled: boolean
}

const STORAGE_KEY = 'xiyiyun_price_templates'

export const defaultPriceTemplates: PriceTemplate[] = [
  { id: 'retail-default', name: '默认零售模板', groupName: '普通会员', markupPercent: 0, enabled: true },
  { id: 'member-standard', name: '会员标准加价', groupName: '标准会员', markupPercent: 8, enabled: true },
  { id: 'vip-channel', name: 'VIP 渠道加价', groupName: 'VIP 会员', markupPercent: 5, enabled: true },
  { id: 'manual-service', name: '人工服务加价', groupName: '代充会员', markupPercent: 12, enabled: true }
]

export function loadPriceTemplates() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return [...defaultPriceTemplates]
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) && parsed.length ? parsed : [...defaultPriceTemplates]
  } catch {
    return [...defaultPriceTemplates]
  }
}

export function savePriceTemplates(templates: PriceTemplate[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(templates))
}
