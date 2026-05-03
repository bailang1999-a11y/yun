<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CheckCircle2, RefreshCw, RotateCcw, Undo2 } from 'lucide-vue-next'
import { fetchGoodsChannels } from '../api/goods'
import { completeManualOrder, fetchOrderDetail, refundOrder, retryOrder, retryOrderWithChannel } from '../api/orders'
import type { ChannelAttempt, GoodsChannel, Order } from '../types/operations'
import {
  formatDateTime,
  formatDeliveryType,
  formatDurationFromOrder,
  formatMoney,
  formatOrderSource as formatOrderSourceLabel,
  formatOrderStatus,
  formatPaymentMethod
} from '../utils/formatters'

const route = useRoute()
const router = useRouter()
const order = ref<Order | null>(null)
const channels = ref<GoodsChannel[]>([])
const selectedChannelId = ref('')
const loading = ref(false)
const operating = ref('')

const orderNo = computed(() => String(route.params.orderNo || ''))
const canCompleteManual = computed(() => order.value?.status === 'WAITING_MANUAL')
const canRetry = computed(() => ['FAILED', 'PROCURING'].includes(order.value?.status || ''))
const canRefund = computed(() => Boolean(order.value && !['REFUNDED', 'CANCELLED'].includes(order.value.status)))
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
onMounted(loadOrder)

function formatTime(value?: string) {
  return formatDateTime(value)
}

function formatOrderSource(value?: Order) {
  if (!value) return '-'
  return formatOrderSourceLabel(value.orderSource, Boolean(value.requestId), value.platform)
}

function formatDuration(value?: Order) {
  return formatDurationFromOrder(value?.createdAt, value?.deliveredAt || value?.paidAt)
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
    PENDING: '等待回调',
    PROCESSING: '处理中',
    PROCURING: '采购中',
    WAITING_MANUAL: '待人工处理'
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
  const message = attempt.callbackMessage || attempt.message || attempt.rawResponse || ''
  if (['SUCCESS', 'DELIVERED'].includes(attempt.status)) return message || '上游已经返回成功，系统已完成该订单处理。'
  if (/余额不足/.test(message)) return '上游账户余额不足，系统无法继续向该渠道下单，请先给对应供应商充值或切换渠道。'
  if (/不存在|not found/i.test(message)) return '系统没有找到这个上游渠道或上游商品，请检查货源对接配置是否还有效。'
  if (/停用|disabled/i.test(message)) return '该上游供应商或渠道已停用，订单没有继续提交到这个渠道。'
  if (/超时|timeout/i.test(message)) return '请求上游时等待时间过长，上游没有在规定时间内返回结果。'
  if (/没有可用上游渠道/.test(message)) return '当前商品没有可用的上游对接渠道，需要先配置并启用货源渠道。'
  if (['FAILED', 'ERROR'].includes(attempt.status)) return message ? `上游处理失败：${message}` : '上游处理失败，但接口没有返回具体原因。'
  return message || '暂未收到上游的详细回调信息。'
}

