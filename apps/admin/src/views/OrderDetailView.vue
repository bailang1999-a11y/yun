<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CheckCircle2, RefreshCw, RotateCcw, Undo2 } from 'lucide-vue-next'
import { fetchGoodsChannels } from '../api/goods'
import { completeManualOrder, fetchOrderDetail, refreshOrderCallback, refundOrder, retryOrder, retryOrderWithChannel } from '../api/orders'
import type { ChannelAttempt, GoodsChannel, Order } from '../types/operations'
import OrderNumbersCell from '../components/OrderNumbersCell.vue'
import OrderStatusBadge from '../components/OrderStatusBadge.vue'
import {
  formatDateTime,
  formatDeliveryType,
  formatDurationFromOrder,
  formatMoney,
  formatOrderSource as formatOrderSourceLabel,
  formatPaymentMethod
} from '../utils/formatters'

const route = useRoute()
const router = useRouter()
const props = withDefaults(defineProps<{
  orderNumber?: string
  initialOrder?: Order
  embedded?: boolean
}>(), {
  orderNumber: '',
  initialOrder: undefined,
  embedded: false
})
const emit = defineEmits<{
  updated: [order: Order]
}>()
const order = ref<Order | null>(props.initialOrder || null)
const channels = ref<GoodsChannel[]>([])
const selectedChannelId = ref('')
const loading = ref(false)
const operating = ref('')
const callbackRefreshing = ref(false)
const nowTick = ref(Date.now())
let durationTimer: ReturnType<typeof setInterval> | undefined

const orderNo = computed(() => props.orderNumber || String(route.params.orderNo || ''))
const canCompleteManual = computed(() => order.value?.status === 'WAITING_MANUAL')
const canRetry = computed(() => ['FAILED', 'PROCURING'].includes(order.value?.status || ''))
const canRefund = computed(() => Boolean(order.value && !['REJECTED', 'REFUNDED', 'CANCELLED'].includes(order.value.status)))
const integrationAttempts = computed<ChannelAttempt[]>(() => {
  if (order.value?.channelAttempts?.length) return order.value.channelAttempts
  if (!order.value?.supplierName && !order.value?.supplierGoodsId && !order.value?.supplierGoodsName) return []

  return [
    {
      supplierName: order.value.supplierName || '-',
      supplierGoodsId: order.value.supplierGoodsId || '-',
      supplierGoodsName: order.value.supplierGoodsName,
      supplierPrice: order.value.unitPrice,
      priority: 0,
      status: order.value.status,
      message: order.value.deliveryMessage || ''
    }
  ]
})
onMounted(() => {
  durationTimer = setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
})

watch(orderNo, (nextOrderNo, previousOrderNo) => {
  if (!nextOrderNo) return
  if (previousOrderNo && nextOrderNo !== previousOrderNo) {
    order.value = props.initialOrder?.orderNo === nextOrderNo ? props.initialOrder : null
  }
  void loadOrder()
}, { immediate: true })

onBeforeUnmount(() => {
  if (durationTimer) clearInterval(durationTimer)
})

function formatTime(value?: string) {
  return formatDateTime(value)
}

function hasExternalAmount(value?: number | string) {
  return value !== undefined && value !== null && value !== '' && Number.isFinite(Number(value))
}

function formatExternalAmount(value?: number | string) {
  return hasExternalAmount(value) ? formatMoney(value) : '未提供'
}

function formatOrderSource(value?: Order) {
  if (!value) return '-'
  return formatOrderSourceLabel(value.orderSource, value.requestId, value.platform, value.buyerRemark)
}

function formatDuration(value?: Order) {
  nowTick.value
  if (!value) return '-'
  const terminalStatuses = ['DELIVERED', 'REJECTED', 'FAILED', 'REFUNDED', 'CANCELLED', 'CLOSED']
  const endAt = terminalStatuses.includes(value.status)
    ? value.rejectedAt || value.deliveredAt || value.createdAt
    : undefined
  return formatDurationFromOrder(value.createdAt, endAt)
}

function sourceGoods(value?: Order) {
  if (!value) return '-'
  if (value.supplierGoodsName || value.supplierGoodsId) {
    return [value.supplierName, value.supplierGoodsName || value.supplierGoodsId].filter(Boolean).join(' · ')
  }

  const successAttempt = value.channelAttempts?.find((attempt) => attempt.status === 'SUCCESS') || value.channelAttempts?.[0]
  if (!successAttempt) return '-'
  return [successAttempt.supplierName, successAttempt.supplierGoodsId].filter(Boolean).join(' · ')
}

function integrationStatusLabel(status?: string) {
  const map: Record<string, string> = {
    SUCCESS: '对接成功',
    DELIVERED: '对接成功',
    FAILED: '对接失败',
    ERROR: '对接异常',
    TIMEOUT: '上游超时',
    PENDING: '等待处理',
    PROCESSING: '上游处理中',
    PROCURING: '采购中',
    WAITING_MANUAL: '待人工处理',
    CANCELLED: '上游已取消',
    REFUNDED: '上游已退款',
    UNPAID: '上游未支付',
    UNKNOWN: '上游状态未知'
  }

  return status ? map[status] ?? status : '未知状态'
}

