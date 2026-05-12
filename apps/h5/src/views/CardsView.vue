<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Copy, Search } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5OrderDelivery, fetchH5Orders } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import type { H5Order, OrderDelivery } from '../types/h5'

const route = useRoute()
const orders = ref<H5Order[]>([])
const orderNo = ref('')
const loadingOrders = ref(false)
const loadingDelivery = ref(false)
const errorMessage = ref('')
const copyMessage = ref('')
const securityMessage = ref('')
const rippleActive = ref(false)
const repeatConfirmVisible = ref(false)
const delivery = ref<OrderDelivery | null>(null)
const viewedStorageKey = 'xiyiyun-viewed-card-orders'
let refreshTimer: number | undefined

onMounted(() => {
  orderNo.value = String(route.query.orderNo ?? '')
  void loadOrders()
  if (orderNo.value) void loadDelivery()
  refreshTimer = window.setInterval(() => void loadOrders(), 8000)
  window.addEventListener('focus', refreshCards)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  window.removeEventListener('focus', refreshCards)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})

watch(
  () => route.query.orderNo,
  (value) => {
    orderNo.value = String(value ?? '')
  }
)

function refreshCards() {
  void loadOrders()
}

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') refreshCards()
}

async function loadOrders() {
  loadingOrders.value = true
  try {
    orders.value = await fetchH5Orders()
  } catch {
    orders.value = []
  } finally {
    loadingOrders.value = false
  }
}

async function loadDelivery() {
  const currentOrderNo = orderNo.value.trim()
  if (!currentOrderNo) {
    errorMessage.value = '请输入订单号后再提取卡密。'
    return
  }

  loadingDelivery.value = true
  errorMessage.value = ''
  copyMessage.value = ''
  securityMessage.value = ''
  delivery.value = null
  if (hasViewedOrder(currentOrderNo)) {
    loadingDelivery.value = false
    repeatConfirmVisible.value = true
    return
  }

  await loadDeliveryConfirmed(currentOrderNo)
}

async function loadDeliveryConfirmed(currentOrderNo = orderNo.value.trim()) {
  if (!currentOrderNo) return
  loadingDelivery.value = true
  errorMessage.value = ''
  copyMessage.value = ''
  securityMessage.value = ''
  delivery.value = null
  repeatConfirmVisible.value = false

  try {
    delivery.value = await fetchH5OrderDelivery(currentOrderNo)
    if (delivery.value.cards.length) {
      markViewedOrder(currentOrderNo)
      securityMessage.value = delivery.value.viewedBefore
        ? '该订单此前已显示过卡密，本次为重复查看。'
        : '卡密已显示，请尽快使用。系统不会二次提醒明文安全。'
    }
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loadingDelivery.value = false
  }
}

function cancelRepeatConfirm() {
  repeatConfirmVisible.value = false
}

async function copyCard(cardNo: string, secret?: string) {
  const content = secret ? `${cardNo} ${secret}` : cardNo
  try {
    await navigator.clipboard.writeText(content)
    copyMessage.value = '卡密已复制。'
    rippleActive.value = true
    window.setTimeout(() => {
      rippleActive.value = false
    }, 900)
  } catch {
    copyMessage.value = '复制失败，请长按卡密手动复制。'
  }
}

function hasViewedOrder(value: string) {
  return getViewedOrders().includes(value)
}

function markViewedOrder(value: string) {
  const next = Array.from(new Set([...getViewedOrders(), value]))
  window.localStorage.setItem(viewedStorageKey, JSON.stringify(next))
}

