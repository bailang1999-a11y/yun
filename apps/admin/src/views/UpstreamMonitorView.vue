<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Activity, RefreshCw, RotateCw, WalletCards } from 'lucide-vue-next'
import { fetchOrders } from '../api/orders'
import { fetchSuppliers, refreshSupplierBalance } from '../api/suppliers'
import { subscribeOrderEvents } from '../api/realtime'
import type { Order, Supplier } from '../types/operations'
import { formatDateTime, formatMoney } from '../utils/formatters'

const orders = ref<Order[]>([])
const suppliers = ref<Supplier[]>([])
const loading = ref(false)
const syncing = ref(false)
const operating = ref(false)
const lastSyncedAt = ref('')
const liveFlash = ref(false)
let unsubscribeRealtime: (() => void) | undefined
let flashTimer: number | undefined

const lowBalanceSuppliers = computed(() => suppliers.value.filter((item) => Number(item.balance) < 300 || item.status !== 'ENABLED'))
const channelEvents = computed(() =>
  orders.value
    .flatMap((order) =>
      (order.channelAttempts || []).map((attempt) => ({
        id: `${order.orderNo}-${attempt.channelId}-${attempt.attemptedAt}`,
        orderNo: order.orderNo,
        supplier: attempt.supplierName,
        upstreamGoodsId: attempt.supplierGoodsId,
        type: attempt.status === 'SUCCESS' ? '采购成功' : '渠道异常',
        before: attempt.status === 'SUCCESS' ? '待采购' : '可用',
        after: attempt.status === 'SUCCESS' ? '已发货' : '不可用',
        action: attempt.status === 'SUCCESS' ? '订单已完成' : '继续切换下一渠道',
        message: attempt.message,
        status: attempt.status,
        time: attempt.attemptedAt || order.createdAt
      }))
    )
    .sort((a, b) => new Date(b.time || '').getTime() - new Date(a.time || '').getTime())
    .slice(0, 12)
)

onMounted(() => {
  void loadMonitor()
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type !== 'ORDER_UPDATED') return
    triggerFlash()
    void loadMonitor({ silent: true })
  })
})

onBeforeUnmount(() => {
  unsubscribeRealtime?.()
  if (flashTimer) window.clearTimeout(flashTimer)
})

function formatTime(value?: string) {
  return formatDateTime(value)
}

