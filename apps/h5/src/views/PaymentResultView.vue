<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Check, Copy, LoaderCircle, Sparkles, Ticket, UserRound, Zap } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5GoodsDetail, fetchH5Order } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import { useCatalogStore } from '../stores/catalog'
import type { GoodsCard, GoodsType, H5Order } from '../types/h5'
import { formatMoney } from '../utils/formatters'
import { formatOrderProcessingDuration } from '../utils/orderDuration'
import { getOrderStatusMeta, isOrderProcessing } from '../utils/orderStatus'

const route = useRoute()
const catalog = useCatalogStore()
const order = ref<H5Order | null>(null)
const orderGoods = ref<GoodsCard | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const copyMessage = ref('')
const pollEnded = ref(false)
const payMethod = String(route.query.method ?? '支付')
let pollTimer: number | undefined
let durationTimer: number | undefined
let copyTimer: number | undefined
let pollStartedAt = 0

const goodsTypePresentation: Record<GoodsType, { label: string; icon: typeof Ticket }> = {
  CARD: { label: '卡密', icon: Ticket },
  DIRECT: { label: '直充', icon: Zap },
  MANUAL: { label: '代充', icon: UserRound }
}

const statusPresentation = computed(() => getOrderStatusMeta(order.value?.status))
const isProcessing = computed(() => Boolean(order.value && isOrderProcessing(order.value.status)))
const resultTitle = computed(() => statusPresentation.value.resultTitle)

const resultCopy = computed(() => {
  if (!order.value) return errorMessage.value || '正在确认订单'
  if (order.value.status === 'DELIVERED' && order.value.goodsType === 'CARD') return '卡密已自动发放，请及时提取。'
  if (order.value.status === 'DELIVERED' && order.value.goodsType === 'DIRECT') return '直充已完成，请检查充值账号。'
  if (order.value.status === 'DELIVERED' && order.value.goodsType === 'MANUAL') return '代充已完成，请检查对应权益。'
  if (pollEnded.value && isProcessing.value) return '还在处理中，可稍后到订单列表查看。'
  return statusPresentation.value.resultCopy
})

const resultGoodsType = computed(() => goodsTypePresentation[order.value?.goodsType || orderGoods.value?.type || 'CARD'])

async function copyText(value: string | undefined, label: string) {
  const content = (value || '').trim()
  if (!content) return

  try {
    if (window.isSecureContext && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(content)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = content
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      const copied = document.execCommand('copy')
      document.body.removeChild(textarea)
      if (!copied) throw new Error('copy command failed')
    }
    copyMessage.value = `${label}已复制`
  } catch {
    copyMessage.value = '复制失败，请长按内容复制。'
  }

  if (copyTimer) window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copyMessage.value = ''
  }, 1800)
}

onMounted(() => {
  pollStartedAt = Date.now()
  if (!catalog.categories.length) void catalog.loadCatalog()
  durationTimer = window.setInterval(() => {
    if (order.value) order.value = { ...order.value }
  }, 1000)
  void loadOrder({ keepPolling: true })
})

