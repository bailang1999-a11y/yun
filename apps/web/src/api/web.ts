import { apiClient } from './client'
import type {
  ApiCredential,
  AuthSession,
  CategoryItem,
  CreateOrderPayload,
  DeliveryCard,
  GoodsItem,
  GoodsType,
  OrderDelivery,
  OrderItem,
  PasswordChangePayload,
  RechargeField,
  RechargeRequestPayload,
  RechargeRequestResult,
  SiteSettings,
  UserProfile
} from '../types/web'

type AnyRecord = Record<string, unknown>

function isRecord(value: unknown): value is AnyRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function unwrap(data: unknown): unknown {
  if (!isRecord(data)) return data
  if ('data' in data) return unwrap(data.data)
  if ('result' in data) return unwrap(data.result)
  return data
}

function toArray(data: unknown): unknown[] {
  const value = unwrap(data)
  if (Array.isArray(value)) return value
  if (isRecord(value)) {
    const nested = value.records ?? value.list ?? value.items ?? value.rows ?? value.content
    if (Array.isArray(nested)) return nested
  }
  return []
}

function text(value: unknown, fallback = '') {
  return value === null || value === undefined ? fallback : String(value)
}

function numberValue(value: unknown, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function stringArray(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item)).filter(Boolean) : []
}

function mapGoodsType(value: unknown): GoodsType {
  const raw = text(value).toUpperCase()
  if (raw === 'DIRECT_RECHARGE') return 'DIRECT'
  if (raw === 'AGENT_RECHARGE') return 'MANUAL'
  if (raw === 'DIRECT' || raw === 'MANUAL' || raw === 'CARD') return raw
  return 'CARD'
}

function normalizeCategory(item: unknown): CategoryItem {
  const record = isRecord(item) ? item : {}
  const rawParentId = text(record.parentId ?? record.parent_id ?? record.pid)
  return {
    id: text(record.id ?? record.categoryId ?? record.code ?? record.name),
    name: text(record.name ?? record.categoryName ?? record.title, '未命名分类'),
    parentId: rawParentId && rawParentId !== '0' ? rawParentId : undefined,
    level: record.level === undefined ? undefined : numberValue(record.level)
  }
}

function normalizeGoods(item: unknown, categories: CategoryItem[]): GoodsItem {
  const record = isRecord(item) ? item : {}
  const category = isRecord(record.category) ? record.category : {}
  const categoryId = text(record.categoryId ?? category.id)
  const type = mapGoodsType(record.type ?? record.goodsType)
  const stock = record.stockLabel ?? record.stockCount ?? record.stock ?? record.inventory
  return {
    id: text(record.id ?? record.goodsId),
    name: text(record.name ?? record.goodsName, '未命名商品'),
    faceValue: text(record.faceValue ?? record.face_value ?? record.spec, '标准规格'),
    price: numberValue(record.price ?? record.salePrice ?? record.sale_price),
    originalPrice:
      record.originalPrice === undefined && record.original_price === undefined
        ? undefined
        : numberValue(record.originalPrice ?? record.original_price),
    type,
    stockLabel: stock === undefined ? '库存充足' : `库存 ${text(stock)}`,
    category: text(record.categoryName ?? category.name ?? categories.find((item) => item.id === categoryId)?.name, '未分类'),
    categoryId,
    cover: text(record.cover ?? record.coverText, type === 'DIRECT' ? 'API' : type === 'MANUAL' ? 'MAN' : 'CARD'),
    requireRechargeAccount: Boolean(record.requireRechargeAccount ?? record.require_recharge_account),
    accountTypes: stringArray(record.accountTypes ?? record.account_types),
    availablePlatforms: stringArray(record.availablePlatforms ?? record.available_platforms),
    forbiddenPlatforms: stringArray(record.forbiddenPlatforms ?? record.forbidden_platforms)
  }
}

