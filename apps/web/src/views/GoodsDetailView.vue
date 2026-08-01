<template>
  <WebShell>
    <section class="detail-page checkout-page">
      <RouterLink class="checkout-back back-link" to="/">
        <ArrowLeft :size="17" stroke-width="2.4" />
        <span>返回商品列表</span>
      </RouterLink>

      <div v-if="loading" class="detail-grid checkout-shell">
        <div class="skeleton-card tall" />
        <div class="skeleton-card tall" />
      </div>

      <div v-else-if="goods" class="checkout-shell checkout-flow">
        <section class="product-media checkout-hero">
          <div class="checkout-cover-shell">
            <div class="detail-cover checkout-cover">
              <img v-if="goods.coverUrl" :src="goods.coverUrl" :alt="goods.name" />
              <span v-else>{{ goods.cover }}</span>
            </div>
          </div>

          <div class="checkout-hero-main">
            <div class="checkout-title-kicker">
              <span>{{ goods.category }}</span>
              <span v-if="goods.benefitBrand">{{ goods.benefitBrand }}</span>
              <span v-if="goods.priceLimitText">限价 {{ goods.priceLimitText }}</span>
            </div>
            <h1>{{ goods.name }}</h1>
            <p class="muted">{{ goods.faceValue }}</p>

            <div class="goods-tags checkout-tags">
              <span v-for="tag in goods.tags || []" :key="`custom-${tag}`" class="goods-tag tag-custom">{{ tag }}</span>
              <span v-for="duration in goods.benefitDurations || []" :key="`duration-${duration}`" class="goods-tag tag-time">
                {{ duration }}
              </span>
              <span v-if="goods.benefitType" class="goods-tag tag-type">{{ goods.benefitType }}</span>
              <span v-if="goods.benefitBrand" class="goods-tag tag-brand">{{ goods.benefitBrand }}</span>
              <span v-for="platform in goods.availablePlatforms || []" :key="`sale-${platform}`" class="goods-tag tag-sale">
                {{ platformLabel(platform) }}
              </span>
              <span v-for="platform in goods.forbiddenPlatforms || []" :key="`deny-${platform}`" class="goods-tag tag-deny">
                禁 {{ platformLabel(platform) }}
              </span>
            </div>
          </div>

          <div class="checkout-hero-side">
            <div class="price-line checkout-price-line">
              <span>购买价</span>
              <strong>¥{{ formatMoney(goods.price) }}</strong>
              <del v-if="goods.originalPrice">¥{{ formatMoney(goods.originalPrice) }}</del>
            </div>
            <div class="checkout-hero-facts">
              <div>
                <span>库存</span>
                <strong>{{ goods.stockLabel }}</strong>
              </div>
              <div>
                <span>类型</span>
                <strong>{{ typeText }}</strong>
              </div>
              <div>
                <span>限购</span>
                <strong>{{ goods.maxBuy > 1 ? `${goods.maxBuy} 件` : '单件' }}</strong>
              </div>
            </div>
          </div>
        </section>

        <div class="checkout-workspace">
          <section class="order-panel checkout-order checkout-form-panel">
            <section v-if="goods.requireRechargeAccount || sortedRechargeFields.length" class="checkout-section">
              <div class="checkout-section-head">
                <span><UserRound :size="16" stroke-width="2.4" />账号信息</span>
                <em v-if="sortedRechargeFields.length">{{ filledRechargeCount }}/{{ sortedRechargeFields.length }}</em>
              </div>

              <div class="field-stack checkout-field-grid">
                <label v-for="field in sortedRechargeFields" :key="field.id || field.code" class="checkout-field">
                  <span>
                    {{ field.label || '充值账号' }}
                    <b v-if="field.required">*</b>
                  </span>
                  <input
                    :id="`recharge-field-${field.code}`"
                    v-model.trim="rechargeValues[field.code]"
                    :type="fieldInputType(field)"
                    :placeholder="field.placeholder || '请输入充值账号'"
                    :required="field.required"
                    :aria-describedby="field.helpText ? `recharge-field-help-${field.code}` : undefined"
                  />
                  <small v-if="field.helpText" :id="`recharge-field-help-${field.code}`">{{ field.helpText }}</small>
                </label>
                <label v-if="!sortedRechargeFields.length" class="checkout-field checkout-field-wide">
                  <span>充值账号</span>
                  <input v-model.trim="rechargeAccount" placeholder="请输入充值账号" />
                </label>
              </div>
            </section>

            <section class="checkout-section checkout-remark-section">
              <div class="checkout-section-head">
                <span><FileText :size="16" stroke-width="2.4" />订单备注</span>
                <em>选填</em>
              </div>
              <textarea v-model.trim="buyerRemark" rows="3" placeholder="订单备注（选填）" />
            </section>
          </section>

          <aside class="checkout-summary-panel" aria-label="下单结算">
            <div class="checkout-summary-head">
              <span>结算</span>
              <strong>{{ typeText }}</strong>
            </div>

            <div class="quantity-row checkout-quantity" role="group" aria-labelledby="checkout-quantity-label">
              <span id="checkout-quantity-label">数量</span>
              <div class="quantity-control">
                <button type="button" :disabled="quantity <= 1" aria-label="减少数量" @click="changeQuantity(-1)">
                  <Minus :size="16" stroke-width="2.6" />
                </button>
                <input
                  v-model.number="quantity"
                  min="1"
                  :max="maxQuantity"
                  type="number"
                  inputmode="numeric"
                  aria-labelledby="checkout-quantity-label"
                  @blur="clampQuantity"
                  @input="clampQuantity"
                />
                <button type="button" :disabled="quantity >= maxQuantity" aria-label="增加数量" @click="changeQuantity(1)">
                  <Plus :size="16" stroke-width="2.6" />
                </button>
              </div>
            </div>

            <div class="checkout-mini-facts">
              <div>
                <span>商品</span>
                <strong>{{ goods.name }}</strong>
              </div>
              <div>
                <span>单价</span>
                <strong>¥{{ formatMoney(goods.price) }}</strong>
              </div>
              <div>
                <span>库存</span>
                <strong>{{ goods.stockLabel }}</strong>
              </div>
            </div>

            <p v-if="error" class="alert-line checkout-alert" role="alert">{{ error }}</p>
            <p v-if="restrictionReason" class="alert-line checkout-alert">{{ restrictionReason }}</p>

            <footer class="checkout-submit">
              <div class="checkout-total">
                <span>应付金额</span>
                <strong>¥{{ formatMoney(orderTotal) }}</strong>
                <small v-if="quantity > 1">¥{{ formatMoney(goods.price) }} × {{ quantity }}</small>
                <small v-else-if="goods.maxBuy > 1">单次最多 {{ goods.maxBuy }} 件</small>
              </div>

              <button class="primary-button wide checkout-submit-button" type="button" :disabled="submitting || Boolean(restrictionReason)" @click="submitOrder">
                <ShieldCheck :size="18" stroke-width="2.5" />
                <span>{{ buyButtonLabel }}</span>
              </button>
            </footer>
          </aside>
        </div>
      </div>

      <template v-else-if="loadError">
        <p class="alert-line" role="alert">{{ loadError }}</p>
        <EmptyState title="商品加载失败" description="服务暂时不可用，请稍后重试。" />
        <div class="detail-retry-row">
          <button class="ghost-button" type="button" :disabled="loading" @click="loadDetail">重新加载</button>
        </div>
      </template>

      <EmptyState v-else title="商品不存在" description="请返回列表重新选择。" />

      <Teleport to="body">
        <div
          v-if="restrictionDialogMessage"
          ref="restrictionDialogRef"
          class="limit-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="restriction-dialog-title"
          tabindex="-1"
        >
          <div class="limit-dialog-card">
            <span>下单限制</span>
            <strong id="restriction-dialog-title">暂无法购买该商品</strong>
            <p>{{ restrictionDialogMessage }}</p>
            <button type="button" @click="closeRestrictionDialog">我知道了</button>
          </div>
        </div>
      </Teleport>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, FileText, Minus, Plus, ShieldCheck, UserRound } from 'lucide-vue-next'
