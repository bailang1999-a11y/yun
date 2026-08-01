<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Check, ChevronDown, Copy, RefreshCw, Sparkles, Ticket, UserRound, Zap } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5GoodsDetail, fetchH5Orders } from '../api/h5'
import { subscribeOrderEvents } from '../api/realtime'
import AppTabbar from '../components/AppTabbar.vue'
import type { GoodsCard, GoodsType, H5Order } from '../types/h5'
import { formatMoney } from '../utils/formatters'
import { formatOrderProcessingDuration } from '../utils/orderDuration'
import { getOrderStatusPresentation } from '../utils/orderStatus'
import { useModalFocus } from '../utils/modalFocus'

const route = useRoute()
const orders = ref<H5Order[]>([])
const goodsById = ref<Record<string, GoodsCard>>({})
const loading = ref(false)
const syncing = ref(false)
const errorMessage = ref('')
const copyMessage = ref('')
const copyFallback = ref<{ label: string; content: string } | null>(null)
const fallbackTextarea = ref<HTMLTextAreaElement | null>(null)
const copyFallbackDialogRef = ref<HTMLElement | null>(null)
const statusMoreRef = ref<HTMLElement | null>(null)
const statusMenuRef = ref<HTMLElement | null>(null)
const moreButtonRef = ref<HTMLButtonElement | null>(null)
const activeStatus = ref('ALL')
const statusMenuOpen = ref(false)
const focusedMenuStatus = ref('ALL')
let refreshTimer: number | undefined
let durationTimer: number | undefined
let copyTimer: number | undefined
let unsubscribeRealtime: (() => void) | undefined
const loadingGoodsIds = new Set<string>()
const unavailableGoodsIds = new Set<string>()

const goodsTypePresentation: Record<GoodsType, { label: string; icon: typeof Ticket }> = {
  CARD: { label: '卡密', icon: Ticket },
  DIRECT: { label: '直充', icon: Zap },
  MANUAL: { label: '代充', icon: UserRound }
}

const activeOrderNo = computed(() => String(route.query.orderNo ?? ''))
useModalFocus(computed(() => Boolean(copyFallback.value)), copyFallbackDialogRef, closeCopyFallback)
const filteredOrders = computed(() => {
  if (activeStatus.value === 'ALL') return orders.value
  if (activeStatus.value === 'PROCESSING') {
    return orders.value.filter((order) => getOrderStatusPresentation(order.status).tone === 'processing')
  }
  if (activeStatus.value === 'UNPAID') {
    return orders.value.filter((order) => ['CREATED', 'PENDING_PAY', 'UNPAID'].includes(order.status))
  }
  if (activeStatus.value === 'DELIVERED') {
    return orders.value.filter((order) => ['DELIVERED', 'COMPLETED'].includes(order.status))
  }
  return orders.value.filter((order) => order.status === activeStatus.value)
})

const mainStatusTabs = [
  { label: '全部订单', value: 'ALL' },
  { label: '进行中', value: 'PROCESSING' },
  { label: '已完成', value: 'DELIVERED' }
]
const statusMenuItems = [
  { label: '全部订单', value: 'ALL' },
  { label: '待付款', value: 'UNPAID' },
  { label: '支付中', value: 'PAYING' },
  { label: '已支付', value: 'PAID' },
  { label: '采购中', value: 'PROCURING' },
  { label: '待人工', value: 'WAITING_MANUAL' },
  { label: '发货中', value: 'DELIVERING' },
  { label: '已完成', value: 'DELIVERED' },
  { label: '退款中', value: 'REFUNDING' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '处理失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '已关闭', value: 'CLOSED' }
]
const isMoreActive = computed(() => !['ALL', 'PROCESSING', 'DELIVERED'].includes(activeStatus.value))

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
  document.addEventListener('pointerdown', closeStatusMenuOnOutsidePointer)
  document.addEventListener('keydown', closeStatusMenuOnEscape)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (durationTimer) window.clearInterval(durationTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
  unsubscribeRealtime?.()
  document.removeEventListener('pointerdown', closeStatusMenuOnOutsidePointer)
  document.removeEventListener('keydown', closeStatusMenuOnEscape)
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
    const nextOrders = await fetchH5Orders()
    orders.value = nextOrders
    void loadGoodsMetadata(nextOrders)
  } catch (error) {
    if (!options.silent) orders.value = []
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
    syncing.value = false
  }
}

