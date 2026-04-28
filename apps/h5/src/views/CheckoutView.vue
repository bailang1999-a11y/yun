<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, LoaderCircle, QrCode } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { cancelH5Order, fetchH5Order, payH5Order } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import type { H5Order } from '../types/h5'

const route = useRoute()
const router = useRouter()
const order = ref<H5Order | null>(null)
const loading = ref(false)
const paying = ref(false)
const cancelling = ref(false)
const errorMessage = ref('')
const payMethod = ref('wechat')
const now = ref(Date.now())
let timer: number | undefined

const methodLabel = computed(() => (payMethod.value === 'wechat' ? '微信支付' : '支付宝'))
const expiresAt = computed(() => {
  if (!order.value?.createdAt) return 0
  const createdAt = new Date(order.value.createdAt).getTime()
  return Number.isFinite(createdAt) ? createdAt + 15 * 60 * 1000 : 0
})
const remainingMs = computed(() => Math.max(expiresAt.value - now.value, 0))
const expired = computed(() => Boolean(order.value?.status === 'UNPAID' && expiresAt.value && remainingMs.value <= 0))
const canPay = computed(() => Boolean(order.value?.status === 'UNPAID' && !expired.value))
const countdownText = computed(() => {
  const totalSeconds = Math.ceil(remainingMs.value / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
})

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
  void loadOrder()
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})

async function loadOrder() {
  loading.value = true
  errorMessage.value = ''

  try {
    order.value = await fetchH5Order(String(route.params.orderNo))
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

async function payNow() {
  if (!order.value || paying.value || !canPay.value) return
  paying.value = true
  errorMessage.value = ''

  try {
    const paidOrder = await payH5Order(order.value.orderNo, payMethod.value)
    order.value = paidOrder
    await router.push({ path: `/result/${paidOrder.orderNo}`, query: { method: methodLabel.value } })
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    paying.value = false
  }
}

async function cancelOrder() {
  if (!order.value || cancelling.value || order.value.status !== 'UNPAID') return
  cancelling.value = true
  errorMessage.value = ''

  try {
    order.value = await cancelH5Order(order.value.orderNo)
    await router.push({ path: '/orders', query: { orderNo: order.value.orderNo } })
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    cancelling.value = false
  }
}
</script>

<template>
  <main class="page page-pad">
    <header class="checkout-head">
      <p>Cashier</p>
      <h1>确认支付</h1>
    </header>

    <section v-if="loading" class="glass-card state-card" role="status" aria-live="polite" aria-busy="true">
      <LoaderCircle class="spin" :size="18" aria-hidden="true" />
      正在加载订单
    </section>
    <section v-else-if="errorMessage" class="notice danger" role="alert">{{ errorMessage }}</section>

    <template v-if="order">
      <section class="glass-card summary-card">
        <div>
          <span>订单号</span>
          <strong>{{ order.orderNo }}</strong>
        </div>
        <div>
          <span>商品</span>
          <strong>{{ order.goodsName }}</strong>
        </div>
        <div v-if="order.rechargeAccount">
          <span>充值账号</span>
          <strong>{{ order.rechargeAccount }}</strong>
        </div>
        <div>
          <span>数量</span>
          <strong>x{{ order.quantity }}</strong>
        </div>
        <div class="amount-row">
          <span>应付金额</span>
          <strong class="metal-price">¥{{ order.totalAmount.toFixed(2) }}</strong>
        </div>
        <div v-if="order.paymentNo">
          <span>支付流水</span>
          <strong>{{ order.paymentNo }}</strong>
        </div>
      </section>

      <section class="glass-card countdown-card" :data-expired="expired" aria-live="polite">
        <span>{{ order.status === 'UNPAID' ? '订单有效期' : '订单状态' }}</span>
        <strong>{{ order.status === 'UNPAID' ? (expired ? '已超时' : countdownText) : order.status }}</strong>
        <p>{{ order.status === 'UNPAID' ? '请在倒计时结束前完成支付。' : '该订单已离开待支付状态。' }}</p>
      </section>

      <section class="glass-card pay-card">
        <h2>支付方式</h2>
        <button type="button" :class="{ active: payMethod === 'wechat' }" @click="payMethod = 'wechat'">
          <QrCode :size="18" />
          微信支付
          <Check v-if="payMethod === 'wechat'" :size="17" />
        </button>
        <button type="button" :class="{ active: payMethod === 'alipay' }" @click="payMethod = 'alipay'">
          <QrCode :size="18" />
          支付宝
          <Check v-if="payMethod === 'alipay'" :size="17" />
        </button>
      </section>

      <button
        class="pay-action"
        type="button"
        :disabled="paying || !canPay"
        :aria-label="expired ? '订单已超时，无法继续支付' : `使用${methodLabel}`"
        @click="payNow"
      >
        <span v-if="paying" class="blue-swirl" />
        {{ expired ? '订单已超时' : paying ? '支付中' : `使用${methodLabel}` }}
      </button>
      <button v-if="order.status === 'UNPAID'" class="cancel-action" type="button" :disabled="cancelling" @click="cancelOrder">
        {{ cancelling ? '取消中' : '取消订单' }}
      </button>
    </template>

    <AppTabbar />
  </main>
</template>

<style scoped>
.checkout-head {
  margin: 4px 2px 14px;
}

.checkout-head p,
.glass-card span {
  margin: 0;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

h1,
h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
}

h1 {
  font-size: 24px;
}

h2 {
  margin-bottom: 12px;
  font-size: 17px;
}

.glass-card {
  position: relative;
  padding: 16px;
  margin-bottom: 12px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15);
  backdrop-filter: blur(40px) saturate(180%);
}

.summary-card {
  display: grid;
  gap: 12px;
}

.summary-card div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.summary-card strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.86);
  text-align: right;
  overflow-wrap: anywhere;
}

