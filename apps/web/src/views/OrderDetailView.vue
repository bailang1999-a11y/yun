<template>
  <WebShell>
    <section class="content-stack order-result-page">
      <div class="order-result-toolbar">
        <RouterLink class="back-link" to="/orders">返回订单中心</RouterLink>
        <button class="ghost-button compact-refresh" type="button" :disabled="loading" @click="load">
          {{ loading ? '刷新中...' : '刷新订单' }}
        </button>
      </div>

      <template v-if="order">
        <section class="order-result-hero info-panel">
          <div class="result-mark" aria-hidden="true">
            <span>{{ resultSymbol }}</span>
          </div>

          <div class="result-copy">
            <p>订单状态</p>
            <h1 v-if="showPaidWaitingDelivery">
              <span class="hero-success-text">支付成功</span>
              <span>等待发货</span>
            </h1>
            <h1 v-else>{{ resultTitle }}</h1>
            <small>{{ resultDescription }}</small>
          </div>

          <div class="result-summary">
            <div>
              <span>订单号</span>
              <strong>{{ order.orderNo }}</strong>
            </div>
            <div>
              <span>金额</span>
              <strong class="result-price">¥{{ order.totalAmount.toFixed(2) }}</strong>
            </div>
            <StatusBadge :status="order.status" />
          </div>
        </section>

        <div class="order-result-grid">
          <section class="info-panel order-info-panel">
            <div class="section-kicker">
              <span>订单详情</span>
              <b>{{ order.goodsType || '商品' }}</b>
            </div>
            <h2>{{ order.goodsName }}</h2>
            <div v-if="orderGoods" class="goods-tags order-goods-tags">
              <span v-for="tag in orderGoods.tags || []" :key="`custom-${tag}`" class="goods-tag tag-custom">{{ tag }}</span>
              <span v-for="duration in orderGoods.benefitDurations || []" :key="`duration-${duration}`" class="goods-tag tag-time">
                {{ duration }}
              </span>
              <span v-if="orderGoods.benefitType" class="goods-tag tag-type">{{ orderGoods.benefitType }}</span>
              <span v-if="orderGoods.benefitBrand" class="goods-tag tag-brand">{{ orderGoods.benefitBrand }}</span>
              <span v-if="orderGoods.priceLimitText" class="goods-tag tag-limit">限价 {{ orderGoods.priceLimitText }}</span>
              <span v-for="platform in orderGoods.availablePlatforms || []" :key="`sale-${platform}`" class="goods-tag tag-sale">
                {{ platformLabel(platform) }}
              </span>
              <span v-for="platform in orderGoods.forbiddenPlatforms || []" :key="`deny-${platform}`" class="goods-tag tag-deny">
                禁 {{ platformLabel(platform) }}
              </span>
            </div>
            <dl class="order-facts">
              <div><dt>数量</dt><dd>{{ order.quantity }}</dd></div>
              <div><dt>充值账号</dt><dd>{{ order.rechargeAccount || '-' }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatDateTime(order.createdAt) }}</dd></div>
              <div><dt>订单处理耗时</dt><dd>{{ formatOrderProcessingDuration(order) }}</dd></div>
              <div><dt>支付方式</dt><dd>{{ order.payMethod || '余额支付' }}</dd></div>
            </dl>
            <div v-if="canPay" class="web-pay-box">
              <p v-if="!paymentChannels.length" class="alert-line">暂无可用支付通道，请联系平台开启。</p>
              <div v-else class="web-pay-methods">
                <button
                  v-for="channel in paymentChannels"
                  :key="channel.code"
                  type="button"
                  :class="{ active: payMethod === channel.code }"
                  @click="payMethod = channel.code"
                >
                  {{ channel.name }}
                </button>
              </div>
              <button class="primary-button" type="button" :disabled="paying || !paymentChannels.length" @click="pay">
                {{ paying ? '支付中...' : `使用${payMethodLabel}` }}
              </button>
            </div>
          </section>

          <section class="info-panel delivery-panel">
            <div class="delivery-panel-head">
              <div>
                <p>发货信息</p>
                <h2>交付内容</h2>
              </div>
              <button class="ghost-button" type="button" @click="loadDelivery">刷新</button>
            </div>

            <p v-if="delivery?.instruction && !showPaidWaitingDelivery" class="muted">{{ delivery.instruction }}</p>

            <div v-if="delivery?.cards.length" class="card-list">
              <div v-for="card in delivery.cards" :key="card.cardNo" class="secret-card">
                <strong>{{ card.cardNo }}</strong>
                <span>{{ card.cardPassword || card.password || card.secret || '-' }}</span>
              </div>
            </div>

            <div v-else class="delivery-wait-card">
              <div class="delivery-orbit" aria-hidden="true"><span /></div>
              <div>
                <strong>{{ showPaidWaitingDelivery ? '支付成功，正在等待发货' : '暂无发货内容' }}</strong>
                <p>{{ showPaidWaitingDelivery ? '系统会持续同步订单进度，发货完成后这里会展示卡密或交付信息。' : '支付或发货完成后会展示卡密/交付信息。' }}</p>
              </div>
              <div class="delivery-steps">
                <span class="done">支付成功</span>
                <span :class="{ active: showPaidWaitingDelivery }">等待发货</span>
                <span>交付完成</span>
              </div>
            </div>
          </section>
        </div>
      </template>

      <EmptyState v-else :title="loading ? '订单加载中' : '订单不存在'" />
      <p v-if="error" class="alert-line">{{ error }}</p>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { fetchGoodsDetail, fetchOrder, fetchOrderDelivery, fetchPaymentChannels, payOrder } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import { useCatalogStore } from '../stores/catalog'