onBeforeUnmount(() => {
  stopPolling()
  if (durationTimer) window.clearInterval(durationTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
})

async function loadOrder(options: { keepPolling?: boolean } = {}) {
  loading.value = !order.value
  errorMessage.value = ''

  try {
    const nextOrder = await fetchH5Order(String(route.params.orderNo))
    order.value = nextOrder
    await loadOrderGoods(nextOrder)
    if (options.keepPolling) scheduleNextPoll()
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
    stopPolling()
  } finally {
    loading.value = false
  }
}

async function loadOrderGoods(nextOrder = order.value) {
  if (!nextOrder?.goodsId) {
    orderGoods.value = null
    return
  }

  try {
    if (!catalog.categories.length) await catalog.loadCatalog()
    orderGoods.value = await fetchH5GoodsDetail(nextOrder.goodsId, catalog.categories, nextOrder.platform || 'h5')
  } catch {
    orderGoods.value = null
  }
}

function scheduleNextPoll() {
  stopPolling()
  if (!order.value || getOrderStatusMeta(order.value.status).terminal || order.value.status === 'UNPAID') return
  if (Date.now() - pollStartedAt >= 30 * 1000) {
    pollEnded.value = true
    return
  }

  pollTimer = window.setTimeout(() => {
    void loadOrder({ keepPolling: true })
  }, 2500)
}

function stopPolling() {
  if (!pollTimer) return
  window.clearTimeout(pollTimer)
  pollTimer = undefined
}

function platformLabel(value: string) {
  const labels: Record<string, string> = {
    douyin: '抖音',
    taobao: '淘宝',
    pdd: '拼多多',
    xianyu: '咸鱼',
    xiaohongshu: '小红书',
    private: '私域',
    h5: '移动 H5',
    web: 'Web',
    pc: 'PC 端',
    api: 'API',
    miniapp: '微信小程序'
  }
  return labels[value] || value
}
</script>

<template>
  <main class="page page-pad">
    <Transition name="copy-toast">
      <div v-if="copyMessage" class="copy-toast" role="status">
        <Check :size="16" aria-hidden="true" />
        {{ copyMessage }}
      </div>
    </Transition>

    <section
      class="result-card liquid-surface"
      :data-order-status="statusPresentation.status"
      :class="[`result-${statusPresentation.tone}`, { 'is-processing': isProcessing && !pollEnded }]"
    >
      <span class="status-wave" aria-hidden="true" />
      <div class="icon-wrap">
        <component :is="statusPresentation.icon" :size="42" aria-hidden="true" />
      </div>
      <p>{{ payMethod }} · {{ statusPresentation.label }}</p>
      <h1>{{ resultTitle }}</h1>
      <small v-if="order">
        <LoaderCircle v-if="isProcessing && !pollEnded" class="spin blue-icon" :size="14" />
        {{ resultCopy }}
      </small>
      <small v-else-if="loading"><LoaderCircle class="spin" :size="14" /> 正在确认订单</small>
      <small v-else>{{ errorMessage || '订单确认完成' }}</small>
    </section>

    <section v-if="order" class="order-mini liquid-surface">
      <div>
        <span>订单号</span>
        <button class="copy-value" type="button" :aria-label="`复制订单号 ${order.orderNo}`" @click="copyText(order.orderNo, '订单号')">
          <strong>{{ order.orderNo }}</strong>
          <Copy :size="14" aria-hidden="true" />
        </button>
      </div>
      <div>
        <span>商品</span>
        <strong class="goods-name">{{ order.goodsName }}</strong>
      </div>
      <div>
        <span>订单类型</span>
        <strong class="goods-type-badge" :data-type="order.goodsType || orderGoods?.type || 'CARD'">
          <component :is="resultGoodsType.icon" :size="13" aria-hidden="true" />
          {{ resultGoodsType.label }}
        </strong>
      </div>
      <div v-if="orderGoods" class="tags-line">
        <span>商品标签</span>
        <div class="goods-tags">
          <span v-for="tag in orderGoods.tags || []" :key="`custom-${tag}`" class="tag tag-custom">{{ tag }}</span>
          <span v-for="duration in orderGoods.benefitDurations || []" :key="`duration-${duration}`" class="tag tag-time">{{ duration }}</span>
          <span v-if="orderGoods.benefitType" class="tag tag-type">
            <Sparkles :size="11" aria-hidden="true" />
            {{ orderGoods.benefitType }}
          </span>
          <span v-if="orderGoods.benefitBrand" class="tag tag-brand">{{ orderGoods.benefitBrand }}</span>
          <span v-if="orderGoods.priceLimitText" class="tag tag-limit">限价 {{ orderGoods.priceLimitText }}</span>
          <span v-for="platform in orderGoods.availablePlatforms || []" :key="`sale-${platform}`" class="tag tag-sale">
            {{ platformLabel(platform) }}
          </span>
          <span v-for="platform in orderGoods.forbiddenPlatforms || []" :key="`deny-${platform}`" class="tag tag-deny">
            禁 {{ platformLabel(platform) }}
          </span>
        </div>
      </div>
      <div v-if="order.rechargeAccount">
        <span>充值账号</span>
        <button class="copy-value" type="button" :aria-label="`复制充值账号 ${order.rechargeAccount}`" @click="copyText(order.rechargeAccount, '充值账号')">
          <strong>{{ order.rechargeAccount }}</strong>
          <Copy :size="14" aria-hidden="true" />
        </button>
      </div>
      <div>
        <span>状态</span>
        <strong class="inline-status" :data-tone="statusPresentation.tone">
          <component :is="statusPresentation.icon" :size="14" aria-hidden="true" />
          {{ statusPresentation.label }}
        </strong>
      </div>
      <div><span>订单处理耗时</span><strong>{{ formatOrderProcessingDuration(order) }}</strong></div>
      <div><span>金额</span><strong class="metal-price">¥{{ formatMoney(order.totalAmount) }}</strong></div>
    </section>

    <div v-if="order" class="action-row">
      <RouterLink :to="{ path: '/orders', query: { orderNo: order.orderNo } }">查看订单</RouterLink>
      <RouterLink v-if="order.status === 'UNPAID'" :to="{ path: '/checkout/' + order.orderNo }">继续支付</RouterLink>
      <RouterLink v-if="order.goodsType === 'CARD' && order.status === 'DELIVERED'" :to="{ path: '/cards', query: { orderNo: order.orderNo } }">
        提取卡密
      </RouterLink>
    </div>

    <AppTabbar />
  </main>
</template>

<style scoped>
.result-card {
  --status-rgb: 148, 163, 184;
  --status-ink: #07101a;
  position: relative;
  min-height: 270px;
  padding: 30px 18px;
  display: grid;
  place-items: center;
  text-align: center;
  overflow: hidden;
  border-radius: 30px;
  background:
    radial-gradient(circle at 50% 34%, rgba(var(--status-rgb), 0.16), transparent 32%),
    rgba(255, 255, 255, 0.05);
  border-color: rgba(var(--status-rgb), 0.24);
}

.result-success {
  --status-rgb: 0, 255, 195;
}

.result-processing {
  --status-rgb: 88, 166, 255;
}

.result-pending {
  --status-rgb: 255, 171, 0;
}

.result-refund {
  --status-rgb: 56, 189, 248;
}

.result-error {
  --status-rgb: 255, 59, 48;
  --status-ink: #170706;
}

.result-cancel,
.result-neutral {
  --status-rgb: 148, 163, 184;
}

.status-wave {
  position: absolute;
  width: 180px;
  height: 180px;
  border-radius: 999px;
  background: rgba(var(--status-rgb), 0.1);
  border: 1px solid rgba(var(--status-rgb), 0.3);
  opacity: 0.62;
}

.result-success .status-wave {
  animation: result-ripple 820ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.result-card.is-processing .status-wave {
  animation: result-breathe 1.5s ease-in-out infinite;
}

.icon-wrap {
  position: relative;
  z-index: 1;
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  color: var(--status-ink);
  border-radius: 999px;
  background: rgb(var(--status-rgb));
  box-shadow: 0 0 48px rgba(var(--status-rgb), 0.36), inset 0 1px 0 rgba(255, 255, 255, 0.38);
}

.result-card.is-processing .icon-wrap {
  animation: result-icon-pulse 1.4s ease-in-out infinite;
}

.result-card[data-order-status='PAYING'] .icon-wrap svg,
.result-card[data-order-status='REFUNDING'] .icon-wrap svg {
  animation: result-turn 1.4s linear infinite;
}

.result-error .icon-wrap {
  animation: result-shake 240ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.result-card p,
.result-card small,
.order-mini span {
  margin: 0;
  color: rgba(255, 255, 255, 0.52);
}

.result-card h1 {
  position: relative;
  z-index: 1;
  margin: 0;
  color: rgba(255, 255, 255, 0.94);
  font-size: 25px;
}

.result-card small {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.order-mini {
  display: grid;
  gap: 12px;
  margin-top: 12px;
  padding: 16px;
  border-radius: 24px;
}

.order-mini div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.order-mini .tags-line {
  align-items: flex-start;
}

.goods-tags {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 5px;
  max-width: 72%;
}

.tag {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 750;
  line-height: 1;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
}

.tag-time {
  color: #c9fff4;
  background: rgba(0, 214, 178, 0.14);
  border-color: rgba(0, 229, 190, 0.28);
}

.tag-type {
  color: #e4ddff;
  background: rgba(122, 92, 255, 0.16);
  border-color: rgba(148, 121, 255, 0.3);
}

.tag-brand {
  color: #d6f0ff;
  background: rgba(46, 152, 235, 0.15);
  border-color: rgba(84, 180, 255, 0.3);
}

.tag-custom {
  color: #e6fbff;
  background: rgba(20, 184, 166, 0.15);
  border-color: rgba(45, 212, 191, 0.3);
}

.tag-limit {
  color: #fff3d2;
  background: rgba(245, 158, 11, 0.18);
  border-color: rgba(251, 191, 36, 0.34);
}

.tag-sale {
  color: #eaf7ff;
  background: rgba(68, 134, 255, 0.16);
  border-color: rgba(106, 164, 255, 0.32);
}

.tag-deny {
  color: #ffe1e1;
  background: rgba(236, 77, 93, 0.14);
  border-color: rgba(255, 112, 126, 0.3);
}

.order-mini strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.86);
  text-align: right;
  overflow-wrap: anywhere;
}

.goods-name {
  max-width: 72%;
}

.copy-value {
  min-width: 0;
  max-width: 72%;
  min-height: 32px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  color: rgba(255, 255, 255, 0.84);
  border: 0;
  background: transparent;
}

.copy-value strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copy-value svg {
  flex: 0 0 auto;
  color: rgba(255, 255, 255, 0.58);
}

.copy-value:active {
  transform: scale(0.97);
}

.goods-type-badge,
.inline-status {
  min-height: 25px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 750;
}

.goods-type-badge {
  color: #dbeafe !important;
  border: 0.5px solid rgba(88, 166, 255, 0.25);
  background: rgba(88, 166, 255, 0.11);
}

.goods-type-badge[data-type='CARD'] {
  color: #d9fff6 !important;
  border-color: rgba(0, 255, 195, 0.24);
  background: rgba(0, 255, 195, 0.09);
}

.goods-type-badge[data-type='MANUAL'] {
  color: #fff1ca !important;
  border-color: rgba(255, 171, 0, 0.27);
  background: rgba(255, 171, 0, 0.1);
}

.inline-status {
  --inline-status-rgb: 148, 163, 184;
  color: rgb(var(--inline-status-rgb)) !important;
  border: 0.5px solid rgba(var(--inline-status-rgb), 0.26);
  border-radius: 999px;
  background: rgba(var(--inline-status-rgb), 0.09);
}

.inline-status[data-tone='success'] {
  --inline-status-rgb: 0, 255, 195;
}

.inline-status[data-tone='processing'] {
  --inline-status-rgb: 88, 166, 255;
}

.inline-status[data-tone='pending'] {
  --inline-status-rgb: 255, 171, 0;
}

.inline-status[data-tone='refund'] {
  --inline-status-rgb: 56, 189, 248;
}

.inline-status[data-tone='error'] {
  --inline-status-rgb: 255, 59, 48;
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
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.34);
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

.action-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 12px;
}

.action-row a {
  height: 46px;
  display: grid;
  place-items: center;
  color: #06100e;
  text-decoration: none;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  font-weight: 800;
}

.action-row a:first-child:last-child {
  grid-column: 1 / -1;
}

.spin {
  animation: spin 0.9s linear infinite;
}

.blue-icon {
  color: #58a6ff;
  filter: drop-shadow(0 0 10px rgba(88, 166, 255, 0.55));
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes result-turn {
  to {
    transform: rotate(360deg);
  }
}

@keyframes result-ripple {
  from {
    opacity: 0.58;
    transform: scale(0.45);
  }
  to {
    opacity: 0;
    transform: scale(1.38);
  }
}

@keyframes result-icon-pulse {
  0%, 100% {
    transform: scale(0.96);
  }
  50% {
    transform: scale(1.02);
  }
}

@keyframes result-breathe {
  0%, 100% {
    opacity: 0.36;
    transform: scale(0.72);
  }
  50% {
    opacity: 0.72;
    transform: scale(1.08);
  }
}

@keyframes result-shake {
  0%, 100% {
    transform: translateX(0);
  }
  35% {
    transform: translateX(-3px);
  }
  70% {
    transform: translateX(3px);
  }
}
</style>