function getViewedOrders() {
  try {
    const raw = window.localStorage.getItem(viewedStorageKey)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}
</script>

<template>
  <main class="page page-pad">
    <span v-if="rippleActive" class="emerald-ripple" aria-hidden="true" />
    <header class="page-head">
      <p>Delivery</p>
      <h1>卡密提取</h1>
    </header>

    <section class="query-panel liquid-surface">
      <label for="orderNo">订单号</label>
      <div class="input-row">
        <input id="orderNo" v-model.trim="orderNo" placeholder="输入订单号" />
        <button type="button" :disabled="loadingDelivery" aria-label="提取卡密" @click="loadDelivery">
          <Search :size="18" />
        </button>
      </div>

      <select v-if="orders.length" v-model="orderNo" aria-label="选择订单号">
        <option value="">从订单中选择</option>
        <option v-for="order in orders" :key="order.orderNo" :value="order.orderNo">
          {{ order.orderNo }} - {{ order.goodsName }}
        </option>
      </select>
      <p v-else class="hint">{{ loadingOrders ? '正在加载订单...' : '也可以直接输入已完成订单号提取。' }}</p>
    </section>

    <section v-if="errorMessage" class="notice danger">{{ errorMessage }}</section>
    <section v-if="copyMessage" class="notice success">{{ copyMessage }}</section>
    <section v-if="securityMessage" class="notice warning">{{ securityMessage }}</section>
    <section v-if="loadingDelivery" class="empty">正在查询发货结果...</section>

    <section v-else-if="delivery" class="delivery-card liquid-surface">
      <div class="delivery-head">
        <span>{{ delivery.orderNo }}</span>
        <strong>{{ delivery.deliveryStatus || delivery.status || '已查询' }}</strong>
      </div>
      <p v-if="delivery.instruction" class="instruction">{{ delivery.instruction }}</p>

      <div v-if="delivery.cards.length" class="card-list">
        <article v-for="card in delivery.cards" :key="card.cardNo" class="secret-card">
          <div>
            <span>卡号</span>
            <strong>{{ card.cardNo }}</strong>
          </div>
          <div v-if="card.cardPassword || card.password || card.secret">
            <span>密码</span>
            <strong>{{ card.cardPassword || card.password || card.secret }}</strong>
          </div>
          <p v-if="card.instruction">{{ card.instruction }}</p>
          <button
            type="button"
            @click="copyCard(card.cardNo, card.cardPassword || card.password || card.secret)"
          >
            <Copy :size="16" />
            复制
          </button>
        </article>
      </div>

      <section v-else class="empty inner">该订单暂无可展示卡密，请稍后刷新或确认订单已发货。</section>
    </section>

    <section v-else class="empty">输入或选择订单号，即可查看卡号、密码和使用说明。</section>

    <Teleport to="body">
      <div v-if="repeatConfirmVisible" class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="repeatCardTitle">
        <div class="confirm-dialog-card">
          <span>安全确认</span>
          <strong id="repeatCardTitle">该订单卡密已查看过</strong>
          <p>再次显示前，请确认周围环境安全，避免卡密被旁人看到。</p>
          <div class="confirm-actions">
            <button type="button" class="ghost" @click="cancelRepeatConfirm">取消</button>
            <button type="button" @click="loadDeliveryConfirmed()">继续查看</button>
          </div>
        </div>
      </div>
    </Teleport>

    <AppTabbar />
  </main>
</template>

<style scoped>
.page-head {
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

.query-panel,
.delivery-card {
  padding: 14px;
  margin-bottom: 10px;
  border-radius: 24px;
}

.emerald-ripple {
  position: fixed;
  left: 50%;
  top: 50%;
  z-index: 30;
  width: 160px;
  height: 160px;
  margin: -80px 0 0 -80px;
  border-radius: 999px;
  border: 1px solid rgba(0, 255, 195, 0.85);
  background: rgba(0, 255, 195, 0.18);
  pointer-events: none;
  animation: emerald-ripple 0.86s ease-out forwards;
}

.query-panel label {
  display: block;
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
  font-weight: 700;
}

.input-row {
  display: grid;
  grid-template-columns: 1fr 42px;
  gap: 8px;
}

input,
select {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.86);
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  outline: none;
  backdrop-filter: blur(18px);
}

select {
  margin-top: 10px;
}

.input-row button {
  display: grid;
  place-items: center;
  color: #06100e;
  background: #00ffc3;
  border: 0;
  border-radius: 16px;
}

.input-row button:disabled {
  opacity: 0.65;
}

.hint {
  margin: 10px 0 0;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.empty {
  padding: 28px 16px;
  color: rgba(255, 255, 255, 0.58);
  background: rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 22px;
  backdrop-filter: blur(32px) saturate(180%);
}

.empty.inner {
  margin-top: 12px;
  background: rgba(255, 255, 255, 0.045);
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

.notice.warning {
  color: #ffab00;
}

.delivery-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.delivery-head strong {
  color: #00ffc3;
  font-size: 13px;
}

.instruction {
  margin: 12px 0 0;
  color: rgba(255, 255, 255, 0.68);
  line-height: 1.6;
}

.card-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.secret-card {
  padding: 12px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
}

.secret-card div {
  display: grid;
  grid-template-columns: 46px 1fr;
  gap: 8px;
  margin-bottom: 8px;
}

.secret-card span {
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.secret-card strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: rgba(255, 255, 255, 0.9);
  font-size: 15px;
}

.secret-card p {
  margin: 0 0 10px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 13px;
  line-height: 1.6;
}

.secret-card button {
  height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  color: #06100e;
  background: #00ffc3;
  border: 0;
  border-radius: 999px;
}

.confirm-dialog {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(3, 8, 18, 0.66);
  backdrop-filter: blur(14px);
}

.confirm-dialog-card {
  width: min(420px, 100%);
  padding: 26px;
  color: rgba(255, 255, 255, 0.9);
  text-align: center;
  border-radius: 24px;
  background: linear-gradient(145deg, rgba(11, 25, 45, 0.94), rgba(6, 35, 36, 0.9));
  border: 1px solid rgba(0, 255, 195, 0.18);
  box-shadow: 0 30px 90px rgba(0, 0, 0, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.confirm-dialog-card span {
  display: inline-flex;
  padding: 5px 12px;
  color: #00ffc3;
  border-radius: 999px;
  background: rgba(0, 255, 195, 0.1);
  border: 1px solid rgba(0, 255, 195, 0.2);
  font-size: 12px;
  font-weight: 800;
}

.confirm-dialog-card strong {
  display: block;
  margin-top: 14px;
  font-size: 21px;
}

.confirm-dialog-card p {
  margin: 12px 0 0;
  color: rgba(214, 226, 240, 0.74);
  line-height: 1.8;
}

.confirm-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 22px;
}

.confirm-actions button {
  height: 44px;
  color: #04110e;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  font-weight: 800;
}

.confirm-actions .ghost {
  color: rgba(226, 236, 247, 0.84);
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
}
</style>