import WebShell from '../components/WebShell.vue'
import EmptyState from '../components/EmptyState.vue'
import { createOrder, fetchGoodsDetail, fetchOrderByRequestId, fetchRechargeFields } from '../api/web'
import { getApiErrorMessage, isAmbiguousRequestError, isNotFoundError } from '../api/client'
import { useCatalogStore } from '../stores/catalog'
import { useSessionStore } from '../stores/session'
import type { CreateOrderPayload, GoodsItem, OrderItem, RechargeField } from '../types/web'
import { formatMoney } from '../utils/formatters'
import { useModalFocus } from '../utils/modalFocus'
import { clearPendingOrderRequest, getPendingOrderRequestId } from '../utils/pendingOrderRequest'

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
const confirmingOrder = ref(false)
const error = ref('')
const loadError = ref('')
const restrictionDialogMessage = ref('')
const restrictionDialogRef = ref<HTMLElement | null>(null)
useModalFocus(computed(() => Boolean(restrictionDialogMessage.value)), restrictionDialogRef, closeRestrictionDialog)
const goodsId = computed(() => String(route.params.id || ''))
const maxQuantity = computed(() => Math.max(1, goods.value?.maxBuy || 1))
const typeText = computed(() => {
  if (!goods.value) return ''
  if (goods.value.type === 'CARD') return '卡密'
  if (goods.value.type === 'DIRECT') return '直充'
  return '代充'
})
const sortedRechargeFields = computed(() => {
  const selectedCodes = new Set(goods.value?.accountTypes || [])
  return rechargeFields.value
    .filter((field) => selectedCodes.has(field.code))
    .sort((a, b) => (a.sort || 0) - (b.sort || 0))
})
const filledRechargeCount = computed(() =>
  sortedRechargeFields.value.filter((field) => rechargeValues[field.code]?.trim()).length
)
const orderTotal = computed(() => Number((Number(goods.value?.price || 0) * quantity.value).toFixed(2)))
const restrictionReason = computed(() => {
  if (!goods.value) return ''
  if (!goods.value.canBuy) return goods.value.buyRestrictionReason || '该商品当前暂无法购买。'
  if (quantity.value > maxQuantity.value) return `该商品单次最多购买 ${maxQuantity.value} 件。`
  return ''
})
const buyButtonLabel = computed(() => {
  if (submitting.value) return confirmingOrder.value ? '正在确认订单...' : '提交中...'
  if (!session.isLoggedIn) return '登录后购买'
  if (goods.value?.soldOut) return '已售罄'
  if (restrictionReason.value) return '暂无法购买'
  return '立即下单'
})