async function loadGoodsMetadata(nextOrders: H5Order[]) {
  const missingOrders = nextOrders.filter((order) => (
    order.goodsId
      && !goodsById.value[order.goodsId]
      && !loadingGoodsIds.has(order.goodsId)
      && !unavailableGoodsIds.has(order.goodsId)
  ))
  if (!missingOrders.length) return

  await Promise.allSettled(missingOrders.map(async (order) => {
    const goodsId = order.goodsId as string
    loadingGoodsIds.add(goodsId)
    try {
      const goods = await fetchH5GoodsDetail(goodsId, [], order.platform || 'h5')
      goodsById.value = { ...goodsById.value, [goodsId]: goods }
    } catch {
      unavailableGoodsIds.add(goodsId)
    } finally {
      loadingGoodsIds.delete(goodsId)
    }
  }))
}

function orderGoods(order: H5Order) {
  return order.goodsId ? goodsById.value[order.goodsId] : undefined
}

function orderTags(order: H5Order) {
  const goods = orderGoods(order)
  if (!goods) return []
  return [
    goods.benefitBrand ? { label: goods.benefitBrand, tone: 'brand' } : null,
    goods.benefitType ? { label: goods.benefitType, tone: 'benefit' } : null,
    ...(goods.benefitDurations || []).slice(0, 2).map((label) => ({ label, tone: 'duration' }))
  ].filter((tag): tag is { label: string; tone: string } => Boolean(tag))
}

function goodsType(order: H5Order) {
  return goodsTypePresentation[order.goodsType || orderGoods(order)?.type || 'CARD']
}

function selectStatus(status: string, restoreFocus = false) {
  activeStatus.value = status
  statusMenuOpen.value = false
  if (restoreFocus) void nextTick(() => moreButtonRef.value?.focus())
}

function toggleStatusMenu() {
  statusMenuOpen.value = !statusMenuOpen.value
  if (!statusMenuOpen.value) return
  focusedMenuStatus.value = activeStatus.value
  void nextTick(() => {
    statusMenuRef.value?.querySelector<HTMLElement>('[tabindex="0"]')?.focus()
  })
}

function closeStatusMenuOnOutsidePointer(event: PointerEvent) {
  if (!statusMenuOpen.value || statusMoreRef.value?.contains(event.target as Node)) return
  statusMenuOpen.value = false
}

function closeStatusMenuOnEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !statusMenuOpen.value) return
  statusMenuOpen.value = false
  event.preventDefault()
  moreButtonRef.value?.focus()
}

function handleStatusMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Tab') {
    statusMenuOpen.value = false
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = Array.from(statusMenuRef.value?.querySelectorAll<HTMLElement>('[role="menuitemradio"]') ?? [])
  if (!items.length) return

  event.preventDefault()
  const currentIndex = items.indexOf(document.activeElement as HTMLElement)
  let nextIndex = 0
  if (event.key === 'End') nextIndex = items.length - 1
  else if (event.key === 'ArrowDown') nextIndex = (currentIndex + 1) % items.length
  else if (event.key === 'ArrowUp') nextIndex = (currentIndex - 1 + items.length) % items.length
  focusedMenuStatus.value = statusMenuItems[nextIndex]?.value ?? activeStatus.value
  void nextTick(() => items[nextIndex]?.focus())
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

    <section v-if="errorMessage" class="notice danger" role="alert">{{ errorMessage }}</section>
    <Transition name="copy-toast">
      <section v-if="copyMessage" class="copy-toast" role="status">
        <Check :size="16" aria-hidden="true" />
        {{ copyMessage }}
      </section>
    </Transition>
    <section v-if="activeOrderNo" class="notice success">订单 {{ activeOrderNo }} 已创建，可在列表中查看处理状态。</section>

    <nav class="status-tabs" aria-label="订单状态筛选">
      <button
        v-for="tab in mainStatusTabs"
        :key="tab.value"
        type="button"
        :class="{ active: activeStatus === tab.value }"
        :aria-pressed="activeStatus === tab.value"
        @click="selectStatus(tab.value)"
      >
        {{ tab.label }}
      </button>
      <div ref="statusMoreRef" class="status-more">
        <button
          ref="moreButtonRef"
          type="button"
          :class="{ active: isMoreActive }"
          :aria-expanded="statusMenuOpen"
          aria-controls="order-status-menu"
          aria-haspopup="menu"
          @click="toggleStatusMenu"
        >
          <span>更多</span>
          <ChevronDown :size="14" aria-hidden="true" />
        </button>
        <div
          v-if="statusMenuOpen"
          id="order-status-menu"
          ref="statusMenuRef"
          class="status-menu"
          role="menu"
          aria-label="更多订单状态"
          @keydown="handleStatusMenuKeydown"
        >
          <button
            v-for="item in statusMenuItems"
            :key="item.value"
            type="button"
            role="menuitemradio"
            :aria-checked="activeStatus === item.value"
            :tabindex="focusedMenuStatus === item.value ? 0 : -1"
            @click="selectStatus(item.value, true)"
          >
            <span>{{ item.label }}</span>
            <Check v-if="activeStatus === item.value" :size="16" aria-hidden="true" />
          </button>
        </div>
      </div>
    </nav>

    <section v-if="loading" class="empty">正在加载订单...</section>
    <section v-else-if="!filteredOrders.length" class="empty">暂无符合条件的订单。</section>

    <section class="order-list">
      <article
        v-for="order in filteredOrders"
        :key="order.orderNo"
        class="order-card"
        :data-order-status="order.status"
        :data-goods-id="order.goodsId"
        :data-order-platform="order.platform"
        :class="[
          `status-${getOrderStatusPresentation(order.status).tone}`,
          { active: order.orderNo === activeOrderNo }
        ]"
      >
        <div class="order-top">
          <button
            class="copy-value copy-order-no"
            type="button"
            :aria-label="`复制订单号 ${order.orderNo}`"
            @click="copyText(order.orderNo, '订单号')"
          >
            <span>{{ order.orderNo }}</span>
            <Copy :size="14" aria-hidden="true" />
          </button>
          <strong class="status-badge" :data-tone="getOrderStatusPresentation(order.status).tone">
            <component :is="getOrderStatusPresentation(order.status).icon" :size="14" aria-hidden="true" />
            {{ getOrderStatusPresentation(order.status).label }}
          </strong>
        </div>

        <div class="goods-title-line">
          <h2>{{ order.goodsName }}</h2>
          <span class="goods-type-badge" :data-type="order.goodsType || orderGoods(order)?.type || 'CARD'">
            <component :is="goodsType(order).icon" :size="13" aria-hidden="true" />
            {{ goodsType(order).label }}
          </span>
        </div>

        <div v-if="orderTags(order).length" class="order-tags" aria-label="商品权益标签">
          <span v-for="tag in orderTags(order)" :key="`${tag.tone}-${tag.label}`" :data-tone="tag.tone">
            <Sparkles v-if="tag.tone === 'benefit'" :size="11" aria-hidden="true" />
            {{ tag.label }}
          </span>
        </div>

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
          <button
            class="copy-value"
            type="button"
            :disabled="!order.rechargeAccount"
            :aria-label="order.rechargeAccount ? `复制充值账号 ${order.rechargeAccount}` : '暂无充值账号'"
            @click="copyText(order.rechargeAccount, '充值账号')"
          >
            <span>{{ order.rechargeAccount || '-' }}</span>
            <Copy v-if="order.rechargeAccount" :size="14" aria-hidden="true" />
          </button>
        </div>
        <div class="order-foot">
          <span v-if="order.deliveryStatus">发货：{{ getOrderStatusPresentation(order.deliveryStatus).label }}</span>
          <span v-else>等待发货信息</span>
          <strong>¥{{ formatMoney(order.totalAmount) }}</strong>
        </div>
        <div class="order-actions">
          <RouterLink v-if="order.status === 'UNPAID'" :to="{ path: '/checkout/' + order.orderNo }">去支付</RouterLink>
          <RouterLink v-else :to="{ path: '/result/' + order.orderNo }">
            {{ getOrderStatusPresentation(order.status).isTerminal ? '查看详情' : '查看进度' }}
          </RouterLink>
          <RouterLink v-if="order.goodsType === 'CARD' && order.status === 'DELIVERED'" :to="{ path: '/cards', query: { orderNo: order.orderNo } }">
            查看卡密
          </RouterLink>
        </div>
      </article>
    </section>

    <Teleport to="body">
      <div
        v-if="copyFallback"
        ref="copyFallbackDialogRef"
        class="copy-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="copy-fallback-title"
        tabindex="-1"
      >
        <div class="copy-sheet-card">
          <div class="copy-sheet-head">
            <div>
              <span>{{ copyFallback.label }}</span>
              <strong id="copy-fallback-title">长按复制</strong>
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

