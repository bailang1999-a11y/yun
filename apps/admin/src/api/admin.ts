import { apiClient } from './client'
import type {
  AdminAuthSession,
  AdminProfile,
  CardImportItem,
  Category,
  CategoryCreatePayload,
  Goods,
  GoodsCard,
  GoodsChannel,
  GoodsChannelCreatePayload,
  GoodsCreatePayload,
  GroupRule,
  GroupRulePatchPayload,
  MemberApiCredential,
  OpenApiLog,
  OperationLog,
  Order,
  OrderQuery,
  PaymentRecord,
  RefundRecord,
  SmsLog,
  UserAccount,
  UserGroup,
  UserGroupCreatePayload,
  Supplier,
  SupplierCreatePayload,
  SystemSetting
} from '../types/operations'

interface ApiEnvelope<T> {
  data?: T | ApiEnvelope<T>
  list?: T
  records?: T
  items?: T
}

function unwrapValue<T>(payload: T | ApiEnvelope<T>): T {
  const envelope = payload as ApiEnvelope<T>
  if (envelope && typeof envelope === 'object' && 'data' in envelope && envelope.data !== undefined) {
    return unwrapValue(envelope.data as T | ApiEnvelope<T>)
  }
  return payload as T
}

function unwrapResponse<T>(payload: T | ApiEnvelope<T>): T {
  if (Array.isArray(payload)) {
    return payload as T
  }

  const envelope = payload as ApiEnvelope<T>
  const inner = envelope.data

  if (Array.isArray(inner)) {
    return inner as T
  }

  if (inner && typeof inner === 'object') {
    const nested = inner as ApiEnvelope<T>

    if (Array.isArray(nested.data)) {
      return nested.data as T
    }

    if (Array.isArray(nested.records)) {
      return nested.records as T
    }

    if (Array.isArray(nested.list)) {
      return nested.list as T
    }

    if (Array.isArray(nested.items)) {
      return nested.items as T
    }
  }

  if (Array.isArray(envelope.records)) {
    return envelope.records as T
  }

  if (Array.isArray(envelope.list)) {
    return envelope.list as T
  }

  if (Array.isArray(envelope.items)) {
    return envelope.items as T
  }

  return payload as T
}

export async function fetchGoods(query: { categoryId?: string | number; platform?: string; search?: string } = {}) {
  const { data } = await apiClient.get<unknown>('/api/admin/goods', {
    params: cleanParams(query)
  })

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGoods)
}

export async function loginAdmin(account: string, password: string) {
  const { data } = await apiClient.post<unknown>('/api/admin/auth/login', { account, password })

  const session = normalizeAdminAuthSession(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
  if (!session.token) {
    throw new Error('login failed')
  }
  return session
}

export async function fetchAdminMe() {
  const { data } = await apiClient.get<unknown>('/api/admin/auth/me')

  return normalizeAdminProfile(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchSettings() {
  const { data } = await apiClient.get<unknown>('/api/admin/settings')

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateSettings(payload: SystemSetting) {
  const { data } = await apiClient.post<unknown>('/api/admin/settings', payload)

  return normalizeSettings(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchPayments() {
  const { data } = await apiClient.get<unknown>('/api/admin/payments')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizePayment)
}

export async function fetchRefunds() {
  const { data } = await apiClient.get<unknown>('/api/admin/refunds')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeRefund)
}

export async function fetchSmsLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/sms-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeSmsLog)
}

export async function fetchOperationLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/operation-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeOperationLog)
}

export async function fetchMemberApiCredentials() {
  const { data } = await apiClient.get<unknown>('/api/admin/member-api-credentials')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeMemberApiCredential)
}

export async function fetchOpenApiLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/open-api-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeOpenApiLog)
}

export async function fetchCategories() {
  const { data } = await apiClient.get<unknown>('/api/admin/categories')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCategory)
}

export async function fetchUserGroups() {
  const { data } = await apiClient.get<unknown>('/api/admin/user-groups')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeUserGroup)
}

