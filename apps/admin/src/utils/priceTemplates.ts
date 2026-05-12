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
