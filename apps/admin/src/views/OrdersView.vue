<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Activity,
  CheckCircle2,
  ClipboardList,
  CircleDollarSign,
  Download,
  Ellipsis,
  Eye,
  PackageCheck,
  RefreshCw,
  RotateCcw,
  Search,
  Trash2,
  Truck,
  XCircle
} from 'lucide-vue-next'
import { deleteOrder, exportOrdersExcel, fetchOrders, markOrderFailed, markOrderSuccess, refreshUnfinishedOrders } from '../api/orders'
import { subscribeOrderEvents } from '../api/realtime'
import type { Order } from '../types/operations'
import OrderBuyerCell from '../components/OrderBuyerCell.vue'
import OrderDurationText from '../components/OrderDurationText.vue'
import OrderGoodsCell from '../components/OrderGoodsCell.vue'
import OrderPaymentBadge from '../components/OrderPaymentBadge.vue'
import OrderSourceBadge from '../components/OrderSourceBadge.vue'
import {
  formatDateTime,
  formatDeliveryType,
  formatMoney,
  formatOrderStatus,
  orderStatusOptions,
  orderStatusTagType
} from '../utils/formatters'

const router = useRouter()
const orders = ref<Order[]>([])
const loading = ref(false)
const syncing = ref(false)
const upstreamRefreshing = ref(false)
const exporting = ref(false)
const lastSyncedAt = ref('')
const operatingOrder = ref('')
const nowTick = ref(Date.now())
let refreshTimer: number | undefined
let durationTimer: number | undefined
let unsubscribeRealtime: (() => void) | undefined
const filters = reactive({
  search: '',
  status: '',
  goodsType: ''
})

const statusOptions = orderStatusOptions

const orderSummary = computed(() => {
  const totalAmount = orders.value.reduce((sum, order) => sum + (Number(order.amount) || 0), 0)
  const activeCount = orders.value.filter((order) => ['UNPAID', 'PROCURING', 'WAITING_MANUAL'].includes(order.status)).length
  const failedCount = orders.value.filter((order) => ['FAILED', 'REFUNDED', 'CANCELLED'].includes(order.status)).length
  const deliveredCount = orders.value.filter((order) => order.status === 'DELIVERED').length

  return [
    { label: '当前结果', value: `${orders.value.length}`, hint: '笔订单', icon: ClipboardList, tone: 'total' },
    { label: '订单金额', value: formatMoney(totalAmount), hint: '当前筛选汇总', icon: CircleDollarSign, tone: 'money' },
    { label: '处理中', value: `${activeCount}`, hint: '待支付 / 采购 / 人工', icon: Activity, tone: 'active' },
    { label: '已发货', value: `${deliveredCount}`, hint: failedCount ? `${failedCount} 笔异常或关闭` : '暂无异常', icon: PackageCheck, tone: 'done' }
  ]
})

function formatTime(value?: string) {
  return formatDateTime(value, { compact: true })
}

function deliveryClass(value?: string) {
  const key = String(value || '').toUpperCase()
  if (key === 'DIRECT') return 'direct'
  if (key === 'CARD') return 'card'
  if (key === 'MANUAL') return 'manual'
  return 'unknown'
}

