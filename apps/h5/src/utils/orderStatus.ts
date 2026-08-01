import {
  BadgeCheck,
  CircleCheckBig,
  CircleDollarSign,
  CircleQuestionMark,
  CircleX,
  Clock3,
  Hand,
  LoaderCircle,
  LockKeyhole,
  PackageSearch,
  ReceiptText,
  RotateCcw,
  TriangleAlert,
  Truck,
  type LucideIcon
} from 'lucide-vue-next'

export type OrderStatus =
  | 'CREATED'
  | 'PENDING_PAY'
  | 'UNPAID'
  | 'PAYING'
  | 'PAID'
  | 'PROCURING'
  | 'WAITING_MANUAL'
  | 'DELIVERING'
  | 'DELIVERED'
  | 'COMPLETED'
  | 'REFUNDING'
  | 'REFUNDED'
  | 'CANCELLED'
  | 'CLOSED'
  | 'FAILED'
  | 'UNKNOWN'

export type OrderStatusTone = 'pending' | 'processing' | 'success' | 'refund' | 'cancel' | 'error' | 'neutral'

export interface OrderStatusMeta {
  status: OrderStatus
  label: string
  tone: OrderStatusTone
  resultTitle: string
  resultCopy: string
  terminal: boolean
  icon: LucideIcon
}

const orderStatusMeta = {
  CREATED: {
    status: 'CREATED',
    label: '已创建',
    tone: 'pending',
    resultTitle: '订单已创建',
    resultCopy: '订单已创建，等待进入支付流程。',
    terminal: false,
    icon: Clock3
  },
  PENDING_PAY: {
    status: 'PENDING_PAY',
    label: '待支付',
    tone: 'pending',
    resultTitle: '订单待支付',
    resultCopy: '请返回收银台完成支付。',
    terminal: false,
    icon: CircleDollarSign
  },
  UNPAID: {
    status: 'UNPAID',
    label: '待支付',
    tone: 'pending',
    resultTitle: '订单待支付',
    resultCopy: '请返回收银台完成支付。',
    terminal: false,
    icon: CircleDollarSign
  },
  PAYING: {
    status: 'PAYING',
    label: '支付确认中',
    tone: 'processing',
    resultTitle: '正在确认支付',
    resultCopy: '支付结果正在确认，请稍候。',
    terminal: false,
    icon: LoaderCircle
  },
  PAID: {
    status: 'PAID',
    label: '已支付',
    tone: 'processing',
    resultTitle: '支付成功',
    resultCopy: '订单已支付，正在进入处理流程。',
    terminal: false,
    icon: BadgeCheck
  },
  PROCURING: {
    status: 'PROCURING',
    label: '采购中',
    tone: 'processing',
    resultTitle: '订单采购中',
    resultCopy: '已开始向上游采购，请耐心等待。',
    terminal: false,
    icon: PackageSearch
  },
  WAITING_MANUAL: {
    status: 'WAITING_MANUAL',
    label: '待人工处理',
    tone: 'processing',
    resultTitle: '等待人工处理',
    resultCopy: '订单已进入人工处理队列。',
    terminal: false,
    icon: Hand
  },
  DELIVERING: {
    status: 'DELIVERING',
    label: '发货中',
    tone: 'processing',
    resultTitle: '正在发货',
    resultCopy: '商品正在交付，请稍候。',
    terminal: false,
    icon: Truck
  },
  DELIVERED: {
    status: 'DELIVERED',
    label: '已发货',
    tone: 'success',
    resultTitle: '订单已完成',
    resultCopy: '商品已完成交付，请查收。',
    terminal: true,
    icon: CircleCheckBig
  },
  COMPLETED: {
    status: 'COMPLETED',
    label: '已完成',
    tone: 'success',
    resultTitle: '订单已完成',
    resultCopy: '本次订单已处理完成。',
    terminal: true,
    icon: CircleCheckBig
  },
  REFUNDING: {
    status: 'REFUNDING',
    label: '退款中',
    tone: 'refund',
    resultTitle: '正在退款',
    resultCopy: '退款申请正在处理，请耐心等待。',
    terminal: false,
    icon: RotateCcw
  },
  REFUNDED: {
    status: 'REFUNDED',
    label: '已退款',
    tone: 'refund',
    resultTitle: '订单已退款',
    resultCopy: '退款已处理，请留意原支付渠道到账。',
    terminal: true,
    icon: ReceiptText
  },
  CANCELLED: {
    status: 'CANCELLED',
    label: '已取消',
    tone: 'cancel',
    resultTitle: '订单已取消',
    resultCopy: '订单已取消，不会继续处理。',
    terminal: true,
    icon: CircleX
  },
  CLOSED: {
    status: 'CLOSED',
    label: '已关闭',
    tone: 'cancel',
    resultTitle: '订单已关闭',
    resultCopy: '订单已关闭，不会继续处理。',
    terminal: true,
    icon: LockKeyhole
  },
  FAILED: {
    status: 'FAILED',
    label: '处理失败',
    tone: 'error',
    resultTitle: '订单处理失败',
    resultCopy: '请查看订单详情或联系客服处理。',
    terminal: true,
    icon: TriangleAlert
  },
  UNKNOWN: {
    status: 'UNKNOWN',
    label: '未知状态',
    tone: 'neutral',
    resultTitle: '正在确认订单',
    resultCopy: '订单状态暂未同步，请稍后刷新查看。',
    terminal: false,
    icon: CircleQuestionMark
  }
} satisfies Record<OrderStatus, OrderStatusMeta>

const processingStatuses = new Set<OrderStatus>([
  'PAYING',
  'PAID',
  'PROCURING',
  'WAITING_MANUAL',
  'DELIVERING',
  'REFUNDING'
])

export function getOrderStatusMeta(status?: string | null): OrderStatusMeta {
  const normalized = String(status || '').trim().toUpperCase() as OrderStatus
  return orderStatusMeta[normalized] ?? orderStatusMeta.UNKNOWN
}

export function getOrderStatusPresentation(status?: string | null) {
  const meta = getOrderStatusMeta(status)
  return { ...meta, isTerminal: meta.terminal }
}

export function isOrderProcessing(status?: string | null) {
  return processingStatuses.has(getOrderStatusMeta(status).status)
}
