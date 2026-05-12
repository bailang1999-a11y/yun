export type GoodsType = 'CARD' | 'DIRECT' | 'MANUAL'

export interface H5Category {
  id: string
  name: string
  parentId?: string
  level?: number
}

export interface GoodsCard {
  id: string
  name: string
  faceValue: string
  price: number
  originalPrice?: number
  type: GoodsType
  stock?: number
  stockLabel: string
  maxBuy: number
  soldOut: boolean
  canBuy: boolean
  category: string
  categoryId?: string
  cover: string
  coverUrl?: string
  requireRechargeAccount: boolean
  accountTypes: string[]
  benefitDurations?: string[]
  benefitType?: string
  benefitBrand?: string
  tags?: string[]
  priceLimited?: boolean
  priceLimitText?: string
  availablePlatforms?: string[]
  forbiddenPlatforms?: string[]
}

export interface RechargeField {
  id: string
  code: string
  label: string
  placeholder: string
  helpText: string
  inputType: string
  required: boolean
  sort: number
  enabled: boolean
}

export interface PaymentChannel {
  id: string
  code: string
  name: string
  type: string
  terminals: string[]
  status: string
  sort: number
  remark?: string
}

export interface H5SystemSetting {
  registrationEnabled: boolean
  registrationType: string
  defaultUserGroupId?: string
}

export interface H5Order {
  orderNo: string
  userId?: string
  buyerAccount?: string
  goodsId?: string
  goodsName: string
  goodsType?: GoodsType
  quantity: number
  totalAmount: number
  status: string
  paymentNo?: string
  payMethod?: string
  deliveryStatus?: string
  rechargeAccount?: string
  createdAt?: string
  paidAt?: string
  deliveredAt?: string
}

export interface CreateOrderPayload {
  goodsId: string
  quantity: number
  rechargeAccount?: string
  buyerRemark?: string
  requestId: string
  terminal?: 'h5' | 'web' | 'api'
}

export interface DeliveryCard {
  cardNo: string
  cardPassword?: string
  password?: string
  secret?: string
  instruction?: string
}

export interface OrderDelivery {
  orderNo: string
  status?: string
  deliveryStatus?: string
  instruction?: string
  viewedBefore?: boolean
  cards: DeliveryCard[]
}

export interface UserProfile {
  id: string
  mobile?: string
  email?: string
  nickname: string
  groupId?: string
  groupName?: string
  balance?: number
  status?: string
}

export interface AuthSession {
  token: string
  profile: UserProfile
}

export interface AuthPayload {
  account: string
  password?: string
  confirmPassword?: string
  code?: string
  terminal: 'h5' | 'web'
  sliderToken?: string
  captchaTicket?: string
  captchaRandstr?: string
  mode: 'login' | 'register' | 'forgot'
}

export interface CaptchaChallenge {
  enabled: boolean
  provider: string
  appId: string
  scene?: string
}
