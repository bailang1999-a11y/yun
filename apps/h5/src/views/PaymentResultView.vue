<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { CheckCircle2, LoaderCircle } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5GoodsDetail, fetchH5Order } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import { useCatalogStore } from '../stores/catalog'
import type { GoodsCard, H5Order } from '../types/h5'
import { formatOrderProcessingDuration } from '../utils/orderDuration'

const route = useRoute()
const catalog = useCatalogStore()
const order = ref<H5Order | null>(null)
const orderGoods = ref<GoodsCard | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const pollEnded = ref(false)
const payMethod = String(route.query.method ?? '支付')
const terminalStatuses = new Set(['DELIVERED', 'FAILED', 'REFUNDED', 'CANCELLED', 'CLOSED'])
let pollTimer: number | undefined
let durationTimer: number | undefined
let pollStartedAt = 0
const statusLabel: Record<string, string> = {
  UNPAID: '待支付',
  PROCURING: '采购中',
  WAITING_MANUAL: '待人工处理',
  DELIVERED: '已发货',
  FAILED: '处理失败',
  REFUNDED: '已退款',
  CANCELLED: '已取消'
}

const isProcessing = computed(() => Boolean(order.value && !terminalStatuses.has(order.value.status) && order.value.status !== 'UNPAID'))
const resultTitle = computed(() => {
  if (!order.value) return '正在确认订单'
  if (order.value.status === 'UNPAID') return '订单待支付'
  if (order.value.status === 'CANCELLED') return '订单已取消'
  if (order.value.status === 'FAILED') return '处理失败'
  if (order.value.status === 'REFUNDED') return '订单已退款'
  return '支付成功，正在处理'
})

const resultCopy = computed(() => {
  if (!order.value) return errorMessage.value || '正在确认订单'
  if (order.value.status === 'UNPAID') return '请返回收银台完成支付。'
  if (order.value.status === 'CANCELLED') return '该订单已取消，不会继续发货。'
  if (order.value.status === 'FAILED') return '订单处理失败，请查看订单或联系客服。'
  if (order.value.status === 'REFUNDED') return '退款流程已处理，请留意原支付渠道到账。'
  if (order.value.goodsType === 'CARD') return '卡密已自动发放'
  if (order.value.goodsType === 'DIRECT') return order.value.status === 'DELIVERED' ? '直充已完成' : '直充订单已进入采购'
  if (pollEnded.value && isProcessing.value) return '还在处理中，可稍后到订单列表查看。'
  return '订单等待人工处理'
})

function formatStatus(status: string) {
  return statusLabel[status] ?? status
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
    orderGoods.value = await fetchH5GoodsDetail(nextOrder.goodsId, catalog.categories)
  } catch {
    orderGoods.value = null
  }
}

function scheduleNextPoll() {
  stopPolling()
  if (!order.value || terminalStatuses.has(order.value.status) || order.value.status === 'UNPAID') return
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
    <section class="result-card liquid-surface">
      <span class="emerald-wave" aria-hidden="true" />
      <div class="icon-wrap"><CheckCircle2 :size="42" /></div>
      <p>{{ payMethod }}</p>
      <h1>{{ resultTitle }}</h1>
      <small v-if="order">
        <LoaderCircle v-if="isProcessing && !pollEnded" class="spin blue-icon" :size="14" />
        {{ resultCopy }}
      </small>
      <small v-else-if="loading"><LoaderCircle class="spin" :size="14" /> 正在确认订单</small>
      <small v-else>{{ errorMessage || '订单确认完成' }}</small>
    </section>

    <section v-if="order" class="order-mini liquid-surface">
      <div><span>订单号</span><strong>{{ order.orderNo }}</strong></div>
      <div><span>商品</span><strong>{{ order.goodsName }}</strong></div>
      <div v-if="orderGoods" class="tags-line">
        <span>权益标签</span>
        <div class="goods-tags">
          <span v-for="tag in orderGoods.tags || []" :key="`custom-${tag}`" class="tag tag-custom">{{ tag }}</span>
          <span v-for="duration in orderGoods.benefitDurations || []" :key="`duration-${duration}`" class="tag tag-time">{{ duration }}</span>
          <span v-if="orderGoods.benefitType" class="tag tag-type">{{ orderGoods.benefitType }}</span>
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
      <div><span>状态</span><strong>{{ formatStatus(order.status) }}</strong></div>
      <div><span>订单处理耗时</span><strong>{{ formatOrderProcessingDuration(order) }}</strong></div>
      <div><span>金额</span><strong class="metal-price">¥{{ order.totalAmount.toFixed(2) }}</strong></div>
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
  position: relative;
  min-height: 270px;
  padding: 30px 18px;
  display: grid;
  place-items: center;
  text-align: center;
  overflow: hidden;
  border-radius: 30px;
}

.emerald-wave {
  position: absolute;
  width: 180px;
  height: 180px;
  border-radius: 999px;
  background: rgba(0, 255, 195, 0.14);
  border: 1px solid rgba(0, 255, 195, 0.35);
  animation: emerald-ripple 1.8s ease-out infinite;
}

.icon-wrap {
  position: relative;
  z-index: 1;
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  color: #06100e;
  border-radius: 999px;
  background: #00ffc3;
  box-shadow: 0 0 48px rgba(0, 255, 195, 0.42);
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
</style>
