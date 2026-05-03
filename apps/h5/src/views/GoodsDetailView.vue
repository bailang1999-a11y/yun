<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, LoaderCircle, ShieldCheck } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { createH5Order, fetchH5GoodsDetail, fetchH5RechargeFields } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import { useCatalogStore } from '../stores/catalog'
import type { GoodsCard, GoodsType, RechargeField } from '../types/h5'

const route = useRoute()
const router = useRouter()
const catalog = useCatalogStore()
const goods = ref<GoodsCard | null>(null)
const rechargeFields = ref<RechargeField[]>([])
const loading = ref(false)
const creating = ref(false)
const errorMessage = ref('')
const rechargeAccount = ref('')
const quantity = ref(1)

const typeLabel: Record<GoodsType, string> = {
  CARD: '卡密兑换',
  DIRECT: '自动充值',
  MANUAL: '人工充值'
}

const totalAmount = computed(() => (goods.value ? goods.value.price * quantity.value : 0))
const selectedRechargeFields = computed(() => {
  if (!goods.value?.accountTypes?.length) return []
  const selectedCodes = new Set(goods.value.accountTypes)
  return rechargeFields.value.filter((item) => selectedCodes.has(item.code))
})
const primaryRechargeField = computed(() => selectedRechargeFields.value[0])
const rechargeAccountLabel = computed(() => primaryRechargeField.value?.label || '充值账号')
const rechargeAccountPlaceholder = computed(() => {
  if (primaryRechargeField.value?.placeholder) return primaryRechargeField.value.placeholder
  if (selectedRechargeFields.value.length) return `请输入${selectedRechargeFields.value.map((item) => item.label).join(' / ')}`
  return '手机号 / QQ / 游戏账号'
})
const forbiddenPlatformText = computed(() => {
  const platforms = goods.value?.forbiddenPlatforms || []
  if (!platforms.length) return ''
  return platforms.map(platformLabel).join('、')
})

onMounted(async () => {
  if (!catalog.categories.length) {
    await catalog.loadCatalog()
  }
  await loadRechargeFields()
  await loadGoods()
})

async function loadRechargeFields() {
  try {
    rechargeFields.value = await fetchH5RechargeFields()
  } catch {
    rechargeFields.value = []
  }
}

async function loadGoods() {
  loading.value = true
  errorMessage.value = ''

  try {
    goods.value = await fetchH5GoodsDetail(String(route.params.id), catalog.categories)
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

async function createOrder() {
  if (!goods.value || creating.value) return
  if (goods.value.requireRechargeAccount && !rechargeAccount.value.trim()) {
    errorMessage.value = '请先填写充值账号。'
    return
  }
  const validationMessage = validateRechargeAccount(rechargeAccount.value.trim())
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }

  creating.value = true
  errorMessage.value = ''

  try {
    const order = await createH5Order({
      goodsId: goods.value.id,
      quantity: quantity.value,
      rechargeAccount: rechargeAccount.value.trim() || undefined,
      requestId: `h5_${Date.now()}_${goods.value.id}`
    })
    await router.push({ path: `/checkout/${order.orderNo}` })
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    creating.value = false
  }
}

function platformLabel(value: string) {
  const labels: Record<string, string> = {
    h5: '移动 H5',
    pc: 'PC 端',
    miniapp: '微信小程序'
  }
  return labels[value] || value
}

function validateRechargeAccount(value: string) {
  if (!goods.value?.requireRechargeAccount || !value || !selectedRechargeFields.value.length) return ''
  const matched = selectedRechargeFields.value.some((field) => accountMatches(field.inputType, value))
  if (matched) return ''
  return `请输入正确的${selectedRechargeFields.value.map((item) => item.label).join(' / ')}`
}

function accountMatches(inputType: string, value: string) {
  switch (inputType) {
    case 'mobile':
      return /^1[3-9]\d{9}$/.test(value)
    case 'email':
      return /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(value)
    case 'number':
      return /^\d+$/.test(value)
    case 'qq':
      return /^[1-9]\d{4,11}$/.test(value)
    case 'jianying_id':
      return /^[A-Za-z0-9_-]{4,32}$/.test(value)
    case 'douyin_id':
      return /^[A-Za-z0-9_.-]{4,32}$/.test(value)
    default:
      return value.trim().length > 0
  }
}
</script>

<template>
  <main class="page page-pad">
    <header class="detail-head liquid-surface">
      <button type="button" aria-label="返回" @click="router.back()"><ArrowLeft :size="18" /></button>
      <span>商品详情</span>
    </header>

    <section v-if="loading" class="state-card liquid-surface">
      <LoaderCircle class="spin" :size="18" />
      正在加载商品
    </section>
    <section v-else-if="errorMessage" class="notice danger">{{ errorMessage }}</section>

    <template v-if="goods">
      <section class="product-hero liquid-surface">
        <div class="cover" :data-type="goods.type">{{ goods.cover }}</div>
        <div>
          <span class="type-pill">{{ typeLabel[goods.type] }}</span>
          <h1>{{ goods.name }}</h1>
          <p>{{ goods.faceValue }} · {{ goods.stockLabel }}</p>
          <strong class="metal-price">¥{{ goods.price.toFixed(2) }}</strong>
        </div>
      </section>

      <section class="buy-panel liquid-surface">
        <div class="row">
          <span>购买数量</span>
          <div class="stepper">
            <button type="button" :disabled="quantity <= 1" @click="quantity--">-</button>
            <strong>{{ quantity }}</strong>
            <button type="button" :disabled="quantity >= 9" @click="quantity++">+</button>
          </div>
        </div>

        <label v-if="goods.requireRechargeAccount" class="account-field">
          <span>{{ rechargeAccountLabel }}</span>
          <input v-model.trim="rechargeAccount" :placeholder="rechargeAccountPlaceholder" />
        </label>

        <div class="service-line">
          <ShieldCheck :size="16" />
          <span>支付后按商品类型自动发卡、直充采购或进入人工处理。</span>
        </div>

        <p v-if="forbiddenPlatformText" class="platform-warning">
          该商品不支持在以下平台使用：{{ forbiddenPlatformText }}
        </p>

        <div class="settlement">
          <span>应付</span>
          <strong class="metal-price">¥{{ totalAmount.toFixed(2) }}</strong>
        </div>

        <button class="primary-action" type="button" :disabled="creating" @click="createOrder">
          <span v-if="creating" class="blue-swirl" />
          {{ creating ? '生成订单中' : '立即购买' }}
        </button>
      </section>

      <section class="description liquid-surface">
        <h2>商品说明</h2>
        <p>本商品支持当前平台购买。卡密商品支付成功后自动展示卡号与密码；直充商品会进入上游采购流程；代充商品由后台人工确认完成。</p>
      </section>
    </template>

    <AppTabbar />
  </main>
</template>

<style scoped>
.detail-head,
.product-hero,
.buy-panel,
.description,
.state-card {
  border-radius: 24px;
}

.detail-head {
  height: 48px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: rgba(255, 255, 255, 0.86);
}

.detail-head button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: inherit;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
}

