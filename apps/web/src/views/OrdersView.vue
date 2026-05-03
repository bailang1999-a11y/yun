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
      <div class="table-panel">
        <table v-if="orders.length">
          <thead>
            <tr>
              <th>订单号</th>
              <th>商品</th>
              <th>数量</th>
              <th>金额</th>
              <th>状态</th>
              <th>时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in orders" :key="order.orderNo">
              <td>{{ order.orderNo }}</td>
              <td>{{ order.goodsName }}</td>
              <td>{{ order.quantity }}</td>
              <td>¥{{ order.totalAmount.toFixed(2) }}</td>
              <td><StatusBadge :status="order.status" /></td>
              <td>{{ order.createdAt || '-' }}</td>
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
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { fetchOrders } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import type { OrderItem } from '../types/web'

const orders = ref<OrderItem[]>([])
const loading = ref(false)
const error = ref('')

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

onMounted(load)
</script>
