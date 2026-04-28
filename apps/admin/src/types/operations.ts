export interface Goods {
  id: number | string
  categoryId?: number | string
  categoryName?: string
  name: string
  price: number | string
  originalPrice?: number | string
  status?: string
  stock?: number
  deliveryType?: string
  platform?: string
  subTitle?: string
  coverUrl?: string
  detailImages?: string[]
  maxBuy?: number
  requireRechargeAccount?: boolean
  accountTypes?: string[]
  priceMode?: string
  priceCoefficient?: number
  priceFixedAdd?: number
  availablePlatforms?: string[]
  forbiddenPlatforms?: string[]
  description?: string
  createdAt?: string
}

export interface GoodsCreatePayload {
  categoryId?: number | string
  name: string
  price: number
  originalPrice?: number
  status: string
  deliveryType: string
  platform?: string
  subTitle?: string
  coverUrl?: string
  detailImages?: string[]
  stock?: number
  maxBuy?: number
  requireRechargeAccount?: boolean
  accountTypes?: string[]
  priceMode?: string
  priceCoefficient?: number
  priceFixedAdd?: number
  availablePlatforms?: string[]
  forbiddenPlatforms?: string[]
  description?: string
}

export interface Category {
  id: number | string
  name: string
  parentId?: number | string
  sort?: number
  enabled?: boolean
  level?: number
  children?: Category[]
}

export interface CategoryCreatePayload {
  parentId?: number | string
  name: string
  sort?: number
  enabled?: boolean
}

export interface Supplier {
  id: number | string
  name: string
  baseUrl: string
  appKey: string
  appSecretMasked: string
  balance: number | string
  status: string
  remark?: string
  lastSyncAt?: string
}

export interface SupplierCreatePayload {
  name: string
  baseUrl: string
  appKey: string
  appSecret: string
  balance?: number
  status?: string
  remark?: string
}

export interface GoodsChannel {
  id: number | string
  goodsId: number | string
  supplierId: number | string
  supplierName: string
  supplierGoodsId: string
  priority: number
  timeoutSeconds: number
  status: string
  createdAt?: string
}

export interface GoodsChannelCreatePayload {
  supplierId?: number | string
  supplierGoodsId: string
  priority: number
  timeoutSeconds: number
  status: string
}

export interface CardImportItem {
  cardNo: string
  password: string
}

export interface GoodsCard {
  id: number | string
  cardNo?: string
  password?: string
  status?: string
  usedAt?: string
  createdAt?: string
}

export interface Order {
  id?: number | string
  orderNo: string
  userId?: number | string
  buyerAccount?: string
  goodsId?: number | string
  goodsName?: string
  goods?: string | { name?: string }
  amount: number | string
  unitPrice?: number | string
  quantity?: number
  status: string
  paymentNo?: string
  payMethod?: string
  deliveryType?: string
  platform?: string
  rechargeAccount?: string
  buyerRemark?: string
  requestId?: string
  deliveryItems?: string[]
  channelAttempts?: ChannelAttempt[]
  deliveryMessage?: string
  createdAt?: string
  paidAt?: string
  deliveredAt?: string
}

export interface OrderQuery {
  search?: string
  status?: string
  goodsType?: string
}

export interface ChannelAttempt {
  channelId?: number | string
  supplierId?: number | string
  supplierName: string
  supplierGoodsId: string
  priority: number
  status: string
  message: string
  attemptedAt?: string
}

export type RuleType = 'CATEGORY' | 'PLATFORM'
export type RulePermission = 'ALLOW' | 'DENY' | 'NONE'

export interface GroupRule {
  groupId: number | string
  ruleType: RuleType
  targetId?: number | string
  targetCode?: string
  targetName?: string
  permission: RulePermission
}

export interface UserGroup {
  id: number | string
  name: string
  description?: string
  defaultGroup?: boolean
  userCount?: number
  status?: string
  rules: GroupRule[]
}

export interface UserAccount {
  id: number | string
  avatar?: string
  mobile?: string
  email?: string
  nickname?: string
  groupId?: number | string
  groupName?: string
  balance?: number | string
  status?: string
  createdAt?: string
}

export interface GroupRulePatchPayload {
  ruleType: RuleType
  rules: Array<{
    targetId?: number | string
    targetCode?: string
    permission: RulePermission
  }>
}

export interface AdminProfile {
  id: number | string
  username: string
  nickname: string
  permissions: string[]
}

export interface AdminAuthSession {
  token: string
  profile: AdminProfile
}

export interface SystemSetting {
  siteName: string
  logoUrl?: string
  customerService?: string
  paymentMode: string
  autoRefundEnabled: boolean
  smsProvider: string
  smsEnabled: boolean
  upstreamSyncSeconds: number
  autoShelfEnabled: boolean
  autoPriceEnabled: boolean
  notificationReceivers: Record<string, string>
}

export interface PaymentRecord {
  paymentNo: string
  orderNo: string
  userId?: number | string
  method: string
  amount: number | string
  status: string
  channelTradeNo?: string
  createdAt?: string
  paidAt?: string
}

export interface RefundRecord {
  refundNo: string
  orderNo: string
  paymentNo?: string
  userId?: number | string
  amount: number | string
  status: string
  reason?: string
  createdAt?: string
  refundedAt?: string
}

export interface SmsLog {
  id: number | string
  orderNo?: string
  mobile?: string
  templateType?: string
  content?: string
  status?: string
  errorMessage?: string
  createdAt?: string
}

export interface OperationLog {
  id: number | string
  operator?: string
  action?: string
  resourceType?: string
  resourceId?: string
  remark?: string
  createdAt?: string
}

export interface MemberApiCredential {
  id: number | string
  userId: number | string
  appKey: string
  appSecret: string
  status: string
  ipWhitelist: string[]
  dailyLimit: number
  createdAt?: string
  lastUsedAt?: string
}

export interface OpenApiLog {
  id: number | string
  userId?: number | string
  appKey?: string
  path?: string
  status?: string
  message?: string
  createdAt?: string
}