async function loadOrders(options: { silent?: boolean } = {}) {
  if (loading.value || syncing.value || upstreamRefreshing.value) return
  if (options.silent) {
    syncing.value = true
  } else {
    loading.value = true
  }

  try {
    orders.value = await fetchOrders(filters)
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch {
    if (!options.silent) ElMessage.error('订单列表加载失败')
  } finally {
    loading.value = false
    syncing.value = false
  }
}

async function refreshOrdersWithUpstream() {
  if (loading.value || syncing.value || upstreamRefreshing.value) return
  upstreamRefreshing.value = true
  try {
    const result = await refreshUnfinishedOrders()
    orders.value = await fetchOrders(filters)
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    if (result.total === 0) {
      ElMessage.info('暂无可刷新上游状态的未完成订单')
    } else if (result.failed > 0) {
      ElMessage.warning(`已刷新 ${result.refreshed}/${result.total} 笔，失败 ${result.failed} 笔${result.firstError ? `：${result.firstError}` : ''}`)
    } else {
      ElMessage.success(`已刷新 ${result.refreshed} 笔未完成订单，更新 ${result.changed} 笔`)
    }
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '未完成订单状态刷新失败'
    ElMessage.error(message)
  } finally {
    upstreamRefreshing.value = false
  }
}

async function exportOrders() {
  exporting.value = true

  try {
    const blob = await exportOrdersExcel(filters)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `喜易云订单导出-${new Date().toISOString().slice(0, 10)}.xlsx`
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('订单导出已开始下载')
  } catch {
    ElMessage.error('订单导出失败')
  } finally {
    exporting.value = false
  }
}

function resetFilters() {
  filters.search = ''
  filters.status = ''
  filters.goodsType = ''
  void loadOrders()
}

async function handleManualCommand(command: unknown, row: Order) {
  const action = String(command)
  const copy: Record<string, { title: string; message: string; type: 'success' | 'warning' | 'error' }> = {
    success: { title: '手动标记成功', message: `确认将订单「${row.orderNo}」标记为成功？`, type: 'success' },
    failed: { title: '手动标记失败', message: `确认将订单「${row.orderNo}」标记为失败？`, type: 'warning' },
    delete: { title: '删除订单', message: `确认删除订单「${row.orderNo}」？删除后列表中不再展示。`, type: 'error' }
  }
  const config = copy[action]
  if (!config) return

  try {
    await ElMessageBox.confirm(config.message, config.title, {
      type: config.type,
      confirmButtonText: action === 'delete' ? '确认删除' : '确认处理',
      cancelButtonText: '取消',
      customClass: 'xiyiyun-glass-message-box'
    })
  } catch {
    return
  }

  operatingOrder.value = `${row.orderNo}:${action}`
  try {
    if (action === 'success') {
      const next = await markOrderSuccess(row.orderNo)
      orders.value = orders.value.map((item) => (item.orderNo === row.orderNo ? next : item))
      ElMessage.success('订单已标记成功')
    }
    if (action === 'failed') {
      const next = await markOrderFailed(row.orderNo)
      orders.value = orders.value.map((item) => (item.orderNo === row.orderNo ? next : item))
      ElMessage.success('订单已标记失败')
    }
    if (action === 'delete') {
      await deleteOrder(row.orderNo)
      orders.value = orders.value.filter((item) => item.orderNo !== row.orderNo)
      ElMessage.success('订单已删除')
    }
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '手动处理失败'
    ElMessage.error(action === 'delete' ? `订单删除失败：${message}` : message)
  } finally {
    operatingOrder.value = ''
  }
}

onMounted(loadOrders)

onMounted(() => {
  refreshTimer = window.setInterval(() => {
    if (!exporting.value) void loadOrders({ silent: true })
  }, 10000)
  durationTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type === 'ORDER_UPDATED' && !exporting.value) void loadOrders({ silent: true })
  })
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (durationTimer) window.clearInterval(durationTimer)
  unsubscribeRealtime?.()
})
</script>