async function loadDetail() {
  loading.value = true
  loadError.value = ''
  try {
    if (!catalog.categories.length) await catalog.loadCatalog()
    goods.value = await fetchGoodsDetail(goodsId.value, catalog.categories)
    rechargeFields.value = await fetchRechargeFields()
  } catch (err) {
    goods.value = null
    rechargeFields.value = []
    // 后端明确返回找不到时按“商品不存在”展示，其余按加载失败展示并允许重试。
    loadError.value = isNotFoundError(err) ? '' : getApiErrorMessage(err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadDetail()
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
  const requiredFields = sortedRechargeFields.value.filter((field) => field.required)
  const anyRequiredFilled = requiredFields.some((field) => rechargeValues[field.code]?.trim())
  if (requiredFields.length > 0 && !anyRequiredFilled) {
    const labels = requiredFields.map((f) => f.label || '充值信息').join(' / ')
    error.value = `请填写充值账号（${labels} 任填一项）。`
    return
  }
  const structuredRechargeFields = Object.fromEntries(
    sortedRechargeFields.value
      .map((field) => [field.code, rechargeValues[field.code]?.trim() || ''] as const)
      .filter(([, value]) => value)
  )
  const firstFieldValue = Object.values(structuredRechargeFields)[0]
  const account = firstFieldValue || rechargeAccount.value
  if (goods.value.requireRechargeAccount && !account) {
    error.value = '请填写充值账号。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const orderPayload: Omit<CreateOrderPayload, 'requestId'> = {
      goodsId: goods.value.id,
      quantity: clampQuantity(),
      rechargeAccount: account,
      rechargeFields: structuredRechargeFields,
      buyerRemark: buyerRemark.value,
      terminal: 'web'
    }
    const requestId = await getPendingOrderRequestId(orderPayload)
    const order = await createOrderWithRecovery({ ...orderPayload, requestId })
    if (!order) {
      error.value = '订单结果暂未确认，请稍后重试或在我的订单中查看。'
      return
    }
    clearPendingOrderRequest('web', requestId)
    await router.push(`/orders/${order.orderNo}`)
  } catch (err) {
    const message = getApiErrorMessage(err)
    error.value = message
    restrictionDialogMessage.value = message
  } finally {
    submitting.value = false
  }
}

async function createOrderWithRecovery(payload: CreateOrderPayload): Promise<OrderItem | null> {
  try {
    return await createOrder(payload)
  } catch (error) {
    if (!isAmbiguousRequestError(error)) {
      clearPendingOrderRequest('web', payload.requestId)
      throw error
    }
  }

  const recovered = await confirmCreatedOrder(payload.requestId)
  if (recovered) return recovered

  try {
    return await createOrder(payload)
  } catch (error) {
    if (!isAmbiguousRequestError(error)) {
      clearPendingOrderRequest('web', payload.requestId)
      throw error
    }
    return confirmCreatedOrder(payload.requestId)
  }
}

async function confirmCreatedOrder(requestId: string): Promise<OrderItem | null> {
  confirmingOrder.value = true
  try {
    for (const delay of [0, 400, 800, 1600]) {
      if (delay) await wait(delay)
      try {
        const order = await fetchOrderByRequestId(requestId)
        if (order) return order
      } catch {
        // A failed confirmation is inconclusive; the same requestId is retried below.
      }
    }
    return null
  } finally {
    confirmingOrder.value = false
  }
}

function wait(delay: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, delay))
}

function closeRestrictionDialog() {
  restrictionDialogMessage.value = ''
}

function clampQuantity() {
  const parsed = Number(quantity.value)
  const nextValue = Number.isFinite(parsed) ? Math.trunc(parsed) : 1
  quantity.value = Math.min(maxQuantity.value, Math.max(1, nextValue))
  return quantity.value
}

function changeQuantity(step: number) {
  quantity.value += step
  clampQuantity()
}

