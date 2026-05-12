import { apiClient } from './client'
import type {
  AuthSession,
  CaptchaChallenge,
  AuthPayload,
  CreateOrderPayload,
  DeliveryCard,
  GoodsCard,
  GoodsType,
  H5Category,
  H5Order,
  H5SystemSetting,
  OrderDelivery,
  PaymentChannel,
  RechargeField,
  UserProfile
} from '../types/h5'

type AnyRecord = Record<string, unknown>

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

function isRecord(value: unknown): value is AnyRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(value: unknown, fallback = '') {
  return value === null || value === undefined ? fallback : String(value)
}

function child(record: AnyRecord, key: string) {
  const value = record[key]
  return isRecord(value) ? value : {}
}

function numberValue(value: unknown, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function optionalNumberValue(value: unknown) {
  if (value === undefined || value === null || value === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function positiveInteger(value: unknown, fallback: number) {
  const parsed = optionalNumberValue(value)
  return parsed === undefined ? fallback : Math.max(1, Math.floor(parsed))
}

function stringArray(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item)).filter(Boolean) : []
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

function normalizePlatformList(value: unknown) {
  return stringArray(value).map((item) => item.toLowerCase())
}

function normalizeTagList(value: unknown) {
  const legacySystemTags = new Set(['new', 'api-source'])
  const source = Array.isArray(value) ? stringArray(value) : text(value).split(/[,，]/)
  const seen = new Set<string>()
  return source
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item) || legacySystemTags.has(item.toLowerCase())) return false
      seen.add(item)
      return true
    })
}

const SALE_TERMINAL_PLATFORMS = new Set(['all', 'h5', 'web', 'pc', 'api', 'private'])

function hasSaleTerminalRestriction(platforms: string[]) {
  return platforms.some((item) => SALE_TERMINAL_PLATFORMS.has(item))
}

function deriveStockLabel(stock: number | undefined, label: string) {
  if (label) return label
  if (stock === undefined) return '库存充足'
  return `库存 ${stock}`
}

function deriveSoldOut(stock: number | undefined, stockLabel: string) {
  if (stock !== undefined) return stock <= 0
  return /售罄|缺货|无货|库存\s*0|库存：0|库存不足|仅剩\s*0|剩余\s*0|^\s*0\s*$/.test(stockLabel)
}

function canBuyOnH5(record: AnyRecord, soldOut: boolean, availablePlatforms: string[], forbiddenPlatforms: string[]) {
  if (soldOut) return false
  if (forbiddenPlatforms.includes('h5')) return false
  if (hasSaleTerminalRestriction(availablePlatforms) && !availablePlatforms.includes('all') && !availablePlatforms.includes('h5')) return false
  const status = text(record.status ?? record.saleStatus ?? record.sale_status).toUpperCase()
  if (['OFF', 'OFFLINE', 'OFF_SALE', 'DISABLED', 'DISABLE', 'SOLD_OUT', 'CLOSED'].includes(status)) return false
  return true
}

function mapGoodsType(value: unknown): GoodsType {
  const raw = text(value).toUpperCase()
  if (raw === 'DIRECT_RECHARGE') return 'DIRECT'
  if (raw === 'AGENT_RECHARGE') return 'MANUAL'
  if (raw === 'DIRECT' || raw === 'MANUAL' || raw === 'CARD') return raw
  return 'CARD'
}

function normalizeCategory(item: unknown): H5Category {
  if (!isRecord(item)) return { id: text(item), name: text(item, '未命名分类') }
  const id = text(item.id ?? item.categoryId ?? item.code ?? item.name)
  const name = text(item.name ?? item.categoryName ?? item.title, '未命名分类')
  const rawParentId = text(item.parentId ?? item.parent_id ?? item.pid)
  const parentId = rawParentId && rawParentId !== '0' ? rawParentId : undefined
  const levelValue = item.level ?? item.categoryLevel
  const level = levelValue === undefined ? undefined : numberValue(levelValue)
  return { id, name, parentId, level }
}