.copy-toast {
  position: fixed;
  z-index: 70;
  top: max(14px, env(safe-area-inset-top));
  left: 50%;
  min-height: 42px;
  padding: 0 15px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #c8fff1;
  border: 0.5px solid rgba(0, 255, 195, 0.32);
  border-radius: 999px;
  background: rgba(10, 35, 34, 0.9);
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.34), 0 0 24px rgba(0, 255, 195, 0.12);
  backdrop-filter: blur(24px) saturate(180%);
  transform: translateX(-50%);
}

.copy-toast-enter-active,
.copy-toast-leave-active {
  transition: opacity 180ms cubic-bezier(0.22, 1, 0.36, 1), transform 180ms cubic-bezier(0.22, 1, 0.36, 1);
}

.copy-toast-enter-from,
.copy-toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px) scale(0.96);
}

.status-tabs {
  position: relative;
  z-index: 10;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin: 0 0 12px;
  padding: 6px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(28px) saturate(180%);
}

.status-tabs > button,
.status-more > button {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  padding: 7px 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.64);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.25;
  white-space: nowrap;
  transition: transform 180ms cubic-bezier(0.22, 1, 0.36, 1), color 180ms ease, background 180ms ease, border-color 180ms ease;
}

.status-tabs > button:active,
.status-more > button:active {
  transform: scale(0.96);
}

.status-tabs > button span,
.status-more > button span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.status-tabs > button.active,
.status-more > button.active {
  color: #effffb;
  border-color: rgba(0, 255, 195, 0.3);
  background: rgba(0, 255, 195, 0.12);
  box-shadow: inset 0 0 0 0.5px rgba(0, 255, 195, 0.1);
}

.status-more {
  position: relative;
  min-width: 0;
}

.status-more > button svg {
  flex: 0 0 auto;
  transition: transform 180ms ease;
}

.status-more > button[aria-expanded='true'] svg {
  transform: rotate(180deg);
}

.status-menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + 8px);
  right: 0;
  width: min(196px, calc(100vw - 32px));
  padding: 6px;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(15, 18, 24, 0.92);
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.38);
  backdrop-filter: blur(28px) saturate(180%);
}

.status-menu button {
  width: 100%;
  min-height: 44px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: rgba(255, 255, 255, 0.72);
  border: 0;
  border-radius: 9px;
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
}

.status-menu button:hover,
.status-menu button:focus-visible {
  color: #effffb;
  background: rgba(255, 255, 255, 0.08);
}

.status-menu button:focus-visible {
  outline: 2px solid rgba(115, 248, 215, 0.72);
  outline-offset: -2px;
}

.status-menu button[aria-checked='true'] {
  color: #73f8d7;
  background: rgba(0, 255, 195, 0.1);
}

.status-menu button svg {
  flex: 0 0 auto;
}

.order-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.order-card {
  --status-rgb: 88, 166, 255;
  position: relative;
  padding: 14px;
  overflow: hidden;
  background:
    radial-gradient(circle at 92% 5%, rgba(var(--status-rgb), 0.12), transparent 34%),
    rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(var(--status-rgb), 0.24);
  border-radius: 24px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(40px) saturate(180%);
}

.order-card::after {
  content: '';
  position: absolute;
  top: 0;
  right: 18px;
  width: 76px;
  height: 1px;
  pointer-events: none;
  background: rgba(var(--status-rgb), 0.72);
  box-shadow: 0 0 18px rgba(var(--status-rgb), 0.52);
}

