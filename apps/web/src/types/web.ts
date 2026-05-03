export type GoodsType = 'CARD' | 'DIRECT' | 'MANUAL'

export interface CategoryItem {
  id: string
  name: string
  parentId?: string
  level?: number
}

export interface GoodsItem {
  id: string
  name: string
  faceValue: string
  price: number
  originalPrice?: number
  type: GoodsType
  stockLabel: string
  category: string
  categoryId?: string
  cover: string
  requireRechargeAccount: boolean
  accountTypes: string[]
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

export interface SiteSettings {
  siteName: string
  logoUrl?: string
  companyName?: string
  icpRecordNo?: string
  policeRecordNo?: string
  disclaimer?: string
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

export interface OrderItem {
  orderNo: string
  userId?: string
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

export interface CreateOrderPayload {
  goodsId: string
  quantity: number
  rechargeAccount?: string
  buyerRemark?: string
  requestId: string
}

export interface ApiCredential {
  appKey: string
  appSecretMasked: string
  status: string
  ipWhitelist: string[]
  dailyLimit: number
  lastUsedAt?: string
}

export interface PasswordChangePayload {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export interface RechargeRequestPayload {
  amount: number
  payMethod: string
  remark?: string
}

export interface RechargeRequestResult {
  requestNo: string
  amount: number
  payMethod: string
  status: string
  createdAt: string
}