function normalizeGoods(item: unknown, categories: H5Category[]): GoodsCard {
  const record = isRecord(item) ? item : {}
  const type = mapGoodsType(record.type ?? record.goodsType)
  const category = child(record, 'category')
  const categoryId = text(record.categoryId ?? category.id)
  const categoryName = text(
    record.categoryName ?? category.name ?? categories.find((item) => item.id === categoryId)?.name,
    '未分类'
  )
  const rawStock = record.stockCount ?? record.stock ?? record.inventory ?? record.stockNum ?? record.stock_num
  const stock = optionalNumberValue(rawStock)
  const stockLabel = deriveStockLabel(stock, text(record.stockLabel ?? record.stock_label))
  const soldOut = deriveSoldOut(stock, stockLabel)
  const maxBuy = positiveInteger(record.maxBuy ?? record.max_buy ?? record.maxQty ?? record.max_qty, 9)
  const availablePlatforms = normalizePlatformList(record.availablePlatforms ?? record.available_platforms)
  const forbiddenPlatforms = normalizePlatformList(record.forbiddenPlatforms ?? record.forbidden_platforms)
  const id = text(record.id ?? record.goodsId)

  const priceLimitText = text(record.priceLimitText ?? record.price_limit_text)
  const hasPriceLimit = Boolean(priceLimitText || (record.priceLimited ?? record.price_limited))

  return {
    id,
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
    maxBuy,
    soldOut,
    canBuy: canBuyOnH5(record, soldOut, availablePlatforms, forbiddenPlatforms),
    category: categoryName,
    categoryId,
    cover: text(record.cover ?? record.coverText, type === 'DIRECT' ? 'API' : type === 'MANUAL' ? 'MAN' : 'CARD'),
    coverUrl: mediaUrl(record.coverUrl ?? record.cover_url ?? record.imageUrl ?? record.image_url),
    requireRechargeAccount: Boolean(record.requireRechargeAccount ?? record.require_recharge_account),
    accountTypes: stringArray(record.accountTypes ?? record.account_types),
    benefitDurations: stringArray(record.benefitDurations ?? record.benefit_durations),
    benefitType: text(record.benefitType ?? record.benefit_type) || undefined,
    benefitBrand: text(record.benefitBrand ?? record.benefit_brand) || undefined,
    tags: normalizeTagList(record.tags ?? record.goodsTags ?? record.goods_tags),
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

function normalizeOrder(item: unknown): H5Order {
  const record = isRecord(item) ? item : {}
  return {
    orderNo: text(record.orderNo ?? record.order_no ?? record.no),
    userId: text(record.userId ?? record.user_id) || undefined,
    buyerAccount: text(record.buyerAccount ?? record.buyer_account) || undefined,
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
    nickname: text(record.nickname, '游客用户'),
    groupId: text(record.groupId) || undefined,
    groupName: text(record.groupName) || undefined,
    balance: numberValue(record.balance),
    status: text(record.status) || undefined
  }
}

function normalizeAuthSession(item: unknown): AuthSession {
  const record = isRecord(item) ? item : {}
  return {
    token: text(record.token),
    profile: normalizeUser(record.profile)
  }
}

function normalizeDelivery(data: unknown, orderNo: string): OrderDelivery {
  const value = unwrap(data)
  const record = isRecord(value) ? value : {}
  const cards = toArray(record.cards ?? record.cardList ?? record.deliveryItems ?? value).map((item) => {
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

export async function fetchH5Categories() {
  const response = await apiClient.get('/api/h5/categories')
  const categories = toArray(response.data).map(normalizeCategory).filter((item) => item.name)
  return [{ id: 'all', name: '全部' }, ...categories]
}

export async function fetchH5RechargeFields() {
  const response = await apiClient.get('/api/h5/recharge-fields')
  return toArray(response.data)
    .map(normalizeRechargeField)
    .filter((item) => item.code && item.enabled)
    .sort((left, right) => left.sort - right.sort)
}

export async function fetchH5Settings(): Promise<H5SystemSetting> {
  const response = await apiClient.get('/api/h5/settings')
  const setting = unwrap(response.data)
  const record = isRecord(setting) ? setting : {}
  return {
    registrationEnabled: record.registrationEnabled !== false,
    registrationType: text(record.registrationType, 'MOBILE'),
    defaultUserGroupId: text(record.defaultUserGroupId)
  }
}

export async function loginH5(account: string, code = '') {
  const response = await apiClient.post('/api/h5/auth/sms/login', { account, code, terminal: 'h5' })
  const session = normalizeAuthSession(unwrap(response.data))
  if (!session.token) {
    throw new Error('login failed')
  }
  return session
}

export async function authH5(payload: AuthPayload) {
  const response = await apiClient.post('/api/h5/auth', payload)
  const session = normalizeAuthSession(unwrap(response.data))
  if (!session.token) {
    throw new Error('auth failed')
  }
  return session
}

export async function createH5SliderToken() {
  const response = await apiClient.post('/api/h5/auth/slider', { terminal: 'h5' })
  return text(unwrap(response.data))
}

export async function fetchH5CaptchaChallenge(terminal: 'h5' | 'web' = 'h5'): Promise<CaptchaChallenge> {
  const response = await apiClient.get('/api/h5/auth/captcha-config', { params: { terminal } })
  const record = isRecord(unwrap(response.data)) ? (unwrap(response.data) as AnyRecord) : {}
  return {
    enabled: Boolean(record.enabled),
    provider: text(record.provider, 'TENCENT'),
    appId: text(record.appId),
    scene: text(record.scene, 'login')
  }
}

export async function sendH5LoginSms(account: string, captchaTicket = '', captchaRandstr = '', mode: 'login' | 'register' | 'forgot' = 'login') {
  const response = await apiClient.post('/api/h5/auth/sms/send', { account, terminal: 'h5', captchaTicket, captchaRandstr, mode })
  return text(unwrap(response.data), '验证码已发送')
}

export async function fetchH5Me(token: string) {
  const response = await apiClient.get('/api/h5/users/me', {
    headers: { Authorization: `Bearer ${token}` }
  })
  return normalizeUser(unwrap(response.data))
}

export async function fetchH5Goods(
  categories: H5Category[],
  query: { categoryId?: string; search?: string; platform?: string; page?: number; pageSize?: number } = {}
) {
  const response = await apiClient.get('/api/h5/goods', {
    params: cleanParams({ platform: 'h5', ...query })
  })
  return toArray(response.data).map((item) => normalizeGoods(item, categories)).filter((item) => item.id)
}

export async function fetchH5GoodsPage(
  categories: H5Category[],
  query: { categoryId?: string; search?: string; platform?: string; page: number; pageSize: number }
) {
  const response = await apiClient.get('/api/h5/goods', {
    params: cleanParams({ platform: 'h5', ...query })
  })
  const value = unwrap(response.data)
  if (!isRecord(value)) {
    const items = toArray(value).map((item) => normalizeGoods(item, categories)).filter((item) => item.id)
    return { items, total: items.length, page: query.page, pageSize: query.pageSize }
  }

  const items = toArray(value.items ?? value.records ?? value.list ?? value.rows)
    .map((item) => normalizeGoods(item, categories))
    .filter((item) => item.id)

  return {
    items,
    total: numberValue(value.total ?? value.totalCount, items.length),
    page: numberValue(value.page, query.page),
    pageSize: numberValue(value.pageSize ?? value.size, query.pageSize)
  }
}

export async function fetchH5GoodsDetail(goodsId: string, categories: H5Category[] = []) {
  const response = await apiClient.get(`/api/h5/goods/${encodeURIComponent(goodsId)}`, {
    params: { terminal: 'h5', platform: 'h5' }
  })
  return normalizeGoods(unwrap(response.data), categories)
}

export async function createH5Order(payload: CreateOrderPayload) {
  const response = await apiClient.post('/api/h5/orders', payload)
  return normalizeOrder(unwrap(response.data))
}

export async function payH5Order(orderNo: string, payMethod = 'wechat') {
  const response = await apiClient.post(`/api/h5/orders/${encodeURIComponent(orderNo)}/pay`, {
    payMethod,
    terminal: 'h5'
  })
  return normalizeOrder(unwrap(response.data))
}

export async function fetchH5PaymentChannels(terminal: 'h5' | 'web' | 'api' = 'h5') {
  const response = await apiClient.get('/api/h5/payment-channels', { params: { terminal } })
  return toArray(response.data)
    .map(normalizePaymentChannel)
    .filter((item) => item.code && item.status === 'ENABLED')
}

export async function cancelH5Order(orderNo: string) {
  const response = await apiClient.post(`/api/h5/orders/${encodeURIComponent(orderNo)}/cancel`)
  return normalizeOrder(unwrap(response.data))
}

export async function fetchH5Orders() {
  const response = await apiClient.get('/api/h5/orders')
  return toArray(response.data).map(normalizeOrder).filter((item) => item.orderNo)
}

export async function fetchH5Order(orderNo: string) {
  const response = await apiClient.get(`/api/h5/orders/${encodeURIComponent(orderNo)}`)
  return normalizeOrder(unwrap(response.data))
}

export async function fetchH5OrderDelivery(orderNo: string) {
  const response = await apiClient.get(`/api/h5/orders/${encodeURIComponent(orderNo)}/delivery`)
  return normalizeDelivery(response.data, orderNo)
}

function cleanParams(params: object) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '')
  )
}