function normalizeRechargeField(item: unknown): RechargeField {
  const record = isRecord(item) ? item : {}
  return {
    id: text(record.id),
    code: text(record.code),
    label: text(record.label),
    placeholder: text(record.placeholder),
    helpText: text(record.helpText),
    inputType: text(record.inputType, 'text'),
    required: Boolean(record.required),
    sort: numberValue(record.sort, 10),
    enabled: record.enabled !== false
  }
}

function normalizeSiteSettings(item: unknown): SiteSettings {
  const record = isRecord(item) ? item : {}
  return {
    siteName: text(record.siteName, '喜易云'),
    logoUrl: text(record.logoUrl) || undefined,
    companyName: text(record.companyName) || undefined,
    icpRecordNo: text(record.icpRecordNo) || undefined,
    policeRecordNo: text(record.policeRecordNo) || undefined,
    disclaimer: text(record.disclaimer) || undefined
  }
}

function normalizeOrder(item: unknown): OrderItem {
  const record = isRecord(item) ? item : {}
  return {
    orderNo: text(record.orderNo ?? record.order_no ?? record.no),
    userId: text(record.userId ?? record.user_id) || undefined,
    goodsId: text(record.goodsId ?? record.goods_id) || undefined,
    goodsName: text(record.goodsName ?? record.goods_name ?? record.name, '未知商品'),
    goodsType: record.goodsType || record.goods_type ? mapGoodsType(record.goodsType ?? record.goods_type) : undefined,
    quantity: numberValue(record.quantity, 1),
    totalAmount: numberValue(record.totalAmount ?? record.total_amount ?? record.amount ?? record.payAmount),
    status: text(record.status, 'UNKNOWN'),
    paymentNo: text(record.paymentNo ?? record.payment_no) || undefined,
    payMethod: text(record.payMethod ?? record.pay_method) || undefined,
    deliveryStatus: text(record.deliveryStatus ?? record.delivery_status) || undefined,
    rechargeAccount: text(record.rechargeAccount ?? record.recharge_account) || undefined,
    createdAt: text(record.createdAt ?? record.created_at) || undefined,
    paidAt: text(record.paidAt ?? record.paid_at) || undefined,
    deliveredAt: text(record.deliveredAt ?? record.delivered_at) || undefined
  }
}

function normalizeUser(item: unknown): UserProfile {
  const record = isRecord(item) ? item : {}
  return {
    id: text(record.id),
    mobile: text(record.mobile) || undefined,
    email: text(record.email) || undefined,
    nickname: text(record.nickname, '喜易云会员'),
    groupId: text(record.groupId) || undefined,
    groupName: text(record.groupName) || undefined,
    balance: numberValue(record.balance),
    status: text(record.status) || undefined
  }
}

function normalizeAuthSession(item: unknown): AuthSession {
  const record = isRecord(item) ? item : {}
  return { token: text(record.token), profile: normalizeUser(record.profile) }
}

function normalizeDelivery(data: unknown, orderNo: string): OrderDelivery {
  const record = isRecord(unwrap(data)) ? (unwrap(data) as AnyRecord) : {}
  const cards = toArray(record.cards ?? record.cardList ?? record.deliveryItems ?? data).map((item) => {
    const card = isRecord(item) ? item : {}
    return {
      cardNo: text(card.cardNo ?? card.card_no ?? card.no ?? item),
      cardPassword: text(card.cardPassword ?? card.card_password) || undefined,
      password: text(card.password) || undefined,
      secret: text(card.secret ?? card.cardSecret ?? card.card_secret) || undefined,
      instruction: text(card.instruction ?? card.remark) || undefined
    } satisfies DeliveryCard
  })
  return {
    orderNo: text(record.orderNo ?? record.order_no, orderNo),
    status: text(record.status) || undefined,
    deliveryStatus: text(record.deliveryStatus ?? record.delivery_status) || undefined,
    instruction: text(record.instruction ?? record.remark ?? record.message) || undefined,
    viewedBefore: Boolean(record.viewedBefore ?? record.viewed_before),
    cards
  }
}

export async function fetchCategories() {
  const response = await apiClient.get('/api/h5/categories')
  return toArray(response.data).map(normalizeCategory).filter((item) => item.id && item.name)
}

