<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CheckCircle2, RefreshCw, RotateCcw, Undo2 } from 'lucide-vue-next'
import { completeManualOrder, fetchGoodsChannels, fetchOrderDetail, refundOrder, retryOrder, retryOrderWithChannel } from '../api/admin'
import type { GoodsChannel, Order } from '../types/operations'

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
const statusLabel: Record<string, string> = {
  UNPAID: '待支付',
  PROCURING: '采购中',
  WAITING_MANUAL: '待人工处理',
  DELIVERED: '已发货',
  FAILED: '处理失败',
  REFUNDED: '已退款',
  CANCELLED: '已取消'
}

onMounted(loadOrder)

function formatMoney(value?: number | string) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? `¥${numberValue.toFixed(2)}` : '-'
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN')
}

function formatStatus(status: string) {
  return statusLabel[status] ?? status
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
    await ElMessageBox.confirm(copy, '二次确认', { type: type === 'refund' ? 'warning' : 'info' })
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
          <strong>{{ formatStatus(order.status) }}</strong>
        </div>

        <div class="info-grid">
          <section>
            <h3>基本信息</h3>
            <dl>
              <dt>来源平台</dt><dd>{{ order.platform || '-' }}</dd>
              <dt>商品ID</dt><dd>{{ order.goodsId || '-' }}</dd>
              <dt>下单会员</dt><dd>{{ order.buyerAccount || order.userId || '-' }}</dd>
              <dt>发货类型</dt><dd>{{ order.deliveryType || '-' }}</dd>
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
              <dt>支付方式</dt><dd>{{ order.payMethod || '-' }}</dd>
              <dt>支付流水</dt><dd>{{ order.paymentNo || '-' }}</dd>
              <dt>支付时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>完成时间</dt><dd>{{ formatTime(order.deliveredAt) }}</dd>
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
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
</style>