function integrationStatusType(status?: string) {
  if (['SUCCESS', 'DELIVERED'].includes(status || '')) return 'success'
  if (['FAILED', 'ERROR', 'TIMEOUT'].includes(status || '')) return 'danger'
  if (['PROCURING', 'PROCESSING', 'PENDING'].includes(status || '')) return 'primary'
  return 'warning'
}

function plainIntegrationMessage(attempt: ChannelAttempt) {
  const message = attempt.callbackMessage || attempt.message || ''
  if (['SUCCESS', 'DELIVERED'].includes(attempt.status)) return message || '上游已经返回成功，系统已完成该订单处理。'
  if (/余额不足/.test(message)) return '上游账户余额不足，系统无法继续向该渠道下单，请先给对应供应商充值或切换渠道。'
  if (/不存在|not found/i.test(message)) return '系统没有找到这个上游渠道或上游商品，请检查货源对接配置是否还有效。'
  if (/停用|disabled/i.test(message)) return '该上游供应商或渠道已停用，订单没有继续提交到这个渠道。'
  if (/超时|timeout/i.test(message)) return '请求上游时等待时间过长，上游没有在规定时间内返回结果。'
  if (/没有可用上游渠道/.test(message)) return '当前商品没有可用的上游对接渠道，需要先配置并启用货源渠道。'
  if (['FAILED', 'ERROR'].includes(attempt.status)) return message ? `上游处理失败：${message}` : '上游处理失败，但接口没有返回具体原因。'
  return message || '上游订单已提交，正在等待上游处理结果。'
}

function callbackStateLabel(attempt: ChannelAttempt) {
  const status = attempt.upstreamStatus || attempt.status
  if (['DELIVERED', 'FAILED', 'ERROR', 'CANCELLED', 'REFUNDED', 'UNPAID'].includes(status || '')) {
    return '已通过订单详情同步'
  }
  return attempt.callbackStatus ? '未收到异步回调' : '未配置/未收到异步回调'
}

async function loadOrder() {
  const targetOrderNo = orderNo.value
  if (!targetOrderNo) return
  loading.value = true
  try {
    const nextOrder = await fetchOrderDetail(targetOrderNo)
    if (targetOrderNo !== orderNo.value) return
    order.value = nextOrder
    emit('updated', nextOrder)
    await loadChannels()
  } catch {
    ElMessage.error('订单详情加载失败')
  } finally {
    loading.value = false
  }
}

async function loadChannels() {
  channels.value = []
  selectedChannelId.value = ''
  if (!order.value?.goodsId || order.value.deliveryType !== 'DIRECT') return
  try {
    channels.value = await fetchGoodsChannels(order.value.goodsId)
    selectedChannelId.value = String(channels.value[0]?.id ?? '')
  } catch {
    channels.value = []
  }
}

async function refreshCallbackInfo() {
  if (order.value?.status === 'REJECTED') return
  if (callbackRefreshing.value) return
  callbackRefreshing.value = true
  try {
    order.value = await refreshOrderCallback(orderNo.value)
    emit('updated', order.value)
    await loadChannels()
    ElMessage.success('回调信息已刷新')
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '回调信息刷新失败'
    ElMessage.error(message)
  } finally {
    callbackRefreshing.value = false
  }
}

async function runOperation(type: 'manual' | 'retry' | 'retry-channel' | 'refund') {
  if (!order.value) return
  if (order.value.status === 'REJECTED') return
  if (type === 'retry-channel' && !selectedChannelId.value) {
    ElMessage.warning('请选择一个上游渠道')
    return
  }

  const copy = {
    manual: '确认该代充订单已完成？',
    retry: '确认重新触发采购/充值？',
    'retry-channel': '确认使用指定渠道重新触发采购/充值？',
    refund: '确认执行模拟退款？'
  }[type]

  try {
    await ElMessageBox.confirm(copy, '二次确认', {
      type: type === 'refund' ? 'warning' : 'info',
      customClass: 'xiyiyun-glass-message-box'
    })
  } catch {
    return
  }

  operating.value = type
  try {
    if (type === 'manual') order.value = await completeManualOrder(order.value.orderNo)
    if (type === 'retry') order.value = await retryOrder(order.value.orderNo)
    if (type === 'retry-channel') order.value = await retryOrderWithChannel(order.value.orderNo, selectedChannelId.value)
    if (type === 'refund') order.value = await refundOrder(order.value.orderNo)
    emit('updated', order.value)
    await loadChannels()
    ElMessage.success('订单已更新')
  } catch {
    ElMessage.error('操作失败')
  } finally {
    operating.value = ''
  }
}
</script>

