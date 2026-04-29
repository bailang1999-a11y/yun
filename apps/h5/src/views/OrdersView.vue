<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshCw } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5Orders } from '../api/h5'
import { subscribeOrderEvents } from '../api/realtime'
import AppTabbar from '../components/AppTabbar.vue'
import type { H5Order } from '../types/h5'

const route = useRoute()
const orders = ref<H5Order[]>([])
const loading = ref(false)
const syncing = ref(false)
const lastSyncedAt = ref('')
const errorMessage = ref('')
const activeStatus = ref('ALL')
let refreshTimer: number | undefined
let unsubscribeRealtime: (() => void) | undefined

const activeOrderNo = computed(() => String(route.query.orderNo ?? ''))
const filteredOrders = computed(() => {
  if (activeStatus.value === 'ALL') return orders.value
  if (activeStatus.value === 'PROCESSING') {
    return orders.value.filter((order) => ['PROCURING', 'WAITING_MANUAL', 'PAID', 'DELIVERING'].includes(order.status))
  }
  return orders.value.filter((order) => order.status === activeStatus.value)
})

const statusTabs = [
  { label: '全部', value: 'ALL' },
  { label: '待付款', value: 'UNPAID' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已完成', value: 'DELIVERED' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '已取消', value: 'CANCELLED' }
]

const statusLabel: Record<string, string> = {
  CREATED: '待支付',
  PENDING_PAY: '待支付',
  UNPAID: '待支付',
  PAID: '已支付',
  DELIVERING: '发货中',
  PROCURING: '采购中',
  WAITING_MANUAL: '待人工',
  DELIVERED: '已发货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
  FAILED: '失败',
  REFUNDED: '已退款'
}

onMounted(() => {
  void loadOrders()
  refreshTimer = window.setInterval(() => {
    void loadOrders({ silent: true })
  }, 8000)
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type === 'ORDER_UPDATED') void loadOrders({ silent: true })
  })
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  unsubscribeRealtime?.()
})

async function loadOrders(options: { silent?: boolean } = {}) {
  if (loading.value || syncing.value) return
  if (options.silent) {
    syncing.value = true
  } else {
    loading.value = true
  }
  errorMessage.value = ''
  try {
    orders.value = await fetchH5Orders()
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch (error) {
    if (!options.silent) orders.value = []
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
    syncing.value = false
  }
}

function formatStatus(status: string) {
  return statusLabel[status] ?? status
}

function formatTime(value?: string) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <main class="page page-pad">
    <header class="page-head">
      <div>
        <p>Orders</p>
        <h1>我的订单</h1>
      </div>
      <button type="button" :disabled="loading || syncing" aria-label="刷新订单" @click="() => loadOrders()">
        <RefreshCw :class="{ spin: loading || syncing }" :size="18" aria-hidden="true" />
      </button>
    </header>

    <section class="live-strip" role="status" aria-live="polite">
      <span :class="{ pulse: syncing }" />
      {{ syncing ? '正在同步订单状态' : lastSyncedAt ? `状态已同步 ${lastSyncedAt}` : '订单状态自动同步中' }}
    </section>

    <section v-if="errorMessage" class="notice danger" role="alert">{{ errorMessage }}</section>
    <section v-if="activeOrderNo" class="notice success">订单 {{ activeOrderNo }} 已创建，可在列表中查看处理状态。</section>

    <nav class="status-tabs" aria-label="订单状态筛选">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        type="button"
        :class="{ active: activeStatus === tab.value }"
        @click="activeStatus = tab.value"
      >
        {{ tab.label }}
      </button>
    </nav>

    <section v-if="loading" class="empty">正在加载订单...</section>
    <section v-else-if="!filteredOrders.length" class="empty">暂无符合条件的订单。</section>

    <article
      v-for="order in filteredOrders"
      :key="order.orderNo"
      class="order-card"
      :class="{ active: order.orderNo === activeOrderNo, muted: ['CANCELLED', 'REFUNDED'].includes(order.status) }"
    >
      <div class="order-top">
        <span>{{ order.orderNo }}</span>
        <strong>{{ formatStatus(order.status) }}</strong>
      </div>
      <h2>{{ order.goodsName }}</h2>
      <div class="order-meta">
        <span>数量 x{{ order.quantity }}</span>
        <span>{{ formatTime(order.createdAt) }}</span>
      </div>
      <div class="order-foot">
        <span v-if="order.deliveryStatus">发货：{{ formatStatus(order.deliveryStatus) }}</span>
        <span v-else>等待发货信息</span>
        <strong>¥{{ order.totalAmount.toFixed(2) }}</strong>
      </div>
      <div class="order-actions">
        <RouterLink v-if="order.status === 'UNPAID'" :to="{ path: '/checkout/' + order.orderNo }">去支付</RouterLink>
        <RouterLink v-else :to="{ path: '/result/' + order.orderNo }">
          {{ ['CANCELLED', 'REFUNDED'].includes(order.status) ? '查看详情' : '查看进度' }}
        </RouterLink>
        <RouterLink v-if="order.goodsType === 'CARD' && order.status === 'DELIVERED'" :to="{ path: '/cards', query: { orderNo: order.orderNo } }">
          查看卡密
        </RouterLink>
      </div>
    </article>

    <AppTabbar />
  </main>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 14px;
}