async function loadOrder() {
  loading.value = true
  try {
    order.value = await fetchOrderDetail(orderNo.value)
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

async function runOperation(type: 'manual' | 'retry' | 'retry-channel' | 'refund') {
  if (!order.value) return
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
  <section class="detail-shell">
    <article class="detail-main liquid-admin-panel" v-loading="loading">
      <div class="detail-head">
        <button type="button" @click="router.push({ name: 'orders' })">
          <ArrowLeft :size="17" />
          返回列表
        </button>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadOrder">刷新</el-button>
      </div>

      <template v-if="order">
        <div class="hero-row">
          <div>
            <span>订单编号</span>
            <h2>{{ order.orderNo }}</h2>
            <p>{{ order.goodsName }}</p>
          </div>
          <strong>{{ formatOrderStatus(order.status) }}</strong>
        </div>

        <div class="info-grid">
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
              <dt>实付金额</dt><dd class="amount">{{ formatMoney(order.amount) }}</dd>
              <dt>支付方式</dt><dd>{{ formatPaymentMethod(order.payMethod) }}</dd>
              <dt>支付流水</dt><dd>{{ order.paymentNo || '-' }}</dd>
              <dt>支付时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>完成时间</dt><dd>{{ formatTime(order.deliveredAt) }}</dd>
              <dt>订单耗时</dt><dd>{{ formatDuration(order) }}</dd>
            </dl>
          </section>

          <section>
            <h3>下单环境</h3>
            <dl>
              <dt>下单 IP</dt><dd>{{ order.orderIp || '-' }}</dd>
              <dt>请求编号</dt><dd>{{ order.requestId || '-' }}</dd>
              <dt>用户 ID</dt><dd>{{ order.userId || '-' }}</dd>
              <dt>手机号</dt><dd>{{ order.buyerMobile || '-' }}</dd>
              <dt>邮箱</dt><dd>{{ order.buyerEmail || '-' }}</dd>
              <dt>买家备注</dt><dd>{{ order.buyerRemark || '-' }}</dd>
            </dl>
          </section>
        </div>

        <section class="timeline">
          <h3>发货 / 采购信息</h3>
          <p>{{ order.deliveryMessage || '暂无发货说明。' }}</p>
          <div v-if="order.deliveryItems?.length" class="secret-list">
            <span v-for="item in order.deliveryItems" :key="item">{{ item }}</span>
          </div>
        </section>

        <section class="timeline integration-panel">
          <h3>对接详情</h3>
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
                <el-tag :type="integrationStatusType(attempt.status)" effect="dark">
                  {{ integrationStatusLabel(attempt.callbackStatus || attempt.upstreamStatus || attempt.status) }}
                </el-tag>
              </div>
              <dl class="integration-dl">
                <dt>对接平台</dt><dd>{{ attempt.supplierName || '-' }}</dd>
                <dt>上游商品编号</dt><dd>{{ attempt.supplierGoodsId || '-' }}</dd>
                <dt>上游商品名称</dt><dd>{{ attempt.supplierGoodsName || order.supplierGoodsName || '接口暂未返回' }}</dd>
                <dt>上游售价</dt><dd class="amount">{{ attempt.supplierPrice ? formatMoney(attempt.supplierPrice) : '接口暂未返回' }}</dd>
                <dt>渠道优先级</dt><dd>{{ attempt.priority || '-' }}</dd>
                <dt>回调状态</dt><dd>{{ integrationStatusLabel(attempt.callbackStatus || attempt.upstreamStatus || attempt.status) }}</dd>
              </dl>
              <p class="integration-message">{{ plainIntegrationMessage(attempt) }}</p>
              <p v-if="attempt.rawResponse" class="raw-response">{{ attempt.rawResponse }}</p>
            </article>
          </div>
          <p v-else>该订单暂未产生上游对接记录，可能是本地货源、待支付订单，或还没有触发采购。</p>
        </section>

        <section v-if="order.channelAttempts?.length" class="timeline">
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

        <div class="action-bar">
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
  display: grid;
  gap: 14px;
}

.detail-main {
  padding: 18px;
  overflow: hidden;
  border-radius: 22px;
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
  font-size: 12px;
}

.hero-row h2 {
  margin: 6px 0;
  color: rgba(255, 255, 255, 0.95);
  font-size: 26px;
}

.hero-row p {
  margin: 0;
  color: rgba(255, 255, 255, 0.62);
}

.hero-row > strong {
  padding: 8px 12px;
  color: #00ffc3;
  border-radius: 999px;
  background: rgba(0, 255, 195, 0.1);
  border: 0.5px solid rgba(0, 255, 195, 0.18);
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
  font-size: 16px;
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
  overflow-wrap: anywhere;
}

.amount {
  color: #00ffc3;
  font-weight: 800;
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

.integration-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.22);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
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
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.integration-head strong {
  display: block;
  color: rgba(255, 255, 255, 0.9);
  font-size: 15px;
}

.integration-head span {
  display: block;
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.44);
  font-size: 12px;
}

.integration-dl {
  grid-template-columns: 112px 1fr 112px 1fr;
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.035);
}

.integration-message {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(59, 130, 246, 0.1);
  border: 0.5px solid rgba(59, 130, 246, 0.16);
}

.raw-response {
  padding: 10px 12px;
  max-height: 120px;
  overflow: auto;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.2);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
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
}

.attempt-card span,
.attempt-card small {
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
}

.attempt-card p {
  margin: 0;
  color: rgba(255, 255, 255, 0.68);
}

.action-bar {
  justify-content: flex-end;
  margin-top: 16px;
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
  .integration-dl,
  .attempt-card,
  .channel-picker > div {
    grid-template-columns: 1fr;
  }
}
</style>
