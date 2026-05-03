<template>
  <WebShell>
    <section class="content-stack">
      <RouterLink class="back-link" to="/orders">返回订单中心</RouterLink>

      <div v-if="order" class="order-detail-grid">
        <section class="info-panel">
          <div class="page-heading compact">
            <div>
              <p>订单详情</p>
              <h1>{{ order.orderNo }}</h1>
            </div>
            <StatusBadge :status="order.status" />
          </div>
          <dl class="detail-list">
            <div><dt>商品</dt><dd>{{ order.goodsName }}</dd></div>
            <div><dt>数量</dt><dd>{{ order.quantity }}</dd></div>
            <div><dt>金额</dt><dd>¥{{ order.totalAmount.toFixed(2) }}</dd></div>
            <div><dt>充值账号</dt><dd>{{ order.rechargeAccount || '-' }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ order.createdAt || '-' }}</dd></div>
          </dl>
          <button v-if="canPay" class="primary-button" type="button" :disabled="paying" @click="pay">
            {{ paying ? '支付中...' : '余额支付' }}
          </button>
        </section>

        <section class="info-panel">
          <div class="page-heading compact">
            <div>
              <p>发货信息</p>
              <h1>交付内容</h1>
            </div>
            <button class="ghost-button" type="button" @click="loadDelivery">刷新</button>
          </div>
          <p v-if="delivery?.instruction" class="muted">{{ delivery.instruction }}</p>
          <div v-if="delivery?.cards.length" class="card-list">
            <div v-for="card in delivery.cards" :key="card.cardNo" class="secret-card">
              <strong>{{ card.cardNo }}</strong>
              <span>{{ card.cardPassword || card.password || card.secret || '-' }}</span>
            </div>
          </div>
          <EmptyState v-else title="暂无发货内容" description="支付或发货完成后会展示卡密/交付信息。" />
        </section>
      </div>

      <EmptyState v-else :title="loading ? '订单加载中' : '订单不存在'" />
      <p v-if="error" class="alert-line">{{ error }}</p>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { fetchOrder, fetchOrderDelivery, payOrder } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import type { OrderDelivery, OrderItem } from '../types/web'

const route = useRoute()
const order = ref<OrderItem | null>(null)
const delivery = ref<OrderDelivery | null>(null)
const loading = ref(false)
const paying = ref(false)
const error = ref('')
const orderNo = computed(() => String(route.params.orderNo || ''))
const canPay = computed(() => ['CREATED', 'PENDING_PAY', 'UNPAID'].includes((order.value?.status || '').toUpperCase()))

async function load() {
  loading.value = true
  error.value = ''
  try {
    order.value = await fetchOrder(orderNo.value)
    await loadDelivery()
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    loading.value = false
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
    order.value = await payOrder(orderNo.value)
    await loadDelivery()
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    paying.value = false
  }
}

onMounted(load)
</script>