.amount-row strong {
  font-size: 30px;
}

.countdown-card {
  display: grid;
  gap: 6px;
}

.countdown-card strong {
  color: #00ffc3;
  font-size: 28px;
}

.countdown-card p {
  margin: 0;
  color: rgba(255, 255, 255, 0.52);
  font-size: 13px;
}

.countdown-card[data-expired="true"] strong {
  color: #ffab00;
}

.pay-card button {
  width: 100%;
  height: 46px;
  margin-top: 8px;
  padding: 0 12px;
  display: grid;
  grid-template-columns: 24px 1fr 24px;
  align-items: center;
  gap: 10px;
  color: rgba(255, 255, 255, 0.76);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
}

.pay-card button.active {
  color: #fff;
  border-color: rgba(0, 255, 195, 0.32);
  background: rgba(0, 255, 195, 0.1);
  box-shadow: 0 0 34px rgba(0, 255, 195, 0.15);
}

.pay-action {
  width: 100%;
  height: 50px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #06100e;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  box-shadow: 0 18px 42px rgba(0, 255, 195, 0.2);
  font-weight: 800;
}

.pay-action:active {
  transform: scale(0.98);
}

.pay-action:disabled {
  opacity: 0.58;
}

.cancel-action {
  width: 100%;
  height: 44px;
  margin-top: 10px;
  color: rgba(255, 255, 255, 0.78);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.055);
  backdrop-filter: blur(24px);
}

.notice.danger,
.state-card {
  padding: 14px;
}

.notice.danger {
  color: #ff8d86;
}

.state-card {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.58);
}

.spin,
.blue-swirl {
  animation: spin 0.9s linear infinite;
}

.blue-swirl {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  border: 2px solid rgba(88, 166, 255, 0.28);
  border-top-color: #58a6ff;
  box-shadow: 0 0 18px rgba(88, 166, 255, 0.55);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
