import { apiClient } from './client'
import { unwrapValue } from './response'

export type OutboundProtocolId = 'KASUSHOU' | 'KAKAYUN' | 'FULU' | 'FENGZHUSHOU' | 'CHENGQUAN' | 'FANCHEN' | 'JINGZHAO'

export interface OutboundProtocolSettings {
  enabled: boolean
  baseUrl: string
  requestLimitPerMinute: number
  timeoutSeconds: number
  protocols: Record<OutboundProtocolId, boolean>
  exposureMode: 'ALL' | 'CATEGORY' | 'SELECTED'
  categoryIds: number[]
  goodsIds: number[]
  deliveryTypes: Array<'CARD' | 'DIRECT'>
  pricePolicy: 'MEMBER_GROUP' | 'RETAIL' | 'COST_PLUS'
  priceAdjustment: number
  includeDisabledGoods: boolean
}

export async function fetchOutboundProtocolSettings() {
  const { data } = await apiClient.get('/api/admin/outbound-protocols')
  return unwrapValue<OutboundProtocolSettings>(data)
}

export async function saveOutboundProtocolSettings(payload: OutboundProtocolSettings) {
  const { data } = await apiClient.post('/api/admin/outbound-protocols', payload)
  return unwrapValue<OutboundProtocolSettings>(data)
}