.page-head p {
  margin: 0 0 2px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

h1 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 22px;
}

.page-head button {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: #00ffc3;
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  backdrop-filter: blur(20px);
}

.page-head button:disabled {
  opacity: 0.65;
}

.empty {
  padding: 28px 16px;
  color: rgba(255, 255, 255, 0.58);
  background: rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 22px;
  backdrop-filter: blur(32px) saturate(180%);
}

.notice {
  margin-bottom: 10px;
  padding: 12px;
  border-radius: 18px;
  font-size: 13px;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(24px);
}

.notice.danger {
  color: #ff8d86;
}

.notice.success {
  color: #00ffc3;
}

.live-strip {
  height: 34px;
  margin-bottom: 10px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(24px) saturate(180%);
}

.live-strip span {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #00ffc3;
  box-shadow: 0 0 14px rgba(0, 255, 195, 0.54);
}

.live-strip span.pulse {
  animation: live-pulse 0.9s ease-in-out infinite;
}

.status-tabs {
  display: flex;
  gap: 8px;
  margin: 0 0 12px;
  padding: 8px;
  overflow-x: auto;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(28px) saturate(180%);
}

.status-tabs button {
  flex: 0 0 auto;
  height: 34px;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.58);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.04);
}

.status-tabs button.active {
  color: #fff;
  border-color: rgba(0, 255, 195, 0.3);
  background: rgba(0, 255, 195, 0.12);
  box-shadow: 0 0 24px rgba(0, 255, 195, 0.14);
}

.order-card {
  position: relative;
  padding: 14px;
  margin-bottom: 10px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 24px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15);
  backdrop-filter: blur(40px) saturate(180%);
}

.order-card.active {
  border-color: rgba(0, 255, 195, 0.35);
  box-shadow: 0 0 42px rgba(0, 255, 195, 0.14);
}

.order-card.muted {
  filter: saturate(0.65);
  opacity: 0.82;
}

.order-top,
.order-meta,
.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.order-top span,
.order-meta,
.order-foot span {
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.order-top strong {
  color: #00ffc3;
  font-size: 13px;
}

.order-card.muted .order-top strong {
  color: rgba(255, 255, 255, 0.5);
}

.order-card h2 {
  margin: 10px 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 16px;
  font-weight: 500;
}

.order-foot {
  margin-top: 12px;
}

.order-foot strong {
  color: #fff;
  font-size: 18px;
}

.order-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 12px;
}

.order-actions a {
  height: 34px;
  color: rgba(255, 255, 255, 0.82);
  text-align: center;
  text-decoration: none;
  line-height: 34px;
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
}

.order-actions a:first-child:last-child {
  grid-column: 1 / -1;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
