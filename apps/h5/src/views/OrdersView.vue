<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshCw } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5Orders } from '../api/h5'
import { subscribeOrderEvents } from '../api/realtime'
import AppTabbar from '../components/AppTabbar.vue'
import type { H5Order } from '../types/h5'
import { formatOrderProcessingDuration } from '../utils/orderDuration'

const route = useRoute()
const orders = ref<H5Order[]>([])
const loading = ref(false)
const syncing = ref(false)
const lastSyncedAt = ref('')
const errorMessage = ref('')
const copyMessage = ref('')
const copyFallback = ref<{ label: string; content: string } | null>(null)
const fallbackTextarea = ref<HTMLTextAreaElement | null>(null)
const activeStatus = ref('ALL')
let refreshTimer: number | undefined
let durationTimer: number | undefined
let copyTimer: number | undefined
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
  durationTimer = window.setInterval(() => {
    orders.value = [...orders.value]
  }, 1000)
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type === 'ORDER_UPDATED') void loadOrders({ silent: true })
  })
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (durationTimer) window.clearInterval(durationTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
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

async function copyText(value: string | undefined, label: string) {
  const content = (value || '').trim()
  if (!content) return

  try {
    if (window.isSecureContext && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(content)
    } else {
      copyWithTextarea(content)
    }
    copyMessage.value = `${label}已复制`
    copyFallback.value = null
  } catch {
    showCopyFallback(label, content)
  }

  if (copyTimer) window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copyMessage.value = ''
  }, 1800)
}

function copyWithTextarea(content: string) {
  const textarea = document.createElement('textarea')
  textarea.value = content
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.width = '1px'
  textarea.style.height = '1px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus({ preventScroll: true })
  textarea.select()
  textarea.setSelectionRange(0, content.length)
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!copied) throw new Error('copy command failed')
}

function showCopyFallback(label: string, content: string) {
  copyFallback.value = { label, content }
  copyMessage.value = '当前浏览器限制自动复制，请长按下方内容复制。'
  void nextTick(() => {
    fallbackTextarea.value?.focus({ preventScroll: true })
    fallbackTextarea.value?.select()
    fallbackTextarea.value?.setSelectionRange(0, content.length)
  })
}

function closeCopyFallback() {
  copyFallback.value = null
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
    <section v-if="copyMessage" class="notice success" role="status">{{ copyMessage }}</section>
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
        <button class="copy-order-no" type="button" @click="copyText(order.orderNo, '订单号')">{{ order.orderNo }}</button>
        <strong>{{ formatStatus(order.status) }}</strong>
      </div>
      <h2>{{ order.goodsName }}</h2>
      <div class="order-meta">
        <span>数量 x{{ order.quantity }}</span>
        <span>{{ formatTime(order.createdAt) }}</span>
      </div>
      <div class="duration-line">
        <span>订单处理耗时</span>
        <strong>{{ formatOrderProcessingDuration(order) }}</strong>
      </div>
      <div class="account-line">
        <span>充值账号</span>
        <button type="button" :disabled="!order.rechargeAccount" @click="copyText(order.rechargeAccount, '充值账号')">
          {{ order.rechargeAccount || '-' }}
        </button>
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

    <Teleport to="body">
      <div v-if="copyFallback" class="copy-sheet" role="dialog" aria-modal="true" aria-label="手动复制">
        <div class="copy-sheet-card">
          <div class="copy-sheet-head">
            <div>
              <span>{{ copyFallback.label }}</span>
              <strong>长按复制</strong>
            </div>
            <button type="button" @click="closeCopyFallback">关闭</button>
          </div>
          <textarea
            ref="fallbackTextarea"
            readonly
            :value="copyFallback.content"
            aria-label="复制内容"
            @click="fallbackTextarea?.select()"
          />
          <p>如果没有自动复制成功，请长按上方内容，选择复制。</p>
        </div>
      </div>
    </Teleport>

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

.copy-order-no {
  min-width: 0;
  padding: 0;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  text-align: left;
  border: 0;
  background: transparent;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.account-line {
  margin-top: 10px;
  padding: 10px 11px;
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.duration-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding: 10px 11px;
  border-radius: 16px;
  color: rgba(255, 255, 255, 0.52);
  background: rgba(0, 255, 195, 0.055);
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  font-size: 12px;
}

.duration-line strong {
  color: #00ffc3;
  font-size: 13px;
}

.account-line span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.account-line button {
  min-width: 0;
  padding: 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
  font-weight: 650;
  text-align: left;
  border: 0;
  background: transparent;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-line button:disabled {
  color: rgba(255, 255, 255, 0.4);
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

.copy-sheet {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(0, 0, 0, 0.46);
  backdrop-filter: blur(14px);
}

.copy-sheet-card {
  width: min(100%, 420px);
  max-height: calc(100vh - 48px);
  padding: 16px;
  border-radius: 22px;
  background: rgba(20, 32, 52, 0.92);
  border: 0.5px solid rgba(255, 255, 255, 0.16);
  box-shadow: 0 22px 60px rgba(0, 0, 0, 0.34);
  overflow: auto;
}

.copy-sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.copy-sheet-head span,
.copy-sheet-card p {
  display: block;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.copy-sheet-head strong {
  display: block;
  margin-top: 2px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 17px;
}

.copy-sheet-head button {
  height: 34px;
  padding: 0 13px;
  color: #00ffc3;
  border: 0.5px solid rgba(0, 255, 195, 0.24);
  border-radius: 999px;
  background: rgba(0, 255, 195, 0.09);
}

.copy-sheet-card textarea {
  width: 100%;
  min-height: 74px;
  padding: 12px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 15px;
  line-height: 1.5;
  resize: none;
  border-radius: 16px;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.08);
}

.copy-sheet-card p {
  margin: 10px 0 0;
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