function fieldInputType(field: RechargeField) {
  const value = field.inputType?.toLowerCase()
  if (value === 'tel' || value === 'email') return value
  if (/mobile|phone|tel/.test(`${field.code} ${field.label}`.toLowerCase())) return 'tel'
  return 'text'
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
.checkout-page {
  width: min(100%, 1360px);
  margin: 0 auto;
  gap: 18px;
}

.detail-retry-row {
  display: flex;
  justify-content: center;
}

.checkout-back {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid color-mix(in oklch, var(--glass-edge), transparent 18%);
  border-radius: 999px;
  background: color-mix(in oklch, var(--glass), transparent 26%);
  box-shadow: inset 0 1px 0 oklch(100% 0.004 250 / 0.36);
  backdrop-filter: blur(18px) saturate(170%);
  -webkit-backdrop-filter: blur(18px) saturate(170%);
}

.checkout-shell {
  gap: 20px;
  align-items: start;
}

.checkout-workbench {
  grid-template-columns: minmax(250px, 0.72fr) minmax(410px, 1fr) minmax(300px, 360px);
  gap: 18px;
}

.checkout-visual,
.checkout-order,
.checkout-summary-panel {
  min-height: 0;
  min-width: 0;
  border-radius: 22px;
}

.checkout-visual::before,
.checkout-order::before,
.checkout-summary-panel::before {
  display: none;
}

.checkout-visual {
  position: sticky;
  top: 18px;
  display: grid;
  grid-template-rows: auto auto auto;
  gap: 16px;
  padding: 18px;
  border: 1px solid oklch(100% 0.004 260 / 0.18);
  background:
    radial-gradient(circle at 50% 26%, oklch(68% 0.13 184 / 0.16), transparent 46%),
    linear-gradient(150deg, oklch(100% 0.004 250 / 0.14), oklch(92% 0.04 250 / 0.04)),
    var(--glass);
  box-shadow:
    0 24px 70px oklch(13% 0.045 265 / 0.18),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.24);
  backdrop-filter: blur(26px) saturate(170%) brightness(1.04);
  -webkit-backdrop-filter: blur(26px) saturate(170%) brightness(1.04);
}

.checkout-visual-head,
.checkout-visual-foot,
.checkout-submit,
.checkout-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.checkout-visual-head {
  min-height: 36px;
  padding: 0;
  min-width: 0;
}

.checkout-visual-head span,
.checkout-section-head em,
.checkout-total span,
.checkout-price-line span,
.checkout-visual-foot span,
.checkout-title-kicker span {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 850;
}

.checkout-brand-chip {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  padding: 0 10px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  border-radius: 999px;
  background: oklch(13% 0.035 268 / 0.48);
  box-shadow: none;
}

.checkout-visual-head strong {
  min-width: 0;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 16px;
  font-weight: 950;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout-cover-shell {
  display: grid;
  min-height: 220px;
  place-items: center;
  padding: 16px;
  border: 0;
  border-radius: 20px;
  background:
    radial-gradient(circle at 52% 46%, oklch(70% 0.14 184 / 0.2), transparent 42%),
    radial-gradient(circle at 26% 8%, oklch(100% 0.004 260 / 0.16), transparent 28%),
    transparent;
}

.checkout-cover {
  width: min(100%, 278px);
  max-width: 100%;
  aspect-ratio: 1 / 1;
  height: auto;
  min-height: 0;
  border: 1px solid oklch(74% 0.1 188 / 0.34);
  border-radius: 18px;
  background: oklch(5% 0.02 268 / 0.88);
  box-shadow:
    0 0 0 7px oklch(100% 0.004 260 / 0.08),
    0 28px 64px oklch(2% 0.02 270 / 0.28);
}

.checkout-cover img {
  object-fit: contain;
  padding: 0;
  background: oklch(8% 0.035 270 / 0.1);
}

.checkout-cover:has(img)::after {
  background:
    linear-gradient(180deg, oklch(100% 0.004 260 / 0.04), transparent 18%, transparent 82%, oklch(2% 0.02 270 / 0.16));
  opacity: 0.54;
}

.checkout-visual-foot {
  display: grid;
  gap: 0;
  padding: 2px 0 0;
}

.checkout-visual-foot > div {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.12);
  background: transparent;
  box-shadow: none;
}

.checkout-visual-foot > div + div::before {
  content: none;
}

.checkout-visual-foot strong {
  display: inline;
  max-width: 100%;
  margin-top: 0;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 14px;
  font-weight: 850;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout-order {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 26px;
  border: 1px solid oklch(100% 0.004 260 / 0.14);
  border-radius: 22px;
  background:
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.72), oklch(11% 0.032 268 / 0.78)),
    oklch(11% 0.032 268 / 0.76);
  box-shadow:
    0 28px 84px oklch(2% 0.02 270 / 0.24),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.1);
  backdrop-filter: blur(18px) saturate(145%);
  -webkit-backdrop-filter: blur(18px) saturate(145%);
}

.checkout-summary-panel {
  position: sticky;
  top: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 78% 0%, oklch(72% 0.16 28 / 0.16), transparent 34%),
    radial-gradient(circle at 10% 18%, oklch(72% 0.14 236 / 0.12), transparent 38%),
    linear-gradient(180deg, oklch(100% 0.004 250 / 0.14), oklch(88% 0.04 250 / 0.05)),
    var(--glass);
  box-shadow:
    0 28px 78px oklch(13% 0.045 265 / 0.2),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.22);
  backdrop-filter: blur(28px) saturate(175%) brightness(1.05);
  -webkit-backdrop-filter: blur(28px) saturate(175%) brightness(1.05);
}