<template>
  <section class="detail-shell" :class="{ 'is-embedded': embedded }">
    <article class="detail-main liquid-admin-panel" v-loading="loading">
      <div v-if="!embedded" class="detail-head">
        <button type="button" @click="router.push({ name: 'orders' })">
          <ArrowLeft :size="17" />
          返回列表
        </button>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadOrder">刷新</el-button>
      </div>

      <template v-if="order">
        <div class="hero-row">
          <div class="hero-order">
            <OrderNumbersCell :order="order" :compact="embedded" />
            <p>{{ order.goodsName }}</p>
          </div>
          <div class="hero-actions">
            <OrderStatusBadge
              :status="order.status"
              :rejection-reason="order.rejectionReason || order.deliveryMessage"
              :request-id="order.requestId"
              :external-max-amount="order.externalMaxAmount"
              :expected-amount="order.expectedAmount"
            />
            <el-button v-if="embedded" :icon="RefreshCw" :loading="loading" @click="loadOrder">刷新</el-button>
          </div>
        </div>

        <div v-if="embedded" class="embedded-info-grid">
          <section>
            <h3>基本信息</h3>
            <dl>
              <dt>下单来源</dt><dd>{{ formatOrderSource(order) }}</dd>
              <dt>销售平台</dt><dd>{{ order.platform || '-' }}</dd>
              <dt>商品 ID</dt><dd>{{ order.goodsId || '-' }}</dd>
              <dt>货源商品</dt><dd>{{ sourceGoods(order) }}</dd>
              <dt>发货类型</dt><dd>{{ formatDeliveryType(order.deliveryType) }}</dd>
              <dt>下单会员</dt><dd>{{ order.buyerAccount || order.userId || '-' }}</dd>
              <dt>用户 ID</dt><dd>{{ order.userId || '-' }}</dd>
              <dt>联系方式</dt><dd>{{ order.buyerContact || order.buyerMobile || order.buyerEmail || '-' }}</dd>
              <dt>手机号</dt><dd>{{ order.buyerMobile || '-' }}</dd>
              <dt>邮箱</dt><dd>{{ order.buyerEmail || '-' }}</dd>
              <dt>充值账号</dt><dd>{{ order.rechargeAccount || '-' }}</dd>
              <dt>买家备注</dt><dd>{{ order.buyerRemark || '-' }}</dd>
            </dl>
          </section>

          <section>
            <h3>支付信息</h3>
            <dl>
              <dt>单价</dt><dd>{{ formatMoney(order.unitPrice) }}</dd>
              <dt>数量</dt><dd>x{{ order.quantity || 1 }}</dd>
              <dt>实付金额</dt><dd :class="{ amount: hasExternalAmount(order.externalMaxAmount) }">{{ formatExternalAmount(order.externalMaxAmount) }}</dd>
              <dt>成本金额</dt><dd>{{ formatMoney(order.amount) }}</dd>
              <dt>支付方式</dt><dd>{{ formatPaymentMethod(order.payMethod) }}</dd>
              <dt>支付流水</dt><dd>{{ order.paymentNo || '-' }}</dd>
              <dt>下单时间</dt><dd>{{ formatTime(order.createdAt) }}</dd>
              <dt>支付时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>完成时间</dt><dd>{{ formatTime(order.deliveredAt) }}</dd>
              <dt>处理耗时</dt><dd>{{ formatDuration(order) }}</dd>
              <dt>下单 IP</dt><dd>{{ order.orderIp || '-' }}</dd>
              <dt>IP 归属地</dt><dd>{{ order.orderIpLocation || '-' }}</dd>
              <dt>请求编号</dt><dd>{{ order.requestId || '-' }}</dd>
            </dl>
          </section>

          <section class="embedded-delivery-info">
            <h3>{{ order.status === 'REJECTED' ? '拒绝信息' : '发货 / 采购' }}</h3>
            <dl>
              <template v-if="order.status === 'REJECTED'">
                <dt>拒绝原因</dt><dd class="is-multiline rejection-reason">{{ order.rejectionReason || order.deliveryMessage || '未记录具体原因' }}</dd>
                <dt>拒绝代码</dt><dd>{{ order.rejectionCode || '-' }}</dd>
                <dt>外部订单号</dt><dd>{{ order.requestId || '未提供' }}</dd>
                <dt>实际支付价格</dt><dd :class="{ amount: hasExternalAmount(order.externalMaxAmount) }">{{ formatExternalAmount(order.externalMaxAmount) }}</dd>
                <dt>系统要求价格</dt><dd :class="{ amount: hasExternalAmount(order.expectedAmount) }">{{ formatExternalAmount(order.expectedAmount) }}</dd>
              </template>
              <template v-else>
                <dt>处理说明</dt>
                <dd class="is-multiline">{{ order.deliveryMessage || '暂无发货说明。' }}</dd>
              </template>
              <template v-if="order.deliveryItems?.length">
                <dt>交付内容</dt>
                <dd class="is-multiline embedded-delivery-items">
                  <span v-for="item in order.deliveryItems" :key="item">{{ item }}</span>
                </dd>
              </template>
            </dl>
          </section>

          <section class="embedded-integration-info">
            <div class="embedded-section-head">
              <h3>对接详情</h3>
              <el-button
                circle
                class="embedded-callback-refresh"
                :icon="RefreshCw"
                :loading="callbackRefreshing"
                :disabled="order.status === 'REJECTED'"
                title="刷新回调信息"
                aria-label="刷新回调信息"
                @click="refreshCallbackInfo"
              />
            </div>
            <div v-if="integrationAttempts.length" class="embedded-integration-list">
              <article
                v-for="attempt in integrationAttempts"
                :key="`${attempt.channelId || attempt.supplierGoodsId}-${attempt.attemptedAt || attempt.status}`"
                class="embedded-integration-attempt"
                :data-status="attempt.status"
              >
                <div class="embedded-attempt-head">
                  <div>
                    <strong>{{ attempt.supplierName || '未指定供应商' }}</strong>
                    <span>{{ formatTime(attempt.attemptedAt) }}</span>
                  </div>
                  <el-tag :type="integrationStatusType(attempt.status)" effect="dark">
                    {{ integrationStatusLabel(attempt.callbackStatus || attempt.upstreamStatus || attempt.status) }}
                  </el-tag>
                </div>
                <dl>
                  <dt>上游状态</dt><dd>{{ integrationStatusLabel(attempt.upstreamStatus || attempt.status) }}</dd>
                  <dt>上游售价</dt><dd class="amount">{{ attempt.supplierPrice ? formatMoney(attempt.supplierPrice) : '接口暂未返回' }}</dd>
                  <dt>回调状态</dt><dd class="is-multiline">{{ callbackStateLabel(attempt) }}</dd>
                  <dt>上游商品</dt><dd class="is-multiline">{{ attempt.supplierGoodsName || order.supplierGoodsName || '接口暂未返回' }}</dd>
                  <dt>商品编号</dt><dd>{{ attempt.supplierGoodsId || '-' }}</dd>
                  <dt>优先级</dt><dd>{{ attempt.priority || '-' }}</dd>
                  <dt>处理结果</dt><dd class="is-multiline">{{ plainIntegrationMessage(attempt) }}</dd>
                </dl>
                <details v-if="attempt.rawResponse" class="embedded-raw-response">
                  <summary>上游原始响应</summary>
                  <pre>{{ attempt.rawResponse }}</pre>
                </details>
              </article>
            </div>
            <p v-else class="embedded-integration-empty">暂未产生上游对接记录。</p>
          </section>

          <section class="embedded-attempt-timeline">
            <h3>尝试时间线</h3>
            <div v-if="order.channelAttempts?.length" class="embedded-timeline-list">
              <article
                v-for="attempt in order.channelAttempts"
                :key="`${attempt.channelId}-${attempt.attemptedAt}`"
                class="embedded-timeline-item"
                :data-status="attempt.status"
              >
                <div class="embedded-timeline-head">
                  <strong>{{ attempt.supplierName || '未指定供应商' }}</strong>
                  <el-tag :type="integrationStatusType(attempt.status)" effect="dark">
                    {{ integrationStatusLabel(attempt.status) }}
                  </el-tag>
                </div>
                <dl>
                  <dt>优先级</dt><dd>{{ attempt.priority || '-' }}</dd>
                  <dt>商品编号</dt><dd>{{ attempt.supplierGoodsId || '-' }}</dd>
                  <dt>尝试时间</dt><dd>{{ formatTime(attempt.attemptedAt) }}</dd>
                  <dt>结果说明</dt><dd class="is-multiline">{{ attempt.message || '暂无结果说明' }}</dd>
                </dl>
              </article>
            </div>
            <p v-else class="embedded-integration-empty">暂无渠道尝试记录。</p>
          </section>
        </div>

        <div v-else class="info-grid">
          <section>
            <h3>基本信息</h3>
            <dl>
              <dt>下单来源</dt><dd>{{ formatOrderSource(order) }}</dd>
              <dt>销售平台</dt><dd>{{ order.platform || '-' }}</dd>
              <dt>商品ID</dt><dd>{{ order.goodsId || '-' }}</dd>
              <dt>货源商品</dt><dd>{{ sourceGoods(order) }}</dd>
              <dt>下单会员</dt><dd>{{ order.buyerAccount || order.userId || '-' }}</dd>
              <dt>联系方式</dt><dd>{{ order.buyerContact || order.buyerMobile || order.buyerEmail || '-' }}</dd>
              <dt>发货类型</dt><dd>{{ formatDeliveryType(order.deliveryType) }}</dd>
              <dt>充值账号</dt><dd>{{ order.rechargeAccount || '-' }}</dd>
              <dt>下单时间</dt><dd>{{ formatTime(order.createdAt) }}</dd>
            </dl>
          </section>

          <section>
            <h3>支付信息</h3>
            <dl>
              <dt>单价</dt><dd>{{ formatMoney(order.unitPrice) }}</dd>
              <dt>数量</dt><dd>x{{ order.quantity || 1 }}</dd>
              <dt>实付金额</dt><dd :class="{ amount: hasExternalAmount(order.externalMaxAmount) }">{{ formatExternalAmount(order.externalMaxAmount) }}</dd>
              <dt>成本金额</dt><dd>{{ formatMoney(order.amount) }}</dd>
              <dt>支付方式</dt><dd>{{ formatPaymentMethod(order.payMethod) }}</dd>
              <dt>支付流水</dt><dd>{{ order.paymentNo || '-' }}</dd>
              <dt>支付时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>完成时间</dt><dd>{{ formatTime(order.deliveredAt) }}</dd>
              <dt>订单处理耗时</dt><dd>{{ formatDuration(order) }}</dd>
            </dl>
          </section>

          <section>
            <h3>下单环境</h3>
            <dl>
              <dt>下单 IP</dt><dd>{{ order.orderIp || '-' }}</dd>
              <dt>IP 归属地</dt><dd>{{ order.orderIpLocation || '-' }}</dd>
              <dt>请求编号</dt><dd>{{ order.requestId || '-' }}</dd>
              <dt>用户 ID</dt><dd>{{ order.userId || '-' }}</dd>
              <dt>手机号</dt><dd>{{ order.buyerMobile || '-' }}</dd>
              <dt>邮箱</dt><dd>{{ order.buyerEmail || '-' }}</dd>
              <dt>买家备注</dt><dd>{{ order.buyerRemark || '-' }}</dd>
            </dl>
          </section>
        </div>

        <section v-if="!embedded" class="timeline">
          <h3>{{ order.status === 'REJECTED' ? '拒绝信息' : '发货 / 采购信息' }}</h3>
          <dl v-if="order.status === 'REJECTED'" class="rejection-detail-grid">
            <dt>拒绝原因</dt><dd class="rejection-reason">{{ order.rejectionReason || order.deliveryMessage || '未记录具体原因' }}</dd>
            <dt>拒绝代码</dt><dd>{{ order.rejectionCode || '-' }}</dd>
            <dt>外部订单号</dt><dd>{{ order.requestId || '未提供' }}</dd>
            <dt>实际支付价格</dt><dd :class="{ amount: hasExternalAmount(order.externalMaxAmount) }">{{ formatExternalAmount(order.externalMaxAmount) }}</dd>
            <dt>系统要求价格</dt><dd :class="{ amount: hasExternalAmount(order.expectedAmount) }">{{ formatExternalAmount(order.expectedAmount) }}</dd>
          </dl>
          <p v-else>{{ order.deliveryMessage || '暂无发货说明。' }}</p>
          <div v-if="order.deliveryItems?.length" class="secret-list">
            <span v-for="item in order.deliveryItems" :key="item">{{ item }}</span>
          </div>
        </section>

        <section v-if="!embedded" class="timeline integration-panel">
          <div class="integration-panel-head">
            <h3>对接详情</h3>
            <el-button v-if="order.status !== 'REJECTED'" class="callback-refresh-button" :icon="RefreshCw" :loading="callbackRefreshing" @click="refreshCallbackInfo">
              刷新回调信息
            </el-button>
          </div>
          <div v-if="integrationAttempts.length" class="integration-list">
            <article
              v-for="attempt in integrationAttempts"
              :key="`${attempt.channelId || attempt.supplierGoodsId}-${attempt.attemptedAt || attempt.status}`"
              class="integration-card"
              :data-status="attempt.status"
            >
              <div class="integration-head">
                <div>
                  <strong>{{ attempt.supplierName || '未指定供应商' }}</strong>
                  <span>{{ formatTime(attempt.attemptedAt) }}</span>
                </div>
                <el-tag class="integration-status-badge" :type="integrationStatusType(attempt.status)" effect="dark">
                  {{ integrationStatusLabel(attempt.callbackStatus || attempt.upstreamStatus || attempt.status) }}
                </el-tag>
              </div>
              <div class="integration-summary">
                <div>
                  <span>上游状态</span>
                  <strong>{{ integrationStatusLabel(attempt.upstreamStatus || attempt.status) }}</strong>
                </div>
                <div>
                  <span>上游售价</span>
                  <strong class="amount">{{ attempt.supplierPrice ? formatMoney(attempt.supplierPrice) : '接口暂未返回' }}</strong>
                </div>
                <div>
                  <span>回调状态</span>
                  <strong>{{ callbackStateLabel(attempt) }}</strong>
                </div>
              </div>
              <div class="integration-body">
                <div class="integration-goods">
                  <span>上游商品</span>
                  <strong>{{ attempt.supplierGoodsName || order.supplierGoodsName || '接口暂未返回' }}</strong>
                  <small>编号 {{ attempt.supplierGoodsId || '-' }} · 优先级 {{ attempt.priority || '-' }}</small>
                </div>
                <p class="integration-message">{{ plainIntegrationMessage(attempt) }}</p>
              </div>
              <details v-if="attempt.rawResponse" class="raw-response">
                <summary>查看上游原始响应</summary>
                <pre>{{ attempt.rawResponse }}</pre>
              </details>
            </article>
          </div>
          <p v-else>该订单暂未产生上游对接记录，可能是本地货源、待支付订单，或还没有触发采购。</p>
        </section>

        <section v-if="!embedded && order.channelAttempts?.length" class="timeline">
          <h3>渠道尝试时间线</h3>
          <div class="attempt-list">
            <article
              v-for="attempt in order.channelAttempts"
              :key="`${attempt.channelId}-${attempt.attemptedAt}`"
              class="attempt-card"
              :data-status="attempt.status"
            >
              <div>
                <strong>{{ attempt.supplierName }}</strong>
                <span>优先级 {{ attempt.priority }} · {{ attempt.supplierGoodsId }}</span>
              </div>
              <p>{{ attempt.message }}</p>
              <small>{{ formatTime(attempt.attemptedAt) }}</small>
            </article>
          </div>
        </section>

        <section v-if="channels.length" class="timeline channel-picker">
          <h3>指定渠道重试</h3>
          <div>
            <el-select v-model="selectedChannelId" placeholder="选择上游渠道" :disabled="!canRetry">
              <el-option
                v-for="channel in channels"
                :key="channel.id"
                :label="`${channel.supplierName} · ${channel.supplierGoodsId} · 优先级 ${channel.priority}`"
                :value="String(channel.id)"
              />
            </el-select>
            <el-button
              :icon="RotateCcw"
              :disabled="!canRetry || !selectedChannelId"
              :loading="operating === 'retry-channel'"
              @click="runOperation('retry-channel')"
            >
              指定渠道重试
            </el-button>
          </div>
        </section>

        <div v-if="order.status !== 'REJECTED'" class="action-bar">
          <el-button
            type="success"
            :icon="CheckCircle2"
            :disabled="!canCompleteManual"
            :loading="operating === 'manual'"
            @click="runOperation('manual')"
          >
            确认充值完成
          </el-button>
          <el-button
            :icon="RotateCcw"
            :disabled="!canRetry"
            :loading="operating === 'retry'"
            @click="runOperation('retry')"
          >
            手动重试
          </el-button>
          <el-button
            type="warning"
            :icon="Undo2"
            :disabled="!canRefund"
            :loading="operating === 'refund'"
            @click="runOperation('refund')"
          >
            退款
          </el-button>
        </div>
      </template>

      <section v-else-if="!loading" class="empty">未找到订单。</section>
    </article>
  </section>