export async function createUserGroup(payload: UserGroupCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/user-groups', payload)

  return normalizeUserGroup(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchUsers() {
  const { data } = await apiClient.get<unknown>('/api/admin/users')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeUser)
}

export async function updateGroupRules(groupId: UserGroup['id'], payload: GroupRulePatchPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/user-groups/${groupId}/rules`, payload)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGroupRule)
}

export async function updateUserGroup(userId: UserAccount['id'], groupId: UserGroup['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/users/${userId}/group`, { groupId })

  return normalizeUser(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function createCategory(payload: CategoryCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/categories', payload)

  return normalizeCategory(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function setCategoryEnabled(categoryId: Category['id'], enabled: boolean) {
  const action = enabled ? 'enable' : 'disable'
  const { data } = await apiClient.post<unknown>(`/api/admin/categories/${categoryId}/${action}`)

  return normalizeCategory(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchSuppliers() {
  const { data } = await apiClient.get<unknown>('/api/admin/suppliers')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeSupplier)
}

export async function createSupplier(payload: SupplierCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/suppliers', payload)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function setSupplierEnabled(supplierId: Supplier['id'], enabled: boolean) {
  const action = enabled ? 'enable' : 'disable'
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/${action}`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function refreshSupplierBalance(supplierId: Supplier['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/balance`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function testSupplierConnection(supplierId: Supplier['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/suppliers/${supplierId}/test`)

  return normalizeSupplier(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function createGoods(payload: GoodsCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/goods', {
    name: payload.name,
    goodsName: payload.name,
    price: payload.price,
    originalPrice: payload.originalPrice,
    status: payload.status,
    type: normalizeGoodsType(payload.deliveryType),
    subTitle: payload.subTitle,
    coverUrl: payload.coverUrl,
    detailImages: payload.detailImages || [],
    detailBlocks: payload.detailBlocks || [],
    benefitDurations: payload.benefitDurations || [],
    integrations: payload.integrations || [],
    pollingEnabled: payload.pollingEnabled,
    monitoringEnabled: payload.monitoringEnabled,
    stock: payload.stock,
    maxBuy: payload.maxBuy,
    requireRechargeAccount: payload.requireRechargeAccount,
    accountTypes: payload.accountTypes || [],
    priceTemplateId: payload.priceTemplateId,
    priceMode: payload.priceMode,
    priceCoefficient: payload.priceCoefficient,
    priceFixedAdd: payload.priceFixedAdd,
    description: payload.description,
    categoryId: payload.categoryId,
    platform: payload.platform || 'GENERAL',
    availablePlatforms: payload.availablePlatforms?.length ? payload.availablePlatforms : ['private'],
    forbiddenPlatforms: payload.forbiddenPlatforms || []
  })

  return normalizeGoods(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateGoods(goodsId: Goods['id'], payload: GoodsCreatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/goods/${goodsId}`, {
    name: payload.name,
    goodsName: payload.name,
    price: payload.price,
    originalPrice: payload.originalPrice,
    status: payload.status,
    type: normalizeGoodsType(payload.deliveryType),
    subTitle: payload.subTitle,
    coverUrl: payload.coverUrl,
    detailImages: payload.detailImages || [],
    detailBlocks: payload.detailBlocks || [],
    benefitDurations: payload.benefitDurations || [],
    integrations: payload.integrations || [],
    pollingEnabled: payload.pollingEnabled,
    monitoringEnabled: payload.monitoringEnabled,
    stock: payload.stock,
    maxBuy: payload.maxBuy,
    requireRechargeAccount: payload.requireRechargeAccount,
    accountTypes: payload.accountTypes || [],
    priceTemplateId: payload.priceTemplateId,
    priceMode: payload.priceMode,
    priceCoefficient: payload.priceCoefficient,
    priceFixedAdd: payload.priceFixedAdd,
    description: payload.description,
    categoryId: payload.categoryId,
    platform: payload.platform || 'GENERAL',
    availablePlatforms: payload.availablePlatforms?.length ? payload.availablePlatforms : ['private'],
    forbiddenPlatforms: payload.forbiddenPlatforms || []
  })

  return normalizeGoods(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function importGoodsCards(goodsId: Goods['id'], cards: CardImportItem[]) {
  const { data } = await apiClient.post(`/api/admin/goods/${goodsId}/cards/import`, {
    cards: cards.map((card) => `${card.cardNo},${card.password}`)
  })

  return data
}

export async function fetchGoodsCards(goodsId: Goods['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/goods/${goodsId}/cards`)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCard)
}

export async function fetchGoodsChannels(goodsId: Goods['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/goods/${goodsId}/channels`)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGoodsChannel)
}

export async function createGoodsChannel(goodsId: Goods['id'], payload: GoodsChannelCreatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/goods/${goodsId}/channels`, payload)

  return normalizeGoodsChannel(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteGoodsChannel(goodsId: Goods['id'], channelId: GoodsChannel['id']) {
  const { data } = await apiClient.post(`/api/admin/goods/${goodsId}/channels/${channelId}/delete`)

  return data
}

export async function fetchOrders(query: OrderQuery = {}) {
  const { data } = await apiClient.get<unknown>('/api/admin/orders', {
    params: cleanParams(query)
  })

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeOrder)
}

export async function exportOrdersExcel(query: OrderQuery = {}) {
  const response = await apiClient.get<Blob>('/api/admin/orders/export', {
    params: cleanParams(query),
    responseType: 'blob'
  })

  return response.data
}

export async function fetchOrderDetail(orderNo: string) {
  const { data } = await apiClient.get<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function completeManualOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/complete-manual`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function retryOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/retry`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function retryOrderWithChannel(orderNo: string, channelId: GoodsChannel['id']) {
  const { data } = await apiClient.post<unknown>(
    `/api/admin/orders/${encodeURIComponent(orderNo)}/retry-channel/${encodeURIComponent(String(channelId))}`
  )

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function refundOrder(orderNo: string) {
  const { data } = await apiClient.post<unknown>(`/api/admin/orders/${encodeURIComponent(orderNo)}/refund`)

  return normalizeOrder(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

function normalizeGoodsType(value: string) {
  if (value === 'AUTO' || value === 'DIRECT') return 'DIRECT'
  if (value === 'MANUAL') return 'MANUAL'
  return 'CARD'
}

function cleanParams(params: object) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '')
  )
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

function normalizeGoods(item: Record<string, unknown>): Goods {
  return {
    id: text(item.id),
    categoryId: text(item.categoryId),
    categoryName: text(item.categoryName),
    name: text(item.name ?? item.goodsName, '未命名商品'),
    price: numberValue(item.price ?? item.salePrice),
    originalPrice: numberValue(item.originalPrice),
    status: text(item.status, 'UNKNOWN'),
    stock: numberValue(item.stock, 0),
    deliveryType: text(item.deliveryType ?? item.type ?? item.goodsType, 'CARD'),
    platform: text(item.platform),
    subTitle: text(item.subTitle),
    coverUrl: text(item.coverUrl),
    detailImages: stringArray(item.detailImages),
    detailBlocks: normalizeDetailBlocks(item.detailBlocks),
    benefitDurations: stringArray(item.benefitDurations),
    integrations: normalizeIntegrations(item.integrations),
    pollingEnabled: Boolean(item.pollingEnabled),
    monitoringEnabled: Boolean(item.monitoringEnabled),
    maxBuy: numberValue(item.maxBuy, 1),
    requireRechargeAccount: Boolean(item.requireRechargeAccount),
    accountTypes: stringArray(item.accountTypes),
    priceTemplateId: text(item.priceTemplateId),
    priceMode: text(item.priceMode, 'FIXED'),
    priceCoefficient: numberValue(item.priceCoefficient, 1),
    priceFixedAdd: numberValue(item.priceFixedAdd, 0),
    availablePlatforms: stringArray(item.availablePlatforms ?? item.available_platforms),
    forbiddenPlatforms: stringArray(item.forbiddenPlatforms ?? item.forbidden_platforms),
    description: text(item.description),
    createdAt: text(item.createdAt)
  }
}

function normalizeIntegrations(value: unknown) {
  if (!Array.isArray(value)) return []
  return value.map((item) => {
    const record = (item || {}) as Record<string, unknown>
    return {
      id: text(record.id),
      supplierId: text(record.supplierId),
      supplierName: text(record.supplierName),
      platformCode: text(record.platformCode),
      supplierGoodsId: text(record.supplierGoodsId),
      supplierGoodsName: text(record.supplierGoodsName),
      supplierPrice: numberValue(record.supplierPrice),
      upstreamStatus: text(record.upstreamStatus, '正常'),
      upstreamStock: numberValue(record.upstreamStock),
      upstreamTitle: text(record.upstreamTitle),
      lastSyncAt: text(record.lastSyncAt),
      enabled: record.enabled === undefined ? true : Boolean(record.enabled)
    }
  })
}

function normalizeDetailBlocks(value: unknown) {
  if (!Array.isArray(value)) return []
  return value
    .map((item) => {
      const record = (item || {}) as Record<string, unknown>
      return {
        type: text(record.type, text(record.imageUrl) ? 'image' : 'text') as 'image' | 'text',
        imageUrl: text(record.imageUrl),
        text: text(record.text)
      }
    })
    .filter((item) => item.imageUrl || item.text)
}

function normalizeAdminProfile(item: Record<string, unknown>): AdminProfile {
  return {
    id: text(item.id),
    username: text(item.username, 'admin'),
    nickname: text(item.nickname, '运营管理员'),
    permissions: stringArray(item.permissions)
  }
}

function normalizeAdminAuthSession(item: Record<string, unknown>): AdminAuthSession {
  return {
    token: text(item.token),
    profile: normalizeAdminProfile((item.profile || {}) as Record<string, unknown>)
  }
}

function normalizeCategory(item: Record<string, unknown>): Category {
  const rawParentId = text(item.parentId ?? item.parent_id)
  return {
    id: text(item.id),
    name: text(item.name, '未命名分类'),
    parentId: rawParentId && rawParentId !== '0' ? rawParentId : undefined,
    sort: numberValue(item.sort),
    enabled: item.enabled === undefined ? true : Boolean(item.enabled)
  }
}

function normalizeGroupRule(item: Record<string, unknown>): GroupRule {
  const ruleType = text(item.ruleType, 'CATEGORY').toUpperCase() === 'PLATFORM' ? 'PLATFORM' : 'CATEGORY'
  const permissionText = text(item.permission, 'NONE').toUpperCase()
  const permission = permissionText === 'ALLOW' || permissionText === 'DENY' ? permissionText : 'NONE'

  return {
    groupId: text(item.groupId),
    ruleType,
    targetId: text(item.targetId) || undefined,
    targetCode: text(item.targetCode) || undefined,
    targetName: text(item.targetName) || undefined,
    permission
  }
}

function normalizeUserGroup(item: Record<string, unknown>): UserGroup {
  const rawRules = Array.isArray(item.rules) ? item.rules : []
  return {
    id: text(item.id),
    name: text(item.name, '未命名用户组'),
    description: text(item.description),
    defaultGroup: Boolean(item.defaultGroup),
    userCount: numberValue(item.userCount),
    status: text(item.status, 'UNKNOWN'),
    rules: rawRules.map((rule) => normalizeGroupRule((rule || {}) as Record<string, unknown>))
  }
}

function normalizeUser(item: Record<string, unknown>): UserAccount {
  return {
    id: text(item.id),
    avatar: text(item.avatar),
    mobile: text(item.mobile),
    email: text(item.email),
    nickname: text(item.nickname, '未命名用户'),
    groupId: text(item.groupId),
    groupName: text(item.groupName),
    balance: numberValue(item.balance),
    status: text(item.status, 'UNKNOWN'),
    createdAt: text(item.createdAt)
  }
}

function normalizeSettings(item: Record<string, unknown>): SystemSetting {
  const receivers = item.notificationReceivers
  return {
    siteName: text(item.siteName, '喜易云'),
    logoUrl: text(item.logoUrl),
    customerService: text(item.customerService),
    paymentMode: text(item.paymentMode, 'MOCK'),
    autoRefundEnabled: Boolean(item.autoRefundEnabled),
    smsProvider: text(item.smsProvider, 'TENCENT'),
    smsEnabled: Boolean(item.smsEnabled),
    upstreamSyncSeconds: numberValue(item.upstreamSyncSeconds, 30),
    autoShelfEnabled: Boolean(item.autoShelfEnabled),
    autoPriceEnabled: Boolean(item.autoPriceEnabled),
    notificationReceivers: receivers && typeof receivers === 'object' ? (receivers as Record<string, string>) : {}
  }
}

function normalizePayment(item: Record<string, unknown>): PaymentRecord {
  return {
    paymentNo: text(item.paymentNo),
    orderNo: text(item.orderNo),
    userId: text(item.userId),
    method: text(item.method),
    amount: numberValue(item.amount),
    status: text(item.status),
    channelTradeNo: text(item.channelTradeNo),
    createdAt: text(item.createdAt),
    paidAt: text(item.paidAt)
  }
}

function normalizeRefund(item: Record<string, unknown>): RefundRecord {
  return {
    refundNo: text(item.refundNo),
    orderNo: text(item.orderNo),
    paymentNo: text(item.paymentNo),
    userId: text(item.userId),
    amount: numberValue(item.amount),
    status: text(item.status),
    reason: text(item.reason),
    createdAt: text(item.createdAt),
    refundedAt: text(item.refundedAt)
  }
}

function normalizeSmsLog(item: Record<string, unknown>): SmsLog {
  return {
    id: text(item.id),
    orderNo: text(item.orderNo),
    mobile: text(item.mobile),
    templateType: text(item.templateType),
    content: text(item.content),
    status: text(item.status),
    errorMessage: text(item.errorMessage),
    createdAt: text(item.createdAt)
  }
}

function normalizeOperationLog(item: Record<string, unknown>): OperationLog {
  return {
    id: text(item.id),
    operator: text(item.operator),
    action: text(item.action),
    resourceType: text(item.resourceType),
    resourceId: text(item.resourceId),
    remark: text(item.remark),
    createdAt: text(item.createdAt)
  }
}

function normalizeMemberApiCredential(item: Record<string, unknown>): MemberApiCredential {
  return {
    id: text(item.id),
    userId: text(item.userId),
    appKey: text(item.appKey),
    appSecret: text(item.appSecret),
    status: text(item.status),
    ipWhitelist: stringArray(item.ipWhitelist),
    dailyLimit: numberValue(item.dailyLimit),
    createdAt: text(item.createdAt),
    lastUsedAt: text(item.lastUsedAt)
  }
}

function normalizeOpenApiLog(item: Record<string, unknown>): OpenApiLog {
  return {
    id: text(item.id),
    userId: text(item.userId),
    appKey: text(item.appKey),
    path: text(item.path),
    status: text(item.status),
    message: text(item.message),
    createdAt: text(item.createdAt)
  }
}

function normalizeSupplier(item: Record<string, unknown>): Supplier {
  return {
    id: text(item.id),
    name: text(item.name, '未命名供应商'),
    baseUrl: text(item.baseUrl),
    appKey: text(item.appKey),
    appSecretMasked: text(item.appSecretMasked),
    balance: numberValue(item.balance),
    status: text(item.status, 'UNKNOWN'),
    remark: text(item.remark),
    lastSyncAt: text(item.lastSyncAt)
  }
}

function normalizeCard(item: Record<string, unknown>): GoodsCard {
  return {
    id: text(item.id),
    cardNo: text(item.cardNo),
    password: text(item.password ?? item.secret ?? item.preview),
    status: text(item.status),
    usedAt: text(item.deliveredAt),
    createdAt: text(item.createdAt ?? item.importedAt)
  }
}

function normalizeGoodsChannel(item: Record<string, unknown>): GoodsChannel {
  return {
    id: text(item.id),
    goodsId: text(item.goodsId),
    supplierId: text(item.supplierId),
    supplierName: text(item.supplierName, '未知供应商'),
    supplierGoodsId: text(item.supplierGoodsId),
    priority: numberValue(item.priority),
    timeoutSeconds: numberValue(item.timeoutSeconds),
    status: text(item.status, 'UNKNOWN'),
    createdAt: text(item.createdAt)
  }
}

function normalizeOrder(item: Record<string, unknown>): Order {
  const deliveryItems = Array.isArray(item.deliveryItems) ? item.deliveryItems.map((value) => text(value)) : []
  const channelAttempts = Array.isArray(item.channelAttempts)
    ? item.channelAttempts.map((attempt) => {
        const record = typeof attempt === 'object' && attempt !== null ? (attempt as Record<string, unknown>) : {}
        return {
          channelId: text(record.channelId),
          supplierId: text(record.supplierId),
          supplierName: text(record.supplierName, '-'),
          supplierGoodsId: text(record.supplierGoodsId, '-'),
          priority: numberValue(record.priority),
          status: text(record.status, 'UNKNOWN'),
          message: text(record.message),
          attemptedAt: text(record.attemptedAt)
        }
      })
    : []

  return {
    orderNo: text(item.orderNo),
    userId: text(item.userId),
    buyerAccount: text(item.buyerAccount),
    goodsId: text(item.goodsId),
    goodsName: text(item.goodsName, '未知商品'),
    amount: numberValue(item.amount ?? item.payAmount ?? item.totalAmount),
    unitPrice: numberValue(item.unitPrice),
    quantity: numberValue(item.quantity, 1),
    status: text(item.status, 'UNKNOWN'),
    paymentNo: text(item.paymentNo),
    payMethod: text(item.payMethod),
    deliveryType: text(item.deliveryType ?? item.goodsType, '-'),
    platform: text(item.platform),
    rechargeAccount: text(item.rechargeAccount),
    buyerRemark: text(item.buyerRemark),
    requestId: text(item.requestId),
    deliveryItems,
    channelAttempts,
    deliveryMessage: text(item.deliveryMessage),
    createdAt: text(item.createdAt),
    paidAt: text(item.paidAt),
    deliveredAt: text(item.deliveredAt)
  }
}
