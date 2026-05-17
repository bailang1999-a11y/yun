import { apiClient } from './client'
import type {
  ApiCredential,
  AuthPayload,
  AuthSession,
  CaptchaChallenge,
  CategoryItem,
  CreateOrderPayload,
  DeliveryCard,
  GoodsItem,
  GoodsType,
  OrderDelivery,
  OrderItem,
  PasswordChangePayload,
  PaymentChannel,
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
  if ('code' in data && Number(data.code) !== 0) {
    throw new Error(text(data.message ?? data.msg ?? data.error, '请求失败，请稍后重试。'))
  }
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

function optionalNumber(value: unknown) {
  if (value === null || value === undefined || value === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function positiveInteger(value: unknown, fallback = 1) {
  const parsed = optionalNumber(value)
  return parsed && parsed > 0 ? Math.floor(parsed) : fallback
}

function stringArray(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item).trim().toLowerCase()).filter(Boolean) : []
}

function mediaUrl(value: unknown) {
  const url = text(value).trim()
  if (!url) return undefined
  if (/^(https?:|data:|blob:)/i.test(url)) return url
  if (url.startsWith('/')) {
    const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL
    const baseUrl = import.meta.env.PROD
      ? window.location.origin
      : (configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : 'http://localhost:8080')
    return `${baseUrl.replace(/\/$/, '')}${url}`
  }
  return url
}

function normalizeTagList(value: unknown) {
  const legacySystemTags = new Set(['new', 'api-source'])
  const source = Array.isArray(value) ? value.map((item) => text(item)) : text(value).split(/[,，]/)
  const seen = new Set<string>()
  return source
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item) || legacySystemTags.has(item.toLowerCase())) return false
      seen.add(item)
      return true
    })
}

function cleanTextList(value: unknown) {
  const source = Array.isArray(value) ? value.map((item) => text(item)) : text(value).split(/[,，]/)
  const seen = new Set<string>()
  return source
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item)) return false
      seen.add(item)
      return true
    })
}

function normalizeSourceChannels(record: AnyRecord) {
  const directChannels = cleanTextList(record.sourceChannels ?? record.source_channels ?? record.supplierNames ?? record.supplier_names)
  const directSupplier = text(record.supplierName ?? record.supplier_name).trim()
  const integrationChannels = Array.isArray(record.integrations)
    ? record.integrations
        .filter(isRecord)
        .filter((item) => item.enabled !== false)
        .map((item) => text(item.supplierName ?? item.supplier_name).trim())
    : []

  return cleanTextList([...directChannels, directSupplier, ...integrationChannels])
}

const SALE_TERMINAL_PLATFORMS = new Set(['all', 'h5', 'web', 'pc', 'api'])