.checkout-summary-head {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.checkout-summary-head span,
.checkout-mini-facts span,
.checkout-quantity > span {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 850;
}

.checkout-summary-head strong {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border: 1px solid oklch(100% 0.004 260 / 0.18);
  border-radius: 999px;
  color: var(--accent-ink);
  background: oklch(100% 0.004 260 / 0.1);
  font-size: 12px;
  font-weight: 950;
}

.checkout-title {
  display: grid;
  gap: 12px;
  padding: 0;
}

.checkout-title-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.checkout-title-kicker span {
  display: inline-flex;
  min-height: 26px;
  align-items: center;
  padding: 0 9px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  border-radius: 999px;
  background: oklch(100% 0.004 260 / 0.07);
  box-shadow: inset 0 1px 0 oklch(100% 0.004 260 / 0.12);
}

.checkout-title h1 {
  color: var(--page-ink);
  font-size: 32px;
  line-height: 1.16;
  overflow-wrap: anywhere;
}

.checkout-tags {
  max-height: none;
  margin-top: 0;
  overflow: visible;
}

.checkout-tags .goods-tag {
  box-shadow: none;
}

.checkout-deal {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: stretch;
  gap: 18px;
  padding: 18px 0 16px;
  border: 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.1);
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.1);
  border-radius: 0;
  background:
    linear-gradient(90deg, oklch(72% 0.16 28 / 0.08), transparent 46%);
  box-shadow: none;
}

.checkout-deal p {
  align-self: end;
  margin: 0;
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 850;
  white-space: nowrap;
}

.checkout-price-line {
  display: grid;
  align-content: center;
  gap: 4px;
}

.checkout-price-line strong {
  color: oklch(72% 0.16 28);
  font-size: 42px;
  line-height: 1;
}

.checkout-price-line del {
  color: oklch(64% 0.032 268 / 0.58);
  font-size: 13px;
}

.checkout-quantity {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-content: center;
  align-items: center;
  justify-items: stretch;
  gap: 12px;
  padding: 14px 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.12);
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.12);
}

.checkout-quantity > span {
  margin: 0;
}

.quantity-control {
  display: grid;
  grid-template-columns: 38px 70px 38px;
  align-items: center;
  min-height: 42px;
  overflow: hidden;
  border: 1px solid oklch(100% 0.004 260 / 0.18);
  border-radius: 16px;
  background:
    linear-gradient(180deg, oklch(100% 0.004 260 / 0.1), oklch(18% 0.045 268 / 0.28)),
    oklch(9% 0.03 268 / 0.18);
  box-shadow: inset 0 1px 0 oklch(100% 0.004 260 / 0.12);
}

.quantity-control button {
  height: 42px;
  border: 0;
  color: var(--accent-ink);
  background: transparent;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition:
    background 180ms var(--ease-out),
    transform 180ms var(--ease-out),
    opacity 180ms var(--ease-out);
}

.quantity-control button:hover:not(:disabled) {
  background: oklch(100% 0.004 250 / 0.24);
}

.quantity-control button:active:not(:disabled) {
  transform: scale(0.94);
}

.quantity-control button:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.quantity-control input {
  height: 42px;
  padding: 0;
  border: 0;
  border-radius: 0;
  text-align: center;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.quantity-control input::-webkit-outer-spin-button,
.quantity-control input::-webkit-inner-spin-button {
  margin: 0;
  appearance: none;
}

.checkout-divider {
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--glass-edge), transparent);
}

.checkout-section {
  display: grid;
  gap: 12px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.checkout-section-head span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--page-ink);
  font-size: 14px;
  font-weight: 950;
}

.checkout-section-head em {
  font-style: normal;
}

.checkout-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.checkout-field {
  min-width: 0;
  margin: 0;
}

.checkout-field span {
  display: flex;
  align-items: center;
  gap: 3px;
}

.checkout-field b {
  color: var(--coral);
}

.checkout-field small {
  display: block;
  margin-top: 6px;
  line-height: 1.45;
}

.checkout-field-wide,
.checkout-remark-section {
  grid-column: 1 / -1;
}

.checkout-remark-section textarea {
  min-height: 76px;
}

.checkout-alert {
  margin: 0;
  border-color: oklch(78% 0.12 42 / 0.35);
  color: oklch(58% 0.13 42);
  background:
    linear-gradient(180deg, oklch(88% 0.08 42 / 0.16), oklch(82% 0.06 42 / 0.08)),
    transparent;
}

.checkout-mini-facts {
  display: grid;
  gap: 0;
}

.checkout-mini-facts > div {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.1);
}

.checkout-mini-facts > div:first-child {
  padding-top: 0;
}

.checkout-mini-facts strong {
  min-width: 0;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 13px;
  font-weight: 900;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout-submit {
  position: static;
  align-items: stretch;
  margin-top: auto;
  padding: 16px 0 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.14);
}

