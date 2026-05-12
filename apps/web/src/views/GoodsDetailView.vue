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
          <div class="detail-cover">
            <img v-if="goods.coverUrl" :src="goods.coverUrl" :alt="goods.name" />
            <span v-else>{{ goods.cover }}</span>
          </div>
          <div class="product-facts">
            <span>{{ goods.category }}</span>
            <span>{{ goods.stockLabel }}</span>
            <span>{{ goods.type === 'CARD' ? '卡密' : goods.type === 'DIRECT' ? '直充' : '代充' }}</span>
          </div>
        </section>

        <section class="order-panel">
          <h1>{{ goods.name }}</h1>
          <p class="muted">{{ goods.faceValue }}</p>
          <div class="goods-tags">
            <span v-for="tag in goods.tags || []" :key="`custom-${tag}`" class="goods-tag tag-custom">{{ tag }}</span>
            <span v-for="duration in goods.benefitDurations || []" :key="`duration-${duration}`" class="goods-tag tag-time">
              {{ duration }}
            </span>
            <span v-if="goods.benefitType" class="goods-tag tag-type">{{ goods.benefitType }}</span>
            <span v-if="goods.benefitBrand" class="goods-tag tag-brand">{{ goods.benefitBrand }}</span>
            <span v-if="goods.priceLimitText" class="goods-tag tag-limit">限价 {{ goods.priceLimitText }}</span>
            <span v-for="platform in goods.availablePlatforms || []" :key="`sale-${platform}`" class="goods-tag tag-sale">
              {{ platformLabel(platform) }}
            </span>
            <span v-for="platform in goods.forbiddenPlatforms || []" :key="`deny-${platform}`" class="goods-tag tag-deny">
              禁 {{ platformLabel(platform) }}
            </span>
          </div>
          <div class="price-line">
            <strong>¥{{ goods.price.toFixed(2) }}</strong>
            <del v-if="goods.originalPrice">¥{{ goods.originalPrice.toFixed(2) }}</del>
          </div>

          <label class="quantity-row">
            <span>数量</span>
            <input v-model.number="quantity" min="1" :max="maxQuantity" type="number" @blur="clampQuantity" @input="clampQuantity" />
          </label>
          <p v-if="goods.maxBuy > 1" class="muted">单次最多购买 {{ goods.maxBuy }} 件</p>

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

          <p v-if="restrictionReason" class="alert-line">{{ restrictionReason }}</p>

          <button class="primary-button wide" type="button" :disabled="submitting || Boolean(restrictionReason)" @click="submitOrder">
            {{ buyButtonLabel }}
          </button>
        </section>
      </div>

      <EmptyState v-else title="商品不存在" description="请返回列表重新选择。" />

      <Teleport to="body">
        <div v-if="restrictionDialogMessage" class="limit-dialog" role="dialog" aria-modal="true">
          <div class="limit-dialog-card">
            <span>下单限制</span>
            <strong>暂无法购买该商品</strong>
            <p>{{ restrictionDialogMessage }}</p>
            <button type="button" @click="restrictionDialogMessage = ''">我知道了</button>
          </div>
        </div>
      </Teleport>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
const restrictionDialogMessage = ref('')
const goodsId = computed(() => String(route.params.id || ''))
const maxQuantity = computed(() => Math.max(1, goods.value?.maxBuy || 1))
const restrictionReason = computed(() => {
  if (!goods.value) return ''
  if (!goods.value.canBuy) return goods.value.buyRestrictionReason || '该商品当前暂无法购买。'
  if (quantity.value > maxQuantity.value) return `该商品单次最多购买 ${maxQuantity.value} 件。`
  return ''
})
const buyButtonLabel = computed(() => {
  if (submitting.value) return '提交中...'
  if (!session.isLoggedIn) return '登录后购买'
  if (goods.value?.soldOut) return '已售罄'
  if (restrictionReason.value) return '暂无法购买'
  return '立即下单'
})

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

watch([goods, quantity], () => {
  clampQuantity()
})

async function submitOrder() {
  if (!session.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (!goods.value) return
  clampQuantity()
  if (restrictionReason.value) {
    restrictionDialogMessage.value = restrictionReason.value
    return
  }
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
      quantity: clampQuantity(),
      rechargeAccount: account,
      buyerRemark: buyerRemark.value,
      requestId: `web_${Date.now()}_${goods.value.id}`,
      terminal: 'web'
    })
    await router.push(`/orders/${order.orderNo}`)
  } catch (err) {
    const message = getApiErrorMessage(err)
    error.value = message
    restrictionDialogMessage.value = message
  } finally {
    submitting.value = false
  }
}

function clampQuantity() {
  const parsed = Number(quantity.value)
  const nextValue = Number.isFinite(parsed) ? Math.trunc(parsed) : 1
  quantity.value = Math.min(maxQuantity.value, Math.max(1, nextValue))
  return quantity.value
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
</script>

<style scoped>
.limit-dialog {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(3, 8, 18, 0.66);
  backdrop-filter: blur(14px);
}

.limit-dialog-card {
  width: min(420px, 100%);
  padding: 28px;
  border-radius: 24px;
  color: rgba(255, 255, 255, 0.9);
  text-align: center;
  background: linear-gradient(145deg, rgba(11, 25, 45, 0.94), rgba(6, 35, 36, 0.9));
  border: 1px solid rgba(0, 255, 195, 0.18);
  box-shadow: 0 30px 90px rgba(0, 0, 0, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.limit-dialog-card span {
  display: inline-flex;
  padding: 5px 12px;
  border-radius: 999px;
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border: 1px solid rgba(0, 255, 195, 0.2);
  font-size: 12px;
  font-weight: 800;
}

.limit-dialog-card strong {
  display: block;
  margin-top: 14px;
  font-size: 22px;
}

.limit-dialog-card p {
  margin: 12px 0 0;
  color: rgba(214, 226, 240, 0.74);
  line-height: 1.8;
}

.limit-dialog-card button {
  width: 100%;
  height: 46px;
  margin-top: 22px;
  color: #04110e;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  font-weight: 800;
  cursor: pointer;
}
</style>