function hasSaleTerminalRestriction(platforms: string[]) {
  return platforms.some((item) => SALE_TERMINAL_PLATFORMS.has(item))
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
  const stock = optionalNumber(record.stock ?? record.stockCount ?? record.stock_count ?? record.inventory)
  const rawStockLabel = text(record.stockLabel ?? record.stock_label)
  const stockLabel = rawStockLabel || (stock === undefined ? '库存充足' : `库存 ${stock}`)
  const normalizedStockLabel = stockLabel.replace(/\s+/g, '').toLowerCase()
  const soldOut =
    stock === 0 ||
    Boolean(record.soldOut ?? record.sold_out) ||
    /售罄|无库存|缺货|已售完|库存0/.test(normalizedStockLabel)
  const priceLimitText = text(record.priceLimitText ?? record.price_limit_text)
  const hasPriceLimit = Boolean(priceLimitText || (record.priceLimited ?? record.price_limited))
  const maxBuy = positiveInteger(record.maxBuy ?? record.max_buy ?? record.maxQty ?? record.max_qty)
  const availablePlatforms = stringArray(record.availablePlatforms ?? record.available_platforms)
  const forbiddenPlatforms = stringArray(record.forbiddenPlatforms ?? record.forbidden_platforms)
  const platformForbidden =
    forbiddenPlatforms.includes('web')
    || (hasSaleTerminalRestriction(availablePlatforms) && !availablePlatforms.includes('all') && !availablePlatforms.includes('web') && !availablePlatforms.includes('pc'))
  const explicitCannotBuy = record.canBuy === false || record.can_buy === false
  const buyRestrictionReason = soldOut
    ? '商品库存不足，暂无法购买。'
    : platformForbidden
      ? '该商品未开放 Web 端购买。'
      : explicitCannotBuy
        ? '该商品当前暂不可购买。'
        : undefined

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
    stock,
    stockLabel,
    maxBuy: stock === undefined ? maxBuy : Math.max(1, Math.min(maxBuy, Math.floor(stock))),
    soldOut,
    canBuy: !soldOut && !platformForbidden && !explicitCannotBuy,
    buyRestrictionReason,
    category: text(record.categoryName ?? category.name ?? categories.find((item) => item.id === categoryId)?.name, '未分类'),
    categoryId,
    cover: text(record.cover ?? record.coverText, type === 'DIRECT' ? 'API' : type === 'MANUAL' ? 'MAN' : 'CARD'),
    coverUrl: mediaUrl(record.coverUrl ?? record.cover_url ?? record.imageUrl ?? record.image_url),
    requireRechargeAccount: Boolean(record.requireRechargeAccount ?? record.require_recharge_account),
    accountTypes: stringArray(record.accountTypes ?? record.account_types),
    benefitDurations: stringArray(record.benefitDurations ?? record.benefit_durations),
    benefitType: text(record.benefitType ?? record.benefit_type) || undefined,
    benefitBrand: text(record.benefitBrand ?? record.benefit_brand) || undefined,
    tags: normalizeTagList(record.tags ?? record.goodsTags ?? record.goods_tags),
    sourceChannels: normalizeSourceChannels(record),
    priceLimited: hasPriceLimit,
    priceLimitText: priceLimitText || (hasPriceLimit ? '限价' : undefined),
    availablePlatforms,
    forbiddenPlatforms
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
    disclaimer: text(record.disclaimer) || undefined,
    registrationEnabled: record.registrationEnabled !== false,
    registrationType: text(record.registrationType, 'MOBILE'),
    defaultUserGroupId: text(record.defaultUserGroupId) || undefined
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

function normalizePaymentChannel(item: unknown): PaymentChannel {
  const record = isRecord(item) ? item : {}
  return {
    id: text(record.id),
    code: text(record.code),
    name: text(record.name, '支付方式'),
    type: text(record.type, 'CUSTOM'),
    terminals: stringArray(record.terminals),
    status: text(record.status, 'ENABLED'),
    sort: numberValue(record.sort, 10),
    remark: text(record.remark) || undefined
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
  const response = await apiClient.get('/api/h5/goods', { params: cleanParams({ ...query, platform: 'web', pageSize: 80 }) })
  const value = unwrap(response.data)
  const source = isRecord(value) ? value.items ?? value.records ?? value.list ?? value.rows : value
  return toArray(source).map((item) => normalizeGoods(item, categories)).filter((item) => item.id)
}

export async function fetchGoodsDetail(goodsId: string, categories: CategoryItem[]) {
  const response = await apiClient.get(`/api/h5/goods/${encodeURIComponent(goodsId)}`, { params: { platform: 'web' } })
  return normalizeGoods(unwrap(response.data), categories)
}

export async function loginWeb(account: string, code = '') {
  const response = await apiClient.post('/api/h5/auth/sms/login', { account, code, terminal: 'web' })
  const session = normalizeAuthSession(unwrap(response.data))
  if (!session.token) throw new Error('login failed')
  return session
}

export async function authWeb(payload: AuthPayload) {
  const response = await apiClient.post('/api/h5/auth', payload)
  const session = normalizeAuthSession(unwrap(response.data))
  if (!session.token) throw new Error('auth failed')
  return session
}

export async function createWebSliderToken() {
  const response = await apiClient.post('/api/h5/auth/slider', { terminal: 'web' })
  return text(unwrap(response.data))
}

export async function fetchWebCaptchaChallenge(): Promise<CaptchaChallenge> {
  const response = await apiClient.get('/api/h5/auth/captcha-config', { params: { terminal: 'web' } })
  const record = isRecord(unwrap(response.data)) ? (unwrap(response.data) as AnyRecord) : {}
  return {
    enabled: Boolean(record.enabled),
    provider: text(record.provider, 'TENCENT'),
    appId: text(record.appId),
    scene: text(record.scene, 'login')
  }
}

export async function sendWebLoginSms(account: string, captchaTicket = '', captchaRandstr = '', mode: 'login' | 'register' | 'forgot' = 'login') {
  const response = await apiClient.post('/api/h5/auth/sms/send', { account, terminal: 'web', captchaTicket, captchaRandstr, mode })
  return text(unwrap(response.data), '验证码已发送')
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

export async function fetchPaymentChannels(terminal: 'h5' | 'web' | 'api' = 'web') {
  const response = await apiClient.get('/api/h5/payment-channels', { params: { terminal } })
  return toArray(response.data)
    .map(normalizePaymentChannel)
    .filter((item) => item.code && item.status === 'ENABLED')
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

export async function fetchApiCredential(): Promise<ApiCredential> {
  const response = await apiClient.get('/api/h5/member-api')
  const record = isRecord(unwrap(response.data)) ? (unwrap(response.data) as AnyRecord) : {}
  const rawSecret = text(record.appSecret)
  return {
    appKey: text(record.appKey),
    appSecretMasked: text(record.appSecretMasked, rawSecret ? maskSecret(rawSecret) : '由后台分配，重置后仅展示一次'),
    status: text(record.status, 'DISABLED'),
    ipWhitelist: toArray(record.ipWhitelist).map((item) => text(item)).filter(Boolean),
    dailyLimit: numberValue(record.dailyLimit),
    lastUsedAt: text(record.lastUsedAt) || undefined
  }
}

export async function saveApiCredential(payload: { enabled?: boolean; resetSecret?: boolean; ipWhitelist?: string[]; dailyLimit?: number }) {
  const response = await apiClient.post('/api/h5/member-api', payload)
  const record = isRecord(unwrap(response.data)) ? (unwrap(response.data) as AnyRecord) : {}
  const rawSecret = text(record.appSecret)
  return {
    appKey: text(record.appKey),
    appSecretMasked: text(record.appSecretMasked, rawSecret ? maskSecret(rawSecret) : '由后台分配，重置后仅展示一次'),
    status: text(record.status, 'DISABLED'),
    ipWhitelist: toArray(record.ipWhitelist).map((item) => text(item)).filter(Boolean),
    dailyLimit: numberValue(record.dailyLimit),
    lastUsedAt: text(record.lastUsedAt) || undefined
  } satisfies ApiCredential
}

function maskSecret(value: string) {
  if (value.length <= 8) return '********'
  return `${value.slice(0, 4)}****${value.slice(-4)}`
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
  await apiClient.post('/api/h5/users/me/password', payload)
  return { ok: true }
}

export async function createRechargeRequest(payload: RechargeRequestPayload): Promise<RechargeRequestResult> {
  if (!Number.isFinite(payload.amount) || payload.amount <= 0) {
    throw new Error('请选择或填写正确的充值金额。')
  }
  const response = await apiClient.post('/api/h5/recharge-requests', payload)
  const record = isRecord(unwrap(response.data)) ? (unwrap(response.data) as AnyRecord) : {}
  return {
    requestNo: text(record.requestNo),
    amount: numberValue(record.amount, payload.amount),
    payMethod: text(record.payMethod, payload.payMethod),
    status: text(record.status, 'SUCCESS'),
    createdAt: text(record.createdAt, new Date().toLocaleString())
  }
}

function cleanParams(params: object) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== ''))
}