.order-card.status-success {
  --status-rgb: 0, 255, 195;
}

.order-card.status-processing {
  --status-rgb: 88, 166, 255;
}

.order-card.status-pending {
  --status-rgb: 255, 171, 0;
}

.order-card.status-error {
  --status-rgb: 255, 59, 48;
}

.order-card.status-refund {
  --status-rgb: 56, 189, 248;
}

.order-card.status-cancel,
.order-card.status-neutral {
  --status-rgb: 148, 163, 184;
  filter: saturate(0.72);
}

.order-card.active {
  border-color: rgba(0, 255, 195, 0.35);
  box-shadow: 0 0 42px rgba(0, 255, 195, 0.14);
}

.order-top,
.order-meta,
.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.order-meta,
.order-foot span {
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.copy-value {
  min-width: 0;
  padding: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  text-align: left;
  border: 0;
  background: transparent;
}

.copy-value span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copy-value svg {
  flex: 0 0 auto;
  color: rgba(255, 255, 255, 0.58);
}

.copy-value:not(:disabled):active {
  transform: scale(0.97);
}

.status-badge {
  min-height: 28px;
  padding: 0 9px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  color: rgb(var(--status-rgb));
  font-size: 12px;
  border: 0.5px solid rgba(var(--status-rgb), 0.28);
  border-radius: 999px;
  background: rgba(var(--status-rgb), 0.1);
  box-shadow: 0 0 18px rgba(var(--status-rgb), 0.08);
}

.status-badge[data-tone='processing'] {
  animation: status-soft-pulse 1.4s ease-in-out infinite;
}

.order-card[data-order-status='PAYING'] .status-badge svg,
.order-card[data-order-status='REFUNDING'] .status-badge svg {
  animation: status-turn 1.4s linear infinite;
}

.status-badge[data-tone='success'] svg {
  animation: status-confirm 420ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.status-badge[data-tone='error'] {
  animation: status-shake 240ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.goods-title-line {
  margin: 10px 0 7px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.order-card h2 {
  min-width: 0;
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 16px;
  font-weight: 650;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.goods-type-badge {
  min-height: 24px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  color: #dbeafe;
  border: 0.5px solid rgba(88, 166, 255, 0.25);
  border-radius: 6px;
  background: rgba(88, 166, 255, 0.11);
  font-size: 11px;
  font-weight: 700;
}

.goods-type-badge[data-type='CARD'] {
  color: #d9fff6;
  border-color: rgba(0, 255, 195, 0.24);
  background: rgba(0, 255, 195, 0.09);
}

.goods-type-badge[data-type='MANUAL'] {
  color: #fff1ca;
  border-color: rgba(255, 171, 0, 0.27);
  background: rgba(255, 171, 0, 0.1);
}

.order-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 9px;
}

.order-tags span {
  min-height: 21px;
  max-width: 120px;
  padding: 2px 7px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.72);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  font-size: 10px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-tags span[data-tone='brand'] {
  color: #d6f0ff;
  border-color: rgba(84, 180, 255, 0.28);
  background: rgba(46, 152, 235, 0.12);
}

.order-tags span[data-tone='benefit'] {
  color: #e4ddff;
  border-color: rgba(148, 121, 255, 0.28);
  background: rgba(122, 92, 255, 0.13);
}

.order-tags span[data-tone='duration'] {
  color: #c9fff4;
  border-color: rgba(0, 229, 190, 0.26);
  background: rgba(0, 214, 178, 0.11);
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

@keyframes status-turn {
  to {
    transform: rotate(360deg);
  }
}

@keyframes status-confirm {
  from {
    opacity: 0;
    transform: scale(0.6);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes status-soft-pulse {
  0%, 100% {
    opacity: 0.72;
  }
  50% {
    opacity: 1;
  }
}

@keyframes status-shake {
  0%, 100% {
    transform: translateX(0);
  }
  35% {
    transform: translateX(-2px);
  }
  70% {
    transform: translateX(2px);
  }
}

@media (min-width: 560px) {
  .order-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

</style>