</template>

<style scoped>
.detail-shell {
  --order-font-primary: 13px;
  --order-font-secondary: 11px;
  --order-font-control: 12px;
  --order-weight-primary: 600;
  --order-weight-secondary: 500;
  --order-weight-control: 600;
  --order-line-primary: 19px;
  --order-line-secondary: 16px;
  --order-line-control: 18px;
  display: grid;
  gap: 14px;
}

.detail-main {
  padding: 18px;
  overflow: hidden;
  border-radius: 22px;
}

.detail-shell.is-embedded {
  animation: embedded-detail-in 180ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.is-embedded .detail-main {
  padding: 18px 22px 22px;
  border-radius: 0;
  background: rgba(4, 12, 24, 0.76);
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.is-embedded .detail-main::after {
  display: none;
}

.is-embedded .hero-row {
  margin-top: 12px;
}

.detail-head,
.hero-row,
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail-head button {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.78);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
}

.hero-row {
  margin-top: 18px;
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.hero-row span,
dt {
  color: rgba(255, 255, 255, 0.45);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.hero-order {
  min-width: 0;
  flex: 1;
}

.hero-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.hero-row p {
  margin: 10px 0 0;
  color: rgba(255, 255, 255, 0.62);
}

.hero-actions :deep(.detail-status) {
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.info-grid section,
.timeline {
  padding: 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

h3 {
  margin: 0 0 12px;
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
  font-weight: 650;
  line-height: 20px;
}

dl {
  display: grid;
  grid-template-columns: 92px 1fr;
  gap: 10px 14px;
  margin: 0;
}

dd {
  margin: 0;
  min-width: 0;
  color: rgba(255, 255, 255, 0.78);
  font-size: var(--order-font-primary);
  font-weight: var(--order-weight-primary);
  line-height: var(--order-line-primary);
  overflow-wrap: anywhere;
}

.amount {
  color: #00ffc3;
  font-weight: 700;
}

.rejection-reason {
  color: #fecaca;
}

.rejection-detail-grid {
  grid-template-columns: 108px minmax(0, 1fr);
}

.timeline {
  margin-top: 14px;
}

.timeline p {
  margin: 0;
  color: rgba(255, 255, 255, 0.66);
}

.channel-picker > div {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.secret-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.secret-list span {
  padding: 7px 10px;
  color: rgba(255, 255, 255, 0.86);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.attempt-list {
  display: grid;
  gap: 10px;
}

.integration-list {
  display: grid;
  gap: 12px;
}

.integration-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.integration-panel-head h3 {
  margin: 0;
}

.integration-panel-head :deep(.callback-refresh-button) {
  height: 32px;
  padding: 0 13px;
  color: #bfdbfe;
  border-color: rgba(96, 165, 250, 0.28);
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(59, 130, 246, 0.2), rgba(37, 99, 235, 0.1));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.16), 0 8px 20px rgba(59, 130, 246, 0.1);
}

.integration-panel-head :deep(.callback-refresh-button:hover) {
  color: #dbeafe;
  border-color: rgba(147, 197, 253, 0.42);
  background: linear-gradient(180deg, rgba(59, 130, 246, 0.28), rgba(37, 99, 235, 0.14));
}

.integration-card {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(15, 23, 42, 0.5), rgba(30, 41, 59, 0.34));
  border: 0.5px solid rgba(148, 163, 184, 0.16);
}

.integration-card[data-status="SUCCESS"],
.integration-card[data-status="DELIVERED"] {
  border-color: rgba(0, 255, 195, 0.18);
}

.integration-card[data-status="FAILED"],
.integration-card[data-status="ERROR"],
.integration-card[data-status="TIMEOUT"] {
  border-color: rgba(248, 113, 113, 0.26);
  background: rgba(127, 29, 29, 0.08);
}

.integration-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.integration-head strong {
  display: block;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  font-weight: 650;
  line-height: 20px;
}

.integration-head span {
  display: block;
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.44);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.integration-head :deep(.integration-status-badge) {
  height: 30px;
  min-width: 82px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 13px;
  border-radius: 999px;
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
  letter-spacing: 0;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18), 0 8px 20px rgba(59, 130, 246, 0.12);
}

.integration-head :deep(.integration-status-badge.el-tag--primary),
.integration-head :deep(.integration-status-badge.el-tag--warning) {
  color: #bfdbfe;
  border-color: rgba(96, 165, 250, 0.3);
  background: linear-gradient(180deg, rgba(59, 130, 246, 0.24), rgba(37, 99, 235, 0.15));
}

.integration-head :deep(.integration-status-badge.el-tag--success) {
  color: #99f6e4;
  border-color: rgba(45, 212, 191, 0.28);
  background: linear-gradient(180deg, rgba(20, 184, 166, 0.24), rgba(13, 148, 136, 0.14));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18), 0 8px 20px rgba(20, 184, 166, 0.12);
}

.integration-head :deep(.integration-status-badge.el-tag--danger) {
  color: #fecaca;
  border-color: rgba(248, 113, 113, 0.32);
  background: linear-gradient(180deg, rgba(239, 68, 68, 0.22), rgba(185, 28, 28, 0.12));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.16), 0 8px 20px rgba(239, 68, 68, 0.1);
}

.integration-summary {
  display: grid;
  grid-template-columns: 1fr 0.8fr 1fr;
  gap: 10px;
}

.integration-summary > div {
  min-width: 0;
  padding: 12px;
  border-radius: 12px;
  background: rgba(2, 6, 23, 0.2);
  border: 0.5px solid rgba(255, 255, 255, 0.07);
}

.integration-summary span,
.integration-goods span {
  display: block;
  margin-bottom: 6px;
  color: rgba(255, 255, 255, 0.42);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.integration-summary strong,
.integration-goods strong {
  display: block;
  min-width: 0;
  color: rgba(255, 255, 255, 0.84);
  font-size: var(--order-font-primary);
  font-weight: var(--order-weight-primary);
  line-height: var(--order-line-primary);
  overflow-wrap: anywhere;
}

.integration-body {
  display: grid;
  grid-template-columns: minmax(280px, 1.05fr) minmax(320px, 1.4fr);
  gap: 12px;
  align-items: stretch;
}

.integration-goods {
  min-width: 0;
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.035);
}

.integration-goods small {
  display: block;
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.46);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.integration-message {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(37, 99, 235, 0.1);
  border: 0.5px solid rgba(59, 130, 246, 0.16);
}

.raw-response {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.2);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-control);
}

