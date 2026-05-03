type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const orderStatusOptions = [
  { label: '待支付', value: 'UNPAID' },
  { label: '采购中', value: 'PROCURING' },
  { label: '待人工', value: 'WAITING_MANUAL' },
  { label: '已发货', value: 'DELIVERED' },
  { label: '失败', value: 'FAILED' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '已取消', value: 'CANCELLED' }
]

const orderStatusLabels: Record<string, string> = {
  UNPAID: '待支付',
  PROCURING: '采购中',
  WAITING_MANUAL: '待人工处理',
  DELIVERED: '已发货',
  FAILED: '处理失败',
  REFUNDED: '已退款',
  CANCELLED: '已取消'
}

const orderStatusTypes: Record<string, TagType> = {
  UNPAID: 'warning',
  PROCURING: 'primary',
  WAITING_MANUAL: 'warning',
  DELIVERED: 'success',
  FAILED: 'danger',
  REFUNDED: 'info',
  CANCELLED: 'info'
}

const deliveryTypeLabels: Record<string, string> = {
  CARD: '卡密',
  DIRECT: '直充',
  MANUAL: '人工代充',
  AUTO: '自动发货'
}

const paymentMethodLabels: Record<string, string> = {
  balance: '余额支付',
  mock: '模拟支付',
  wechat: '微信支付',
  alipay: '支付宝',
  WECHAT: '微信支付',
  ALIPAY: '支付宝',
  MOCK: '模拟支付'
}

const userStatusLabels: Record<string, string> = {
  NORMAL: '正常',
  FROZEN: '冻结',
  DISABLED: '停用',
  ENABLED: '启用'
}

const realNameTypeLabels: Record<string, string> = {
  PERSONAL: '个人',
  SUBJECT: '主体',
  NONE: '未实名'
}

const verificationStatusLabels: Record<string, string> = {
  VERIFIED: '已实名',
  PENDING: '待审核',
  REJECTED: '未通过',
  UNVERIFIED: '未实名'
}

export function formatMoney(value?: number | string, options: { currency?: boolean; fallback?: string } = {}) {
  const { currency = true, fallback = '-' } = options
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) return fallback
  return `${currency ? '¥' : ''}${numberValue.toFixed(2)}`
}

export function formatDateTime(value?: string, options: { compact?: boolean } = {}) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  if (options.compact) {
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  const pad = (numberValue: number) => String(numberValue).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatDurationFromOrder(createdAt?: string, endAt?: string) {
  if (!createdAt) return '-'
  const start = new Date(createdAt).getTime()
  const end = endAt ? new Date(endAt).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '-'

  const seconds = Math.floor((end - start) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}

export function formatOrderStatus(value?: string) {
  return orderStatusLabels[value || ''] || value || '-'
}

export function orderStatusTagType(value?: string): TagType {
  return orderStatusTypes[value || ''] || 'info'
}

export function formatDeliveryType(value?: string) {
  return deliveryTypeLabels[value || ''] || value || '-'
}

export function formatPaymentMethod(value?: string) {
  return paymentMethodLabels[value || ''] || value || '-'
}

export function formatOrderSource(source?: string, hasRequestId = false, platform?: string) {
  if (/api|member-api/i.test(source || '') || hasRequestId) return 'API 下单'
  if (/h5|web|front|pc/i.test(source || '') || platform) return '前台下单'
  return '-'
}

export function formatUserStatus(value?: string) {
  return userStatusLabels[value || ''] || value || '-'
}

export function userStatusTagType(value?: string): TagType {
  if (value === 'NORMAL' || value === 'ENABLED') return 'success'
  if (value === 'FROZEN') return 'warning'
  if (value === 'DISABLED') return 'info'
  return 'info'
}

export function formatRealNameType(value?: string) {
  return realNameTypeLabels[value || ''] || value || '未实名'
}

export function formatVerificationStatus(value?: string) {
  return verificationStatusLabels[value || ''] || value || '未实名'
}

export function verificationStatusTagType(value?: string): TagType {
  if (value === 'VERIFIED') return 'success'
  if (value === 'PENDING') return 'warning'
  if (value === 'REJECTED') return 'danger'
  return 'info'
}

export function maskCertificate(value?: string) {
  if (!value) return '-'
  if (value.includes('*')) return value
  if (value.length <= 8) return value
  return `${value.slice(0, 4)}****${value.slice(-4)}`
}