.product-hero {
  display: grid;
  grid-template-columns: 104px 1fr;
  gap: 14px;
  margin-top: 12px;
  padding: 16px;
  overflow: hidden;
}

.cover {
  width: 104px;
  height: 104px;
  display: grid;
  place-items: center;
  border-radius: 28px;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.68), rgba(10, 77, 80, 0.82));
  box-shadow: inset 0 1px 18px rgba(255, 255, 255, 0.2), 0 16px 34px rgba(0, 0, 0, 0.26);
}

.cover[data-type="DIRECT"] {
  background: linear-gradient(135deg, rgba(88, 166, 255, 0.76), rgba(37, 99, 235, 0.48));
}

.cover[data-type="MANUAL"] {
  background: linear-gradient(135deg, rgba(255, 171, 0, 0.7), rgba(154, 91, 19, 0.5));
}

.type-pill {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 999px;
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  font-size: 12px;
}

h1,
h2,
p {
  margin: 0;
}

h1 {
  margin-top: 10px;
  color: rgba(255, 255, 255, 0.92);
  font-size: 21px;
  line-height: 1.28;
}

.product-hero p,
.description p,
.service-line,
.row span,
.account-field span,
.settlement span {
  color: rgba(255, 255, 255, 0.52);
}

.product-hero strong {
  display: block;
  margin-top: 12px;
  font-size: 32px;
}

.buy-panel,
.description {
  margin-top: 12px;
  padding: 16px;
}

.row,
.settlement {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.stepper {
  display: inline-grid;
  grid-template-columns: 36px 42px 36px;
  align-items: center;
  height: 36px;
  overflow: hidden;
  border-radius: 999px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
}

.stepper button {
  height: 36px;
  color: rgba(255, 255, 255, 0.82);
  border: 0;
  background: transparent;
}

.stepper strong {
  color: #fff;
  text-align: center;
}

.account-field {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.account-field input {
  height: 44px;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.88);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  outline: none;
  background: rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(18px);
}

.service-line {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  line-height: 1.5;
  font-size: 13px;
}

.platform-warning {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 16px;
  color: rgba(255, 220, 165, 0.9);
  background: rgba(255, 171, 0, 0.08);
  border: 0.5px solid rgba(255, 171, 0, 0.2);
}

.settlement {
  margin-top: 16px;
}

.settlement strong {
  font-size: 30px;
}

.primary-action {
  width: 100%;
  height: 48px;
  margin-top: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #06100e;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  box-shadow: 0 18px 42px rgba(0, 255, 195, 0.2);
  font-weight: 700;
}

.primary-action:active {
  transform: scale(0.98);
}

.description h2 {
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 17px;
}

.notice,
.state-card {
  margin-top: 12px;
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
