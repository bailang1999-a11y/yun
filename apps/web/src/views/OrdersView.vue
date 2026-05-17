<template>
  <WebShell>
    <section class="content-stack">
      <div class="page-heading">
        <div>
          <p>订单中心</p>
          <h1>我的订单</h1>
        </div>
        <button class="ghost-button" type="button" @click="load">刷新</button>
      </div>

      <p v-if="error" class="alert-line">{{ error }}</p>
      <p v-if="copyMessage" class="copy-feedback" role="status">{{ copyMessage }}</p>
      <div class="table-panel">
        <table v-if="orders.length">
          <thead>
            <tr>
              <th>订单号</th>
              <th>商品</th>
              <th>充值账号</th>
              <th>数量</th>
              <th>金额</th>
              <th>状态</th>
              <th>订单处理耗时</th>
              <th>时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in orders" :key="order.orderNo">
              <td>
                <button class="copy-value order-no-button" type="button" @click="copyText(order.orderNo, '订单号')">
                  {{ order.orderNo }}
                </button>
              </td>
              <td>{{ order.goodsName }}</td>
              <td>
                <button
                  class="copy-value account-chip"
                  type="button"
                  :disabled="!order.rechargeAccount"
                  @click="copyText(order.rechargeAccount, '充值账号')"
                >
                  {{ order.rechargeAccount || '-' }}
                </button>
              </td>
              <td>{{ order.quantity }}</td>
              <td class="amount-cell">¥{{ formatMoney(order.totalAmount) }}</td>
              <td><StatusBadge :status="order.status" /></td>
              <td>{{ formatOrderProcessingDuration(order) }}</td>
              <td>{{ formatDateTime(order.createdAt) }}</td>
              <td><RouterLink class="text-link" :to="`/orders/${order.orderNo}`">详情</RouterLink></td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else :title="loading ? '订单加载中' : '暂无订单'" description="购买商品后会在这里显示。" />
      </div>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { fetchOrders } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import type { OrderItem } from '../types/web'
import { formatMoney } from '../utils/formatters'
import { formatOrderProcessingDuration } from '../utils/orderDuration'

const orders = ref<OrderItem[]>([])
const loading = ref(false)
const error = ref('')
const copyMessage = ref('')
let refreshTimer: number | undefined
let durationTimer: number | undefined
let copyTimer: number | undefined

async function load() {
  loading.value = true
  error.value = ''
  try {
    orders.value = await fetchOrders()
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    loading.value = false
  }
}

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void load()
}

function refreshOrders() {
  void load()
}

function formatDateTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').replace(/\.\d{3}(?=[+-]\d{2}:?\d{2}|Z$)/, '').replace(/([+-]\d{2}:?\d{2}|Z)$/, '')
  const pad = (num: number) => String(num).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function copyText(value: string | undefined, label: string) {
  const content = (value || '').trim()
  if (!content) return

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(content)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = content
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    copyMessage.value = `${label}已复制`
  } catch {
    copyMessage.value = '复制失败，请手动选择复制'
  }

  if (copyTimer) window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copyMessage.value = ''
  }, 1800)
}

onMounted(() => {
  void load()
  refreshTimer = window.setInterval(() => void load(), 8000)
  durationTimer = window.setInterval(() => {
    orders.value = [...orders.value]
  }, 1000)
  window.addEventListener('focus', refreshOrders)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (durationTimer) window.clearInterval(durationTimer)
  if (copyTimer) window.clearTimeout(copyTimer)
  window.removeEventListener('focus', refreshOrders)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<style scoped>
.copy-feedback {
  width: max-content;
  max-width: 100%;
  margin: 0;
  padding: 10px 13px;
  color: var(--accent-ink);
  font-size: 13px;
  font-weight: 750;
  border: 1px solid oklch(100% 0.004 250 / 0.42);
  border-radius: 8px;
  background: var(--accent-soft);
}

.copy-value {
  max-width: 100%;
  appearance: none;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.copy-value:disabled {
  cursor: default;
}

.order-no-button {
  padding: 0;
  color: var(--accent-ink);
  font-weight: 800;
  background: transparent;
}

.order-no-button:hover,
.account-chip:not(:disabled):hover {
  color: var(--accent);
}
</style>
