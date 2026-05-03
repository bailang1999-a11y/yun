<template>
  <WebShell>
    <section class="detail-page">
      <RouterLink class="back-link" to="/">返回商品列表</RouterLink>

      <div v-if="loading" class="detail-grid">
        <div class="skeleton-card tall" />
        <div class="skeleton-card tall" />
      </div>

      <div v-else-if="goods" class="detail-grid">
        <section class="product-media">
          <div class="detail-cover"><span>{{ goods.cover }}</span></div>
          <div class="product-facts">
            <span>{{ goods.category }}</span>
            <span>{{ goods.stockLabel }}</span>
            <span>{{ goods.type === 'CARD' ? '卡密' : goods.type === 'DIRECT' ? '直充' : '代充' }}</span>
          </div>
        </section>

        <section class="order-panel">
          <h1>{{ goods.name }}</h1>
          <p class="muted">{{ goods.faceValue }}</p>
          <div class="price-line">
            <strong>¥{{ goods.price.toFixed(2) }}</strong>
            <del v-if="goods.originalPrice">¥{{ goods.originalPrice.toFixed(2) }}</del>
          </div>

          <label class="quantity-row">
            <span>数量</span>
            <input v-model.number="quantity" min="1" type="number" />
          </label>

          <div v-if="goods.requireRechargeAccount || rechargeFields.length" class="field-stack">
            <label v-for="field in rechargeFields" :key="field.id || field.code">
              <span>{{ field.label || '充值账号' }}</span>
              <input v-model.trim="rechargeValues[field.code]" :placeholder="field.placeholder || '请输入充值账号'" />
              <small v-if="field.helpText">{{ field.helpText }}</small>
            </label>
            <label v-if="!rechargeFields.length">
              <span>充值账号</span>
              <input v-model.trim="rechargeAccount" placeholder="请输入充值账号" />
            </label>
          </div>

          <textarea v-model.trim="buyerRemark" rows="3" placeholder="订单备注（选填）" />
          <p v-if="error" class="alert-line">{{ error }}</p>

          <button class="primary-button wide" type="button" :disabled="submitting" @click="submitOrder">
            {{ session.isLoggedIn ? (submitting ? '提交中...' : '立即下单') : '登录后购买' }}
          </button>
        </section>
      </div>

      <EmptyState v-else title="商品不存在" description="请返回列表重新选择。" />
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import EmptyState from '../components/EmptyState.vue'
import { createOrder, fetchGoodsDetail, fetchRechargeFields } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import { useCatalogStore } from '../stores/catalog'
import { useSessionStore } from '../stores/session'
import type { GoodsItem, RechargeField } from '../types/web'

const route = useRoute()
const router = useRouter()
const catalog = useCatalogStore()
const session = useSessionStore()
const goods = ref<GoodsItem | null>(null)
const rechargeFields = ref<RechargeField[]>([])
const rechargeValues = reactive<Record<string, string>>({})
const rechargeAccount = ref('')
const buyerRemark = ref('')
const quantity = ref(1)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const goodsId = computed(() => String(route.params.id || ''))

onMounted(async () => {
  loading.value = true
  try {
    if (!catalog.categories.length) await catalog.loadCatalog()
    goods.value = await fetchGoodsDetail(goodsId.value, catalog.categories)
    rechargeFields.value = await fetchRechargeFields()
  } finally {
    loading.value = false
  }
})

async function submitOrder() {
  if (!session.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (!goods.value) return
  const firstFieldValue = rechargeFields.value.map((field) => rechargeValues[field.code]).find(Boolean)
  const account = firstFieldValue || rechargeAccount.value
  if (goods.value.requireRechargeAccount && !account) {
    error.value = '请填写充值账号。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const order = await createOrder({
      goodsId: goods.value.id,
      quantity: Math.max(1, quantity.value || 1),
      rechargeAccount: account,
      buyerRemark: buyerRemark.value,
      requestId: `${Date.now()}-${goods.value.id}`
    })
    await router.push(`/orders/${order.orderNo}`)
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    submitting.value = false
  }
}
</script>