.checkout-total {
  display: grid;
  align-content: center;
  gap: 5px;
  min-width: 0;
}

.checkout-total strong {
  color: oklch(72% 0.16 28);
  font-size: 30px;
  line-height: 1;
}

.checkout-total small {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 800;
}

.checkout-submit-button {
  min-width: 0;
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

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

:global(:root[data-theme="dark"]) .checkout-visual {
  border-color: oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 50% 26%, oklch(56% 0.13 176 / 0.12), transparent 46%),
    linear-gradient(150deg, oklch(31% 0.06 268 / 0.44), oklch(13% 0.032 268 / 0.5)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .checkout-summary-panel {
  border-color: oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 78% 0%, oklch(72% 0.16 28 / 0.14), transparent 34%),
    radial-gradient(circle at 10% 18%, oklch(72% 0.14 236 / 0.1), transparent 38%),
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.66), oklch(11% 0.032 268 / 0.76)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .checkout-order {
  border-color: oklch(100% 0.004 260 / 0.14);
  background:
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.72), oklch(11% 0.032 268 / 0.78)),
    oklch(11% 0.032 268 / 0.76);
}

:global(:root[data-theme="dark"]) .checkout-cover-shell {
  border-color: transparent;
  background: transparent;
}

:global(:root[data-theme="dark"]) .checkout-deal {
  border-color: oklch(100% 0.004 260 / 0.1);
  background:
    linear-gradient(90deg, oklch(72% 0.16 28 / 0.08), transparent 46%);
}

:global(:root[data-theme="dark"]) .checkout-section {
  border: 0;
  background: transparent;
}

:global(:root[data-theme="dark"]) .checkout-submit {
  border-color: oklch(100% 0.004 260 / 0.14);
  background: transparent;
}

:global(:root[data-theme="dark"]) .checkout-title-kicker span {
  border-color: oklch(100% 0.004 260 / 0.16);
  background: oklch(100% 0.004 260 / 0.07);
}

:global(:root[data-theme="dark"]) .quantity-control {
  border-color: oklch(100% 0.004 260 / 0.22);
  background:
    linear-gradient(180deg, oklch(42% 0.06 268 / 0.38), oklch(22% 0.05 270 / 0.5)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .quantity-control input {
  background: transparent;
}

@media (max-width: 1320px) {
  .checkout-workbench {
    grid-template-columns: minmax(220px, 0.66fr) minmax(360px, 1fr) minmax(286px, 320px);
    gap: 14px;
  }

  .checkout-order {
    padding: 22px;
  }

  .checkout-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-deal {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-deal p {
    align-self: start;
  }
}

@media (max-width: 1200px) {
  .checkout-page {
    width: 100%;
  }

  .checkout-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-visual {
    grid-template-rows: auto auto auto;
    min-height: 0;
    max-width: none;
    margin: 0 auto;
  }

  .checkout-visual,
  .checkout-summary-panel {
    position: static;
  }

  .checkout-summary-panel {
    order: 3;
  }

  .checkout-cover {
    width: min(100%, 320px);
  }
}

@media (max-width: 760px) {
  .checkout-visual,
  .checkout-order,
  .checkout-summary-panel {
    padding: 16px;
    border-radius: 22px;
  }

  .checkout-visual {
    padding: 0;
  }

  .checkout-visual-foot,
  .checkout-submit {
    align-items: stretch;
    flex-direction: column;
  }

  .checkout-visual-foot {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-deal {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-cover-shell {
    padding: 8px;
  }

  .checkout-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-quantity {
    grid-template-columns: minmax(0, 1fr);
    justify-items: stretch;
  }

  .quantity-control {
    grid-template-columns: 44px minmax(0, 1fr) 44px;
  }

  .checkout-title h1 {
    font-size: 25px;
  }
}

.checkout-page {
  width: min(100%, 1320px);
}

.checkout-flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
}

.checkout-hero,
.checkout-form-panel,
.checkout-summary-panel {
  min-width: 0;
  border-radius: 22px;
}

.checkout-hero::before,
.checkout-form-panel::before,
.checkout-summary-panel::before {
  display: none;
}

.checkout-hero {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) minmax(230px, 280px);
  align-items: center;
  gap: 22px;
  padding: 20px 22px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 10% 0%, oklch(72% 0.14 236 / 0.14), transparent 32%),
    radial-gradient(circle at 94% 10%, oklch(72% 0.16 28 / 0.12), transparent 34%),
    linear-gradient(135deg, oklch(100% 0.004 250 / 0.13), oklch(84% 0.04 250 / 0.04)),
    var(--glass);
  box-shadow:
    0 24px 70px oklch(7% 0.028 265 / 0.22),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.2);
  backdrop-filter: blur(28px) saturate(178%) brightness(1.04);
  -webkit-backdrop-filter: blur(28px) saturate(178%) brightness(1.04);
}

.checkout-hero .checkout-cover-shell {
  min-height: 0;
  padding: 0;
  border-radius: 18px;
  background: transparent;
}

.checkout-hero .checkout-cover {
  width: 132px;
  height: 132px;
  min-height: 0;
  border-radius: 20px;
  box-shadow:
    0 0 0 6px oklch(100% 0.004 260 / 0.07),
    0 22px 48px oklch(2% 0.02 270 / 0.28);
}

.checkout-hero .checkout-cover span {
  width: 76px;
  height: 76px;
  border-radius: 22px;
  font-size: 28px;
}

.checkout-hero-main {
  display: grid;
  min-width: 0;
  gap: 9px;
}

.checkout-title-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.checkout-title-kicker span {
  display: inline-flex;
  min-height: 26px;
  align-items: center;
  padding: 0 9px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  border-radius: 999px;
  color: var(--faint-ink);
  background: oklch(100% 0.004 260 / 0.07);
  font-size: 12px;
  font-weight: 850;
  box-shadow: inset 0 1px 0 oklch(100% 0.004 260 / 0.12);
}

.checkout-hero-main h1 {
  margin: 0;
  color: var(--page-ink);
  font-size: 30px;
  line-height: 1.18;
  overflow-wrap: anywhere;
}

.checkout-hero-main .muted {
  max-width: 70ch;
  margin: 0;
  line-height: 1.6;
}

.checkout-tags {
  max-height: none;
  margin-top: 2px;
  overflow: visible;
}

.checkout-tags .goods-tag {
  box-shadow: none;
}

.checkout-hero-side {
  display: grid;
  min-width: 0;
  gap: 12px;
  justify-self: stretch;
}

.checkout-price-line {
  display: grid;
  gap: 4px;
  justify-items: end;
}

.checkout-price-line span,
.checkout-total span,
.checkout-mini-facts span,
.checkout-quantity > span,
.checkout-section-head em {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 850;
}

.checkout-price-line strong {
  color: oklch(72% 0.16 28);
  font-size: 38px;
  line-height: 1;
}

.checkout-price-line del {
  color: oklch(64% 0.032 268 / 0.58);
  font-size: 13px;
}

.checkout-hero-facts {
  display: grid;
  gap: 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.1);
}