export async function fetchRechargeFields() {
  const response = await apiClient.get('/api/h5/recharge-fields')
  return toArray(response.data).map(normalizeRechargeField).filter((item) => item.code && item.enabled)
}

export async function fetchSiteSettings() {
  const response = await apiClient.get('/api/h5/settings')
  return normalizeSiteSettings(unwrap(response.data))
}

export async function fetchGoods(categories: CategoryItem[], query: { categoryId?: string; search?: string; platform?: string } = {}) {
  const response = await apiClient.get('/api/h5/goods', { params: cleanParams({ ...query, platform: query.platform || 'pc', pageSize: 80 }) })
  const value = unwrap(response.data)
  const source = isRecord(value) ? value.items ?? value.records ?? value.list ?? value.rows : value
  return toArray(source).map((item) => normalizeGoods(item, categories)).filter((item) => item.id)
}

export async function fetchGoodsDetail(goodsId: string, categories: CategoryItem[]) {
  const response = await apiClient.get(`/api/h5/goods/${encodeURIComponent(goodsId)}`)
  return normalizeGoods(unwrap(response.data), categories)
}

export async function loginWeb(account: string, code = '') {
  const response = await apiClient.post('/api/h5/auth/sms/login', { account, code })
  const session = normalizeAuthSession(unwrap(response.data))
  if (!session.token) throw new Error('login failed')
  return session
}

export async function fetchMe() {
  const response = await apiClient.get('/api/h5/users/me')
  return normalizeUser(unwrap(response.data))
}

export async function createOrder(payload: CreateOrderPayload) {
  const response = await apiClient.post('/api/h5/orders', payload)
  return normalizeOrder(unwrap(response.data))
}

export async function payOrder(orderNo: string, payMethod = 'balance') {
  const response = await apiClient.post(`/api/h5/orders/${encodeURIComponent(orderNo)}/pay`, { payMethod, terminal: 'web' })
  return normalizeOrder(unwrap(response.data))
}

export async function fetchOrders() {
  const response = await apiClient.get('/api/h5/orders')
  return toArray(response.data).map(normalizeOrder).filter((item) => item.orderNo)
}

export async function fetchOrder(orderNo: string) {
  const response = await apiClient.get(`/api/h5/orders/${encodeURIComponent(orderNo)}`)
  return normalizeOrder(unwrap(response.data))
}

export async function fetchOrderDelivery(orderNo: string) {
  const response = await apiClient.get(`/api/h5/orders/${encodeURIComponent(orderNo)}/delivery`)
  return normalizeDelivery(response.data, orderNo)
}

export async function fetchApiCredential(profile?: UserProfile | null): Promise<ApiCredential> {
  return {
    appKey: profile?.id ? `user_${profile.id}_app` : 'login_required',
    appSecretMasked: '由后台分配，重置后仅展示一次',
    status: profile ? 'ENABLED' : 'DISABLED',
    ipWhitelist: [],
    dailyLimit: 500,
    lastUsedAt: ''
  }
}

export async function changePassword(payload: PasswordChangePayload) {
  if (!payload.currentPassword || !payload.newPassword || !payload.confirmPassword) {
    throw new Error('请完整填写密码信息。')
  }
  if (payload.newPassword !== payload.confirmPassword) {
    throw new Error('两次输入的新密码不一致。')
  }
  if (payload.newPassword.length < 6) {
    throw new Error('新密码至少需要 6 位。')
  }
  await wait(320)
  return { ok: true }
}

export async function createRechargeRequest(payload: RechargeRequestPayload): Promise<RechargeRequestResult> {
  if (!Number.isFinite(payload.amount) || payload.amount <= 0) {
    throw new Error('请选择或填写正确的充值金额。')
  }
  await wait(320)
  return {
    requestNo: `RCH${Date.now()}`,
    amount: payload.amount,
    payMethod: payload.payMethod,
    status: 'PENDING',
    createdAt: new Date().toLocaleString()
  }
}

function cleanParams(params: object) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== ''))
}

function wait(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