.raw-response summary {
  cursor: pointer;
  color: rgba(191, 219, 254, 0.9);
  font-family: inherit;
}

.raw-response pre {
  max-height: 120px;
  margin: 10px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.attempt-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 0.7fr) 180px;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.attempt-card[data-status="SUCCESS"] {
  border-color: rgba(0, 255, 195, 0.22);
  box-shadow: 0 0 24px rgba(0, 255, 195, 0.08);
}

.attempt-card strong {
  display: block;
  color: rgba(255, 255, 255, 0.9);
  font-size: var(--order-font-primary);
  font-weight: var(--order-weight-primary);
  line-height: var(--order-line-primary);
}

.attempt-card span,
.attempt-card small {
  color: rgba(255, 255, 255, 0.45);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.attempt-card p {
  margin: 0;
  color: rgba(255, 255, 255, 0.68);
  font-size: var(--order-font-primary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-primary);
}

.action-bar {
  justify-content: flex-end;
  margin-top: 16px;
}

.is-embedded .detail-main {
  padding: 10px 14px 14px;
}

.is-embedded .hero-row {
  margin-top: 0;
  padding: 10px 12px;
  border-radius: 8px;
}

.is-embedded .hero-order {
  display: flex;
  align-items: center;
  gap: 16px;
}

.is-embedded .hero-order p {
  order: -1;
  width: 190px;
  flex: 0 0 190px;
  overflow: hidden;
  margin: 0;
  color: rgba(244, 249, 255, 0.86);
  font-size: 13px;
  font-weight: 650;
  line-height: 19px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-embedded .hero-order :deep(.order-numbers.is-compact) {
  width: 100%;
  max-width: none;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.is-embedded .hero-order :deep(.number-row) {
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 5px;
}

.is-embedded .hero-actions :deep(.detail-status) {
  height: 30px;
  padding: 0 10px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.is-embedded .hero-actions :deep(.el-button) {
  min-height: 30px;
  height: 30px;
  padding: 0 10px;
}

.embedded-info-grid {
  display: grid;
  grid-template-columns:
    minmax(280px, 1.3fr)
    minmax(280px, 1.3fr)
    minmax(220px, 1.15fr)
    minmax(260px, 1.35fr)
    minmax(250px, 1.2fr);
  margin-top: 8px;
  overflow: hidden;
  border: 1px solid rgba(183, 215, 244, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.025);
}

.embedded-info-grid section {
  min-width: 0;
  padding: 10px 12px;
}

.embedded-info-grid section + section {
  border-left: 1px solid rgba(183, 215, 244, 0.1);
}

.embedded-info-grid h3 {
  margin-bottom: 7px;
  font-size: 12px;
  line-height: 18px;
}

.embedded-info-grid dl {
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 5px 9px;
}

.embedded-info-grid dt,
.embedded-info-grid dd {
  font-size: 12px;
  line-height: 18px;
}

.embedded-info-grid dd {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.embedded-info-grid dd.is-multiline {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
}

.embedded-delivery-items {
  display: grid;
  gap: 4px;
}

.embedded-delivery-items span {
  padding: 3px 6px;
  overflow-wrap: anywhere;
  color: rgba(244, 249, 255, 0.82);
  border: 1px solid rgba(183, 215, 244, 0.09);
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.03);
}

.embedded-section-head,
.embedded-attempt-head,
.embedded-timeline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.embedded-section-head {
  margin-bottom: 7px;
}

.embedded-section-head h3 {
  margin: 0;
}

.embedded-section-head :deep(.embedded-callback-refresh) {
  width: 26px;
  min-width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  color: #bfdbfe;
  border-color: rgba(96, 165, 250, 0.24);
  background: rgba(59, 130, 246, 0.1);
}

.embedded-integration-list {
  display: grid;
  gap: 8px;
}

.embedded-integration-attempt {
  min-width: 0;
  padding-top: 8px;
  border-top: 1px solid rgba(183, 215, 244, 0.09);
}

.embedded-integration-attempt:first-child {
  padding-top: 0;
  border-top: 0;
}

.embedded-integration-attempt[data-status="FAILED"],
.embedded-integration-attempt[data-status="ERROR"],
.embedded-integration-attempt[data-status="TIMEOUT"] {
  color: #fecaca;
}

.embedded-attempt-head > div {
  min-width: 0;
}

.embedded-attempt-head strong,
.embedded-attempt-head span {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.embedded-attempt-head strong {
  color: rgba(244, 249, 255, 0.86);
  font-size: 12px;
  font-weight: 650;
  line-height: 18px;
}

.embedded-attempt-head span {
  color: rgba(210, 225, 242, 0.42);
  font-size: 11px;
  line-height: 16px;
}

.embedded-attempt-head :deep(.el-tag) {
  min-width: 64px;
  height: 24px;
  padding: 0 7px;
  font-size: 11px;
  line-height: 16px;
}

.embedded-integration-attempt dl {
  margin-top: 7px;
}

.embedded-raw-response {
  margin-top: 7px;
  padding: 6px 7px;
  border-radius: 6px;
  background: rgba(2, 6, 23, 0.28);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  line-height: 16px;
}

.embedded-raw-response summary {
  color: rgba(191, 219, 254, 0.84);
  cursor: pointer;
}

.embedded-raw-response pre {
  max-height: 90px;
  margin: 6px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.embedded-integration-empty {
  margin: 0;
  color: rgba(210, 225, 242, 0.46);
  font-size: 12px;
  line-height: 18px;
}

.embedded-timeline-list {
  display: grid;
  gap: 8px;
}

.embedded-timeline-item {
  min-width: 0;
  padding-top: 8px;
  border-top: 1px solid rgba(183, 215, 244, 0.09);
}

.embedded-timeline-item:first-child {
  padding-top: 0;
  border-top: 0;
}

.embedded-timeline-head strong {
  min-width: 0;
  overflow: hidden;
  color: rgba(244, 249, 255, 0.86);
  font-size: 12px;
  font-weight: 650;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.embedded-timeline-head :deep(.el-tag) {
  min-width: 64px;
  height: 24px;
  padding: 0 7px;
  font-size: 11px;
  line-height: 16px;
}

.embedded-timeline-item dl {
  margin-top: 7px;
}

.is-embedded .timeline {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.028);
}

.is-embedded .timeline h3 {
  margin-bottom: 7px;
  font-size: 12px;
  line-height: 18px;
}

.is-embedded .attempt-list {
  gap: 7px;
}

.is-embedded .attempt-card {
  grid-template-columns: minmax(220px, 0.9fr) minmax(260px, 1.4fr) 150px;
  gap: 8px;
  padding: 7px 9px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.028);
}

.is-embedded .channel-picker {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr);
  align-items: center;
  border-radius: 8px 8px 0 0;
}

.is-embedded .channel-picker h3 {
  margin: 0;
}

.is-embedded .channel-picker > div {
  grid-template-columns: minmax(260px, 1fr) auto;
}

.is-embedded .action-bar {
  margin-top: 8px;
  padding: 9px 12px;
  border: 1px solid rgba(183, 215, 244, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.028);
}

.is-embedded .channel-picker + .action-bar {
  margin-top: 0;
  border-top: 0;
  border-radius: 0 0 8px 8px;
}

.empty {
  padding: 36px;
  color: rgba(255, 255, 255, 0.55);
  text-align: center;
}

@media (max-width: 1280px) {
  .info-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .info-grid,
  .integration-summary,
  .integration-body,
  .attempt-card,
  .channel-picker > div {
    grid-template-columns: 1fr;
  }
}

@keyframes embedded-detail-in {
  from {
    transform: translateY(-8px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .detail-shell.is-embedded {
    animation: none;
  }
}
</style>