<template>
  <article class="orders-page panel">
    <header class="orders-hero">
      <div class="title-block">
        <span>运营订单中心</span>
        <h2>订单管理</h2>
      </div>
      <div class="head-actions">
        <span class="live-badge" role="status" aria-live="polite">
          <i :class="{ pulse: syncing }" />
          {{ syncing ? '同步中' : lastSyncedAt ? `已同步 ${lastSyncedAt}` : '实时同步' }}
        </span>
        <el-button class="ghost-action" :icon="Download" :loading="exporting" @click="exportOrders">导出 Excel</el-button>
        <el-button type="primary" :icon="RefreshCw" :loading="loading || syncing || upstreamRefreshing" @click="refreshOrdersWithUpstream">
          刷新未完成
        </el-button>
      </div>
    </header>

    <section class="orders-control-strip">
      <div class="filter-bar" aria-label="订单筛选">
        <el-input
          v-model="filters.search"
          class="search-input"
          clearable
          placeholder="订单号 / 商品 / 充值账号"
          :prefix-icon="Search"
          @keyup.enter="() => loadOrders()"
          @clear="() => loadOrders()"
        />
        <el-select v-model="filters.status" clearable placeholder="订单状态" @change="() => loadOrders()">
          <el-option v-for="status in statusOptions" :key="status.value" :label="status.label" :value="status.value" />
        </el-select>
        <el-select v-model="filters.goodsType" clearable placeholder="发货类型" @change="() => loadOrders()">
          <el-option label="卡密" value="CARD" />
          <el-option label="直充" value="DIRECT" />
          <el-option label="代充" value="MANUAL" />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" @click="() => loadOrders()">查询</el-button>
        <el-button class="ghost-action" :icon="RotateCcw" @click="resetFilters">重置</el-button>
      </div>

      <div class="order-summary" aria-label="订单概览">
        <article v-for="item in orderSummary" :key="item.label" class="summary-item" :class="`summary-item--${item.tone}`">
          <span class="summary-icon">
            <component :is="item.icon" :size="16" />
          </span>
          <span class="summary-label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <em>{{ item.hint }}</em>
        </article>
      </div>
    </section>

    <section class="orders-table-shell">
      <div class="table-caption">
        <div>
          <strong>订单明细</strong>
          <span>{{ orders.length ? `当前展示 ${orders.length} 笔订单` : '暂无订单数据' }}</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="orders" height="620" class="orders-table" style="width: 100%">
      <template #empty>
        <div class="orders-empty">
          <ClipboardList :size="34" />
          <strong>{{ loading ? '正在加载订单' : '没有匹配的订单' }}</strong>
          <span>{{ loading ? '稍等片刻，列表马上回来。' : '可以调整筛选条件，或先去前台完成一笔测试订单。' }}</span>
          <el-button v-if="!loading" type="primary" :icon="RefreshCw" @click="resetFilters">清空筛选并刷新</el-button>
        </div>
      </template>
      <el-table-column prop="orderNo" label="订单号" min-width="210" fixed="left" show-overflow-tooltip />
      <el-table-column label="商品 / 货源" min-width="320" show-overflow-tooltip>
        <template #default="{ row }">
          <OrderGoodsCell :order="row" />
        </template>
      </el-table-column>
      <el-table-column label="下单用户" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <OrderBuyerCell :order="row" />
        </template>
      </el-table-column>
      <el-table-column label="金额" width="130">
        <template #default="{ row }">
          <span class="amount-card">
            <small>实付</small>
            <strong>{{ formatMoney(row.amount) }}</strong>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="支付方式" width="130">
        <template #default="{ row }">
          <OrderPaymentBadge :value="row.payMethod" />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="orderStatusTagType(row.status)" effect="dark">{{ formatOrderStatus(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发货类型" width="120">
        <template #default="{ row }">
          <span class="delivery-pill" :class="deliveryClass(row.deliveryType)">
            <Truck :size="13" />
            {{ formatDeliveryType(row.deliveryType) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="132">
        <template #default="{ row }">
          <OrderSourceBadge :source="row.orderSource" :request-id="row.requestId" :platform="row.platform" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="订单处理耗时" width="140">
        <template #default="{ row }"><OrderDurationText :order="row" :now="nowTick" /></template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button class="row-action" size="small" :icon="Eye" @click="router.push({ name: 'order-detail', params: { orderNo: row.orderNo } })">
              详情
            </el-button>
            <el-dropdown
              trigger="click"
              :disabled="Boolean(operatingOrder)"
              @command="handleManualCommand($event, row)"
            >
              <el-button class="row-action" size="small" :icon="Ellipsis" :loading="operatingOrder.startsWith(`${row.orderNo}:`)">
                处理
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="success" :icon="CheckCircle2">标记成功</el-dropdown-item>
                  <el-dropdown-item command="failed" :icon="XCircle">标记失败</el-dropdown-item>
                  <el-dropdown-item command="delete" :icon="Trash2" divided>删除订单</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
      </el-table>
    </section>
  </article>
</template>

<style scoped>
.panel {
  position: relative;
  padding: 16px;
  overflow: hidden;
  border-radius: 18px;
  background:
    radial-gradient(circle at 0% 0%, rgba(0, 255, 195, 0.13), transparent 30%),
    radial-gradient(circle at 100% 0%, rgba(88, 166, 255, 0.16), transparent 32%),
    rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 18px 58px rgba(0, 0, 0, 0.22), inset 0 1px 0 rgba(255, 255, 255, 0.13);
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
}

.orders-page {
  min-height: calc(100vh - 132px);
}

.orders-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 14px;
}

.title-block {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.title-block span {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
}

.title-block h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 21px;
  font-weight: 850;
  letter-spacing: 0;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.live-badge {
  height: 34px;
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

.ghost-action {
  --el-button-bg-color: rgba(255, 255, 255, 0.07);
  --el-button-border-color: rgba(255, 255, 255, 0.12);
  --el-button-text-color: rgba(255, 255, 255, 0.82);
  --el-button-hover-bg-color: rgba(255, 255, 255, 0.11);
  --el-button-hover-border-color: rgba(255, 255, 255, 0.2);
  --el-button-hover-text-color: rgba(255, 255, 255, 0.95);
}

.orders-control-strip {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 12px;
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 16px;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.028)),
    rgba(7, 16, 30, 0.38);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.filter-bar {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) 152px 152px 96px 96px;
  gap: 10px;
  align-items: center;
}

.search-input {
  min-width: 0;
}

.order-summary {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.summary-item {
  position: relative;
  display: grid;
  grid-template-columns: 34px 1fr;
  grid-template-areas:
    "icon label"
    "icon value"
    "icon hint";
  column-gap: 10px;
  row-gap: 2px;
  min-width: 0;
  padding: 12px 13px;
  border-radius: 14px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.048);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease;
}

.summary-item:hover {
  transform: translateY(-1px);
  border-color: rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.065);
}

.summary-icon {
  grid-area: icon;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  align-self: center;
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.07);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.summary-label,
.summary-item em {
  min-width: 0;
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
  font-style: normal;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-label {
  grid-area: label;
}

.summary-item em {
  grid-area: hint;
}

.summary-item strong {
  grid-area: value;
  min-width: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 19px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-item--total .summary-icon {
  color: #b6f7ff;
  background: rgba(6, 182, 212, 0.13);
}

.summary-item--money .summary-icon {
  color: #fef08a;
  background: rgba(234, 179, 8, 0.13);
}

.summary-item--active .summary-icon {
  color: #bfdbfe;
  background: rgba(59, 130, 246, 0.14);
}

.summary-item--done .summary-icon {
  color: #bdf8dc;
  background: rgba(34, 197, 94, 0.12);
}

.orders-table-shell {
  position: relative;
  z-index: 1;
  overflow: hidden;
  border-radius: 16px;
  background: rgba(5, 12, 25, 0.34);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.table-caption {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.08);
  background:
    linear-gradient(90deg, rgba(0, 255, 195, 0.08), transparent 34%),
    rgba(255, 255, 255, 0.035);
}

.table-caption div {
  display: grid;
  gap: 2px;
}

.table-caption strong {
  color: rgba(255, 255, 255, 0.88);
  font-size: 14px;
  font-weight: 800;
}

.table-caption span {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-action {
  --el-button-bg-color: rgba(255, 255, 255, 0.07);
  --el-button-border-color: rgba(255, 255, 255, 0.12);
  --el-button-text-color: rgba(255, 255, 255, 0.84);
}

.amount-card {
  position: relative;
  min-width: 92px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
  padding: 4px 8px 4px 5px;
  overflow: hidden;
  border-radius: 11px;
  color: #fff7ad;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.11), rgba(255, 255, 255, 0.028)),
    radial-gradient(circle at 18% 0%, rgba(250, 204, 21, 0.26), transparent 48%),
    rgba(234, 179, 8, 0.08);
  border: 0.5px solid rgba(250, 204, 21, 0.34);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.13),
    inset 0 -10px 18px rgba(113, 63, 18, 0.12),
    0 8px 22px rgba(234, 179, 8, 0.08);
}

.amount-card::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  border-radius: inherit;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.14), transparent 36%);
  opacity: 0.55;
}

.amount-card small {
  position: relative;
  z-index: 1;
  height: 23px;
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  border-radius: 8px;
  color: rgba(255, 251, 214, 0.78);
  font-size: 10px;
  font-weight: 850;
  line-height: 1;
  background: rgba(7, 16, 30, 0.28);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.amount-card strong {
  position: relative;
  z-index: 1;
  color: #fff3a3;
  font-size: 14px;
  font-weight: 900;
  line-height: 1;
  letter-spacing: 0;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 18px rgba(234, 179, 8, 0.28);
}

.delivery-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 72px;
  height: 28px;
  padding: 0 8px;
  border-radius: 9px;
  color: rgba(255, 255, 255, 0.8);
  font-size: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.055);
}

.delivery-pill.direct {
  color: #b6f7ff;
  border-color: rgba(6, 182, 212, 0.24);
  background: rgba(6, 182, 212, 0.12);
}

.delivery-pill.card {
  color: #ddd6fe;
  border-color: rgba(139, 92, 246, 0.24);
  background: rgba(124, 58, 237, 0.12);
}

.delivery-pill.manual {
  color: #fed7aa;
  border-color: rgba(249, 115, 22, 0.24);
  background: rgba(249, 115, 22, 0.11);
}

.orders-table :deep(.el-table__header th.el-table__cell) {
  height: 42px;
  color: rgba(255, 255, 255, 0.56);
  font-size: 12px;
  font-weight: 750;
  background: rgba(255, 255, 255, 0.042) !important;
}

.orders-table :deep(.el-table__row) {
  transition: background 160ms ease;
}

.orders-table :deep(.el-table__row td.el-table__cell) {
  height: 58px;
  border-bottom-color: rgba(255, 255, 255, 0.065);
}

.orders-table :deep(.el-table__fixed-right),
.orders-table :deep(.el-table__fixed) {
  background: rgba(7, 16, 30, 0.86);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
}

.orders-table :deep(.el-table__cell .cell) {
  display: flex;
  align-items: center;
}

.orders-table :deep(.el-table__cell:nth-child(2) .cell),
.orders-table :deep(.el-table__cell:nth-child(3) .cell) {
  align-items: stretch;
}

.orders-table :deep(.el-tag) {
  min-width: 72px;
  justify-content: center;
  border-radius: 9px;
  font-weight: 750;
}

.orders-empty {
  min-height: 280px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  color: rgba(255, 255, 255, 0.55);
}

.orders-empty svg {
  color: rgba(0, 255, 195, 0.72);
}

.orders-empty strong {
  color: rgba(255, 255, 255, 0.86);
  font-size: 16px;
}

.orders-empty span {
  max-width: 360px;
  color: rgba(255, 255, 255, 0.5);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1280px) {
  .filter-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .order-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .orders-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .head-actions {
    justify-content: flex-start;
  }
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
