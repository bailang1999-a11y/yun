import type { H5Order } from '../types/h5'

const terminalStatuses = new Set(['DELIVERED', 'COMPLETED', 'FAILED', 'REFUNDED', 'CANCELLED', 'CLOSED'])

export function formatOrderProcessingDuration(order?: Pick<H5Order, 'createdAt' | 'deliveredAt' | 'status'> | null) {
  if (!order?.createdAt) return '-'
  const start = new Date(order.createdAt).getTime()
  const status = (order.status || '').toUpperCase()
  const end = terminalStatuses.has(status) && order.deliveredAt ? new Date(order.deliveredAt).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '-'

  const totalSeconds = Math.floor((end - start) / 1000)
  const days = Math.floor(totalSeconds / 86400)
  const hours = Math.floor((totalSeconds % 86400) / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60

  if (days > 0) return `${days}天 ${hours}小时 ${minutes}分`
  if (hours > 0) return `${hours}小时 ${minutes}分 ${seconds}秒`
  if (minutes > 0) return `${minutes}分 ${seconds}秒`
  return `${seconds}秒`
}