async function loadMonitor(options: { silent?: boolean } = {}) {
  if (loading.value || syncing.value) return
  if (options.silent) syncing.value = true
  else loading.value = true

  try {
    const [nextOrders, nextSuppliers] = await Promise.all([fetchOrders({ goodsType: 'DIRECT' }), fetchSuppliers()])
    orders.value = nextOrders
    suppliers.value = nextSuppliers
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch {
    if (!options.silent) ElMessage.error('上游监控数据加载失败')
  } finally {
    loading.value = false
    syncing.value = false
  }
}

async function syncAllBalances() {
  operating.value = true
  try {
    await Promise.all(suppliers.value.map((supplier) => refreshSupplierBalance(supplier.id)))
    ElMessage.success('供应商余额已同步')
    await loadMonitor()
  } catch {
    ElMessage.error('同步失败')
  } finally {
    operating.value = false
  }
}

function triggerFlash() {
  liveFlash.value = true
  if (flashTimer) window.clearTimeout(flashTimer)
  flashTimer = window.setTimeout(() => {
    liveFlash.value = false
  }, 1400)
}
</script>

<template>
  <section class="monitor-shell">
    <div class="monitor-head liquid-admin-panel">
      <div>
        <span>Upstream Monitor</span>
        <h2>上游监控看板</h2>
      </div>
      <div class="head-actions">
        <span class="live-badge" :class="{ flash: liveFlash }" role="status" aria-live="polite">
          <i :class="{ pulse: syncing || liveFlash }" />
          {{ syncing ? '实时同步中' : lastSyncedAt ? `已同步 ${lastSyncedAt}` : '实时监听中' }}
        </span>
        <el-button :icon="RotateCw" :loading="operating" @click="syncAllBalances">全量同步余额</el-button>
        <el-button :icon="RefreshCw" :loading="loading || syncing" @click="() => loadMonitor()">刷新</el-button>
      </div>
    </div>

    <div class="metric-grid">
      <article class="metric-card">
        <Activity :size="18" />
        <span>直充订单</span>
        <strong>{{ orders.length }}</strong>
      </article>
      <article class="metric-card" :data-warn="lowBalanceSuppliers.length > 0">
        <WalletCards :size="18" />
        <span>余额/状态预警</span>
        <strong>{{ lowBalanceSuppliers.length }}</strong>
      </article>
      <article class="metric-card">
        <RotateCw :size="18" />
        <span>渠道尝试</span>
        <strong>{{ channelEvents.length }}</strong>
      </article>
    </div>

    <section class="panel-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>实时变更流</h2>
          <span>{{ channelEvents.length }} 条</span>
        </div>
        <div class="change-feed">
          <article v-for="item in channelEvents" :key="item.id" :data-status="item.status">
            <div>
              <strong>{{ item.supplier }}</strong>
              <span>{{ item.orderNo }}</span>
            </div>
            <p>{{ item.upstreamGoodsId }} · {{ item.type }} · {{ item.before }} → {{ item.after }}</p>
            <small>{{ item.action }}，{{ item.message }} · {{ formatTime(item.time) }}</small>
          </article>
          <section v-if="!channelEvents.length" class="empty">暂无直充渠道变更，产生直充订单后会自动出现。</section>
        </div>
      </article>

      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>供应商健康</h2>
          <span>{{ suppliers.length }} 个上游</span>
        </div>
        <div class="supplier-list">
          <article v-for="supplier in suppliers" :key="supplier.id" :data-warn="Number(supplier.balance) < 300 || supplier.status !== 'ENABLED'">
            <div>
              <strong>{{ supplier.name }}</strong>
              <span>{{ supplier.status === 'ENABLED' ? '启用' : '停用' }}</span>
            </div>
            <p>{{ supplier.baseUrl }}</p>
            <small>余额 {{ formatMoney(supplier.balance) }} · {{ formatTime(supplier.lastSyncAt) }}</small>
          </article>
          <section v-if="!suppliers.length" class="empty">暂无供应商，请先在供应商管理中新增。</section>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.monitor-shell {
  display: grid;
  gap: 14px;
}

.monitor-head,
.panel,
.metric-card {
  padding: 18px;
  border-radius: 22px;
}

.monitor-head,
.panel-head,
.head-actions,
.metric-card,
.change-feed article div,
.supplier-list article div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.monitor-head span,
.panel-head span,
.metric-card span,
.change-feed span,
.change-feed small,
.supplier-list span,
.supplier-list small {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.monitor-head h2,
.panel-head h2 {
  margin: 4px 0 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 20px;
}

.live-badge {
  height: 32px;
  padding: 0 11px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.live-badge.flash {
  color: #00ffc3;
  border-color: rgba(0, 255, 195, 0.25);
  box-shadow: 0 0 24px rgba(0, 255, 195, 0.14);
}

.live-badge i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #00ffc3;
  box-shadow: 0 0 14px rgba(0, 255, 195, 0.5);
}

.live-badge i.pulse {
  animation: live-pulse 0.9s ease-in-out infinite;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.metric-card svg {
  color: #00ffc3;
  filter: drop-shadow(0 0 18px rgba(0, 255, 195, 0.35));
}

.metric-card[data-warn="true"] svg {
  color: #ffab00;
}

.metric-card strong {
  margin-left: auto;
  color: rgba(255, 255, 255, 0.92);
  font-size: 26px;
}

.panel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 0.75fr);
  gap: 14px;
}

.change-feed,
.supplier-list {
  display: grid;
  gap: 10px;
}

.change-feed article,
.supplier-list article,
.empty {
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.change-feed article[data-status="SUCCESS"] {
  border-color: rgba(0, 255, 195, 0.2);
  box-shadow: 0 0 22px rgba(0, 255, 195, 0.08);
}

.change-feed article:not([data-status="SUCCESS"]),
.supplier-list article[data-warn="true"] {
  border-color: rgba(255, 171, 0, 0.22);
  box-shadow: 0 0 22px rgba(255, 171, 0, 0.08);
}

.change-feed strong,
.supplier-list strong {
  color: rgba(255, 255, 255, 0.88);
}

.change-feed p,
.supplier-list p {
  margin: 8px 0;
  color: rgba(255, 255, 255, 0.68);
  overflow-wrap: anywhere;
}

.empty {
  color: rgba(255, 255, 255, 0.56);
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
