import { defineStore } from 'pinia'
import { fetchOrders, fetchSuppliers } from '../api/admin'
import type { Order, Supplier } from '../types/operations'

function money(value: number) {
  return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function numberValue(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function isToday(value?: string) {
  if (!value) return false
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return false
  const now = new Date()
  return date.toDateString() === now.toDateString()
}

export const useDashboardStore = defineStore('dashboard', {
  state: () => ({
    loading: false,
    syncing: false,
    lastSyncedAt: '',
    orders: [] as Order[],
    suppliers: [] as Supplier[]
  }),
  getters: {
    todayOrders(state) {
      return state.orders.filter((order) => isToday(order.createdAt))
    },
    metrics(): Array<{ label: string; value: string; trend: string; tone: string }> {
      const todayOrders = this.todayOrders
      const paidOrders = todayOrders.filter((order) => ['DELIVERED', 'PROCURING', 'WAITING_MANUAL'].includes(order.status))
      const successOrders = todayOrders.filter((order) => order.status === 'DELIVERED')
      const exceptionOrders = todayOrders.filter((order) => ['FAILED', 'REFUNDING', 'WAITING_MANUAL'].includes(order.status))
      const revenue = paidOrders.reduce((sum, order) => sum + numberValue(order.amount), 0)
      const successRate = todayOrders.length ? (successOrders.length / todayOrders.length) * 100 : 0

      return [
        { label: '今日销售额', value: money(revenue), trend: `${paidOrders.length} 笔已支付`, tone: 'success' },
        { label: '订单数', value: String(todayOrders.length), trend: '今日创建', tone: 'normal' },
        { label: '成功率', value: `${successRate.toFixed(1)}%`, trend: `${successOrders.length} 笔完成`, tone: 'success' },
        { label: '异常待处理', value: String(exceptionOrders.length), trend: exceptionOrders.length ? '需处理' : '稳定', tone: exceptionOrders.length ? 'warn' : 'success' }
      ]
    },
    recentOrders(state) {
      const statusLabel: Record<string, string> = {
        UNPAID: '待支付',
        PROCURING: '采购中',
        WAITING_MANUAL: '待人工',
        DELIVERED: '已发货',
        FAILED: '失败',
        REFUNDED: '已退款',
        CANCELLED: '已取消'
      }
      return state.orders.slice(0, 5).map((order) => ({
        orderNo: order.orderNo,
        goods: order.goodsName || '-',
        status: statusLabel[order.status] ?? order.status,
        amount: money(numberValue(order.amount))
      }))
    },
    supplierFeeds(state) {
      return state.suppliers.map((supplier) => {
        const balance = numberValue(supplier.balance)
        const tone = supplier.status !== 'ENABLED' ? 'warn' : balance < 300 ? 'warn' : 'success'
        const message =
          supplier.status !== 'ENABLED'
            ? '供应商已停用，路由器会跳过该渠道。'
            : balance < 300
              ? `余额仅 ${money(balance)}，建议尽快补款。`
              : `余额 ${money(balance)}，连接状态正常。`

        return {
          id: supplier.id,
          name: supplier.name,
          message,
          tone,
          lastSyncAt: supplier.lastSyncAt
        }
      })
    }
  },
  actions: {
    async loadDashboard(options: { silent?: boolean } = {}) {
      if (this.loading || this.syncing) return
      if (options.silent) {
        this.syncing = true
      } else {
        this.loading = true
      }
      try {
        const [orders, suppliers] = await Promise.all([fetchOrders(), fetchSuppliers()])
        this.orders = orders
        this.suppliers = suppliers
        this.lastSyncedAt = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      } finally {
        this.loading = false
        this.syncing = false
      }
    }
  }
})