import { useSessionStore } from '../stores/session'
import type { GoodsItem, OrderDelivery, OrderItem, PaymentChannel } from '../types/web'
import { formatOrderProcessingDuration } from '../utils/orderDuration'

const route = useRoute()
const session = useSessionStore()
const catalog = useCatalogStore()
const order = ref<OrderItem | null>(null)
const orderGoods = ref<GoodsItem | null>(null)
const delivery = ref<OrderDelivery | null>(null)
const paymentChannels = ref<PaymentChannel[]>([])
const payMethod = ref('balance')
const loading = ref(false)
const paying = ref(false)
const error = ref('')
let refreshTimer: number | undefined
let durationTimer: number | undefined
const orderNo = computed(() => String(route.params.orderNo || ''))
const canPay = computed(() => ['CREATED', 'PENDING_PAY', 'UNPAID'].includes((order.value?.status || '').toUpperCase()))
const payMethodLabel = computed(() => paymentChannels.value.find((item) => item.code === payMethod.value)?.name || '支付')
const unfinishedPaidStatuses = new Set(['PAID', 'PROCURING', 'DELIVERING', 'WAITING_MANUAL'])
const showPaidWaitingDelivery = computed(() => {
  const current = order.value
  if (!current || delivery.value?.cards.length) return false
  return unfinishedPaidStatuses.has((current.status || '').toUpperCase())
})
const resultTitle = computed(() => {
  const status = (order.value?.status || '').toUpperCase()
  if (['CREATED', 'PENDING_PAY', 'UNPAID'].includes(status)) return '订单待支付'
  if (status === 'DELIVERED') return '交付完成'
  if (status === 'FAILED') return '处理失败'
  if (status === 'REFUNDED') return '订单已退款'
  if (status === 'CANCELLED' || status === 'CLOSED') return '订单已关闭'
  return '订单处理中'
})
const resultDescription = computed(() => {
  const status = (order.value?.status || '').toUpperCase()
  if (showPaidWaitingDelivery.value) return '款项已完成支付，当前正在等待发货或上游处理。'
  if (status === 'DELIVERED') return '订单已经完成交付，可查看下方交付内容。'
  if (canPay.value) return '请完成支付，支付后系统会继续处理发货。'
  if (status === 'FAILED') return '订单处理失败，请查看订单或联系客服处理。'
  if (status === 'REFUNDED') return '订单已退款，请留意余额或原支付渠道到账。'
  return '系统正在同步订单状态，请稍后刷新查看。'
})
const resultSymbol = computed(() => {
  const status = (order.value?.status || '').toUpperCase()
  if (showPaidWaitingDelivery.value || status === 'DELIVERED') return '✓'
  if (status === 'FAILED') return '!'
  if (canPay.value) return '¥'
  return '…'
})

function formatDateTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').replace(/\.\d{3}(?=[+-]\d{2}:?\d{2}|Z$)/, '').replace(/([+-]\d{2}:?\d{2}|Z)$/, '')
  const pad = (num: number) => String(num).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (!catalog.categories.length) await catalog.loadCatalog()
    await loadPaymentChannels()
    const nextOrder = await fetchOrder(orderNo.value)
    order.value = nextOrder
    await loadOrderGoods(nextOrder)
    await loadDelivery()
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    loading.value = false
  }
}

async function loadPaymentChannels() {
  const channels = await fetchPaymentChannels('web')
  paymentChannels.value = channels
  if (!channels.some((item) => item.code === payMethod.value)) {
    payMethod.value = channels[0]?.code || ''
  }
}

async function loadOrderGoods(nextOrder = order.value) {
  if (!nextOrder?.goodsId) {
    orderGoods.value = null
    return
  }

  try {
    orderGoods.value = await fetchGoodsDetail(nextOrder.goodsId, catalog.categories)
  } catch {
    orderGoods.value = null
  }
}

async function loadDelivery() {
  try {
    delivery.value = await fetchOrderDelivery(orderNo.value)
  } catch {
    delivery.value = { orderNo: orderNo.value, cards: [] }
  }
}

async function pay() {
  paying.value = true
  error.value = ''
  try {
    order.value = await payOrder(orderNo.value, payMethod.value)
    await session.ensureProfile({ force: true })
    await loadDelivery()
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    paying.value = false
  }
}

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void load()
}

function refreshOrder() {
  void load()
}

function platformLabel(value: string) {
  const labels: Record<string, string> = {
    douyin: '抖音',
    taobao: '淘宝',
    pdd: '拼多多',
    xianyu: '咸鱼',
    xiaohongshu: '小红书',
    private: '私域',
    h5: 'H5',
    web: 'Web',
    pc: 'PC',
    api: 'API'
  }
  return labels[value] || value
}

onMounted(() => {
  void load()
  refreshTimer = window.setInterval(() => void load(), 8000)
  durationTimer = window.setInterval(() => {
    if (order.value) order.value = { ...order.value }
  }, 1000)
  window.addEventListener('focus', refreshOrder)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (durationTimer) window.clearInterval(durationTimer)
  window.removeEventListener('focus', refreshOrder)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>