.checkout-hero-facts > div {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.09);
}

.checkout-hero-facts strong {
  min-width: 0;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 13px;
  font-weight: 900;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 340px);
  align-items: start;
  gap: 18px;
}

.checkout-form-panel {
  display: grid;
  gap: 18px;
  padding: 26px;
  border: 1px solid oklch(100% 0.004 260 / 0.14);
  background:
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.68), oklch(11% 0.032 268 / 0.76)),
    var(--glass);
  box-shadow:
    0 24px 72px oklch(2% 0.02 270 / 0.22),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.1);
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.checkout-section {
  display: grid;
  gap: 12px;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.checkout-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.checkout-section-head span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--page-ink);
  font-size: 14px;
  font-weight: 950;
}

.checkout-section-head em {
  font-style: normal;
}

.checkout-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.checkout-field {
  min-width: 0;
  margin: 0;
}

.checkout-field span {
  display: flex;
  align-items: center;
  gap: 3px;
}

.checkout-field b {
  color: var(--coral);
}

.checkout-field small {
  display: block;
  margin-top: 6px;
  line-height: 1.45;
}

.checkout-field-wide,
.checkout-remark-section {
  grid-column: 1 / -1;
}

.checkout-remark-section textarea {
  min-height: 104px;
}

.checkout-summary-panel {
  position: sticky;
  top: 18px;
  display: grid;
  gap: 16px;
  padding: 20px;
  border: 1px solid oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 78% 0%, oklch(72% 0.16 28 / 0.16), transparent 34%),
    radial-gradient(circle at 10% 18%, oklch(72% 0.14 236 / 0.12), transparent 38%),
    linear-gradient(180deg, oklch(100% 0.004 250 / 0.14), oklch(88% 0.04 250 / 0.05)),
    var(--glass);
  box-shadow:
    0 28px 78px oklch(13% 0.045 265 / 0.2),
    inset 0 1px 0 oklch(100% 0.004 260 / 0.22);
  backdrop-filter: blur(28px) saturate(175%) brightness(1.05);
  -webkit-backdrop-filter: blur(28px) saturate(175%) brightness(1.05);
}

.checkout-summary-head {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.checkout-summary-head span {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 850;
}

.checkout-summary-head strong {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border: 1px solid oklch(100% 0.004 260 / 0.18);
  border-radius: 999px;
  color: var(--accent-ink);
  background: oklch(100% 0.004 260 / 0.1);
  font-size: 12px;
  font-weight: 950;
}

.checkout-quantity {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.12);
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.12);
}

.checkout-quantity > span {
  margin: 0;
}

.quantity-control {
  display: grid;
  grid-template-columns: 38px 70px 38px;
  align-items: center;
  min-height: 42px;
  overflow: hidden;
  border: 1px solid oklch(100% 0.004 260 / 0.18);
  border-radius: 16px;
  background:
    linear-gradient(180deg, oklch(100% 0.004 260 / 0.1), oklch(18% 0.045 268 / 0.28)),
    oklch(9% 0.03 268 / 0.18);
  box-shadow: inset 0 1px 0 oklch(100% 0.004 260 / 0.12);
}

.quantity-control button {
  height: 42px;
  border: 0;
  color: var(--accent-ink);
  background: transparent;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition:
    background 180ms var(--ease-out),
    transform 180ms var(--ease-out),
    opacity 180ms var(--ease-out);
}

.quantity-control button:hover:not(:disabled) {
  background: oklch(100% 0.004 250 / 0.24);
}

.quantity-control button:active:not(:disabled) {
  transform: scale(0.94);
}

.quantity-control button:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.quantity-control input {
  height: 42px;
  padding: 0;
  border: 0;
  border-radius: 0;
  text-align: center;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.quantity-control input::-webkit-outer-spin-button,
.quantity-control input::-webkit-inner-spin-button {
  margin: 0;
  appearance: none;
}

.checkout-mini-facts {
  display: grid;
  gap: 0;
}

.checkout-mini-facts > div {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid oklch(100% 0.004 260 / 0.1);
}

.checkout-mini-facts > div:first-child {
  padding-top: 0;
}

.checkout-mini-facts strong {
  min-width: 0;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 13px;
  font-weight: 900;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout-alert {
  margin: 0;
  border-color: oklch(78% 0.12 42 / 0.35);
  color: oklch(58% 0.13 42);
  background:
    linear-gradient(180deg, oklch(88% 0.08 42 / 0.16), oklch(82% 0.06 42 / 0.08)),
    transparent;
}

.checkout-submit {
  display: grid;
  gap: 14px;
  margin-top: 0;
  padding: 16px 0 0;
  border-top: 1px solid oklch(100% 0.004 260 / 0.14);
}

.checkout-total {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.checkout-total strong {
  color: oklch(72% 0.16 28);
  font-size: 30px;
  line-height: 1;
}

.checkout-total small {
  color: var(--faint-ink);
  font-size: 12px;
  font-weight: 800;
}

.checkout-submit-button {
  min-width: 0;
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

:global(:root[data-theme="dark"]) .checkout-hero {
  border-color: oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 10% 0%, oklch(72% 0.14 236 / 0.12), transparent 32%),
    radial-gradient(circle at 94% 10%, oklch(72% 0.16 28 / 0.11), transparent 34%),
    linear-gradient(135deg, oklch(25% 0.055 268 / 0.6), oklch(10% 0.03 268 / 0.72)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .checkout-form-panel {
  border-color: oklch(100% 0.004 260 / 0.14);
  background:
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.68), oklch(11% 0.032 268 / 0.76)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .checkout-summary-panel {
  border-color: oklch(100% 0.004 260 / 0.16);
  background:
    radial-gradient(circle at 78% 0%, oklch(72% 0.16 28 / 0.14), transparent 34%),
    radial-gradient(circle at 10% 18%, oklch(72% 0.14 236 / 0.1), transparent 38%),
    linear-gradient(180deg, oklch(23% 0.05 268 / 0.66), oklch(11% 0.032 268 / 0.76)),
    var(--glass);
}

:global(:root[data-theme="dark"]) .checkout-submit,
:global(:root[data-theme="dark"]) .checkout-section {
  background: transparent;
}

:global(:root[data-theme="dark"]) .quantity-control {
  border-color: oklch(100% 0.004 260 / 0.22);
  background:
    linear-gradient(180deg, oklch(42% 0.06 268 / 0.38), oklch(22% 0.05 270 / 0.5)),
    var(--glass);
}

@media (max-width: 1180px) {
  .checkout-hero {
    grid-template-columns: 132px minmax(0, 1fr);
  }

  .checkout-hero-side {
    grid-column: 1 / -1;
    grid-template-columns: minmax(0, 240px) minmax(0, 1fr);
    align-items: end;
  }

  .checkout-price-line {
    justify-items: start;
  }

  .checkout-hero-facts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    border-top: 0;
  }

  .checkout-hero-facts > div {
    display: grid;
    gap: 3px;
    padding: 10px 12px;
    border: 1px solid oklch(100% 0.004 260 / 0.11);
    border-radius: 14px;
    background: oklch(100% 0.004 260 / 0.05);
  }

  .checkout-hero-facts strong {
    text-align: left;
  }
}

@media (max-width: 1040px) {
  .checkout-workspace {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-summary-panel {
    position: static;
  }
}

@media (max-width: 760px) {
  .checkout-hero,
  .checkout-form-panel,
  .checkout-summary-panel {
    padding: 16px;
  }

  .checkout-hero {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-hero .checkout-cover {
    width: 116px;
    height: 116px;
  }

  .checkout-hero-side {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-hero-facts,
  .checkout-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .checkout-quantity {
    grid-template-columns: minmax(0, 1fr);
  }

  .quantity-control {
    grid-template-columns: 44px minmax(0, 1fr) 44px;
  }

  .checkout-hero-main h1 {
    font-size: 25px;
  }
}
</style>
