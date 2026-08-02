<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Activity,
  CheckCircle2,
  ChevronDown,
  ClipboardList,
  CircleDollarSign,
  Download,
  Ellipsis,
  PackageCheck,
  RefreshCw,
  RotateCcw,
  Search,
  Trash2,
  Truck,
  XCircle
} from 'lucide-vue-next'
import { deleteOrder, exportOrdersExcel, fetchOrdersPage, markOrderFailed, markOrderSuccess, refreshUnfinishedOrders } from '../api/orders'
import { subscribeOrderEvents } from '../api/realtime'
import type { Order } from '../types/operations'
import OrderBuyerCell from '../components/OrderBuyerCell.vue'
import OrderDurationText from '../components/OrderDurationText.vue'
import OrderGoodsCell from '../components/OrderGoodsCell.vue'
import OrderPaymentBadge from '../components/OrderPaymentBadge.vue'
import OrderSourceBadge from '../components/OrderSourceBadge.vue'
import OrderStatusBadge from '../components/OrderStatusBadge.vue'
import RechargeAccountTag from '../components/RechargeAccountTag.vue'
import OrderDetailView from './OrderDetailView.vue'
import {
  formatDateTime,
  formatDeliveryType,
  formatMoney,
  orderStatusOptions
} from '../utils/formatters'

const orders = ref<Order[]>([])
const loading = ref(false)
const syncing = ref(false)
const upstreamRefreshing = ref(false)
const exporting = ref(false)
const lastSyncedAt = ref('')
const operatingOrder = ref('')
const expandedOrderNo = ref('')
const orderDetailCache = reactive(new Map<string, Order>())
const ordersTableArea = ref<HTMLElement | null>(null)
const nowTick = ref(Date.now())
const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
})
let refreshTimer: number | undefined
let durationTimer: number | undefined
let tableResizeFrame: number | undefined
let tableResizeObserver: ResizeObserver | undefined
let pageSizeReloadPending = false
let unsubscribeRealtime: (() => void) | undefined
const filters = reactive({
  search: '',
  status: '',
  goodsType: '',
  timeRange: 'all'
})

const statusOptions = orderStatusOptions
const timeRangeOptions = [
  { label: '全部时间', value: 'all' },
  { label: '当天', value: 'today' },
  { label: '近 24 小时', value: '24h' },
  { label: '近 1 周', value: '7d' },
  { label: '近 1 月', value: '30d' }
]
const TABLE_HEADER_HEIGHT = 42
const TABLE_HORIZONTAL_SCROLLBAR_HEIGHT = 12
const ORDER_ROW_HEIGHT = 58

function hasExternalAmount(value?: number | string) {
  return value !== undefined && value !== null && value !== '' && Number.isFinite(Number(value))
}

function formatExternalAmount(value?: number | string) {
  return hasExternalAmount(value) ? formatMoney(value) : '未提供'
}

function orderDurationTone(status?: string) {
  if (status === 'DELIVERED') return 'success'
  if (['FAILED', 'REFUNDED', 'CANCELLED', 'CLOSED'].includes(status || '')) return 'danger'
  return 'processing'
}

function toggleOrderDetails(row: Order) {
  expandedOrderNo.value = expandedOrderNo.value === row.orderNo ? '' : row.orderNo
}

function handleOrderRowClick(row: Order, _column: unknown, event: Event) {
  const target = event.target
  if (target instanceof Element && target.closest('button, a, input, select, textarea, [role="button"], [role="menuitem"]')) return
  toggleOrderDetails(row)
}

function orderRowClassName({ row }: { row: Order }) {
  return expandedOrderNo.value === row.orderNo ? 'is-expanded' : ''
}

function handleDetailUpdated(updatedOrder: Order) {
  orderDetailCache.set(updatedOrder.orderNo, updatedOrder)
  orders.value = orders.value.map((item) => (item.orderNo === updatedOrder.orderNo ? updatedOrder : item))
}

async function copyLocalOrderNo(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('本地订单号已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

const orderSummary = computed(() => {
  const pricedOrders = orders.value.filter((order) => hasExternalAmount(order.externalMaxAmount))
  const totalAmount = pricedOrders.reduce((sum, order) => sum + Number(order.externalMaxAmount), 0)
  const missingAmountCount = orders.value.length - pricedOrders.length
  const activeCount = orders.value.filter((order) => ['UNPAID', 'PROCURING', 'WAITING_MANUAL'].includes(order.status)).length
  const failedCount = orders.value.filter((order) => ['FAILED', 'REFUNDED', 'CANCELLED'].includes(order.status)).length
  const deliveredCount = orders.value.filter((order) => order.status === 'DELIVERED').length

  return [
    { label: '当前结果', value: `${orders.value.length}`, hint: '笔订单', icon: ClipboardList, tone: 'total' },
    {
      label: '订单金额',
      value: formatMoney(totalAmount),
      hint: missingAmountCount ? `当前筛选汇总 · ${missingAmountCount} 笔未提供` : '当前筛选汇总',
      icon: CircleDollarSign,
      tone: 'money'
    },
    { label: '处理中', value: `${activeCount}`, hint: '待支付 / 采购 / 人工', icon: Activity, tone: 'active' },
    { label: '已发货', value: `${deliveredCount}`, hint: failedCount ? `${failedCount} 笔异常或关闭` : '暂无异常', icon: PackageCheck, tone: 'done' }
  ]
})

function formatTime(value?: string) {
  return formatDateTime(value, { compact: true })
}

function createdFromForRange(range: string) {
  if (range === 'all') return undefined
  const now = new Date()
  const start = new Date(now)
  if (range === 'today') {
    start.setHours(0, 0, 0, 0)
  } else {
    const duration: Record<string, number> = {
      '24h': 24 * 60 * 60 * 1000,
      '7d': 7 * 24 * 60 * 60 * 1000,
      '30d': 30 * 24 * 60 * 60 * 1000
    }
    const milliseconds = duration[range]
    if (!milliseconds) return undefined
    start.setTime(now.getTime() - milliseconds)
  }
  return start.toISOString()
}

function orderQuery() {
  return {
    search: filters.search,
    status: filters.status,
    goodsType: filters.goodsType,
    createdFrom: createdFromForRange(filters.timeRange)
  }
}

function deliveryClass(value?: string) {
  const key = String(value || '').toUpperCase()
  if (key === 'DIRECT') return 'direct'
  if (key === 'CARD') return 'card'
  if (key === 'MANUAL') return 'manual'
  return 'unknown'
}

function syncPageSizeFromTable(reload = true) {
  const tableHeight = ordersTableArea.value?.clientHeight || 0
  if (tableHeight <= TABLE_HEADER_HEIGHT + TABLE_HORIZONTAL_SCROLLBAR_HEIGHT) return

  const availableRowsHeight = tableHeight - TABLE_HEADER_HEIGHT - TABLE_HORIZONTAL_SCROLLBAR_HEIGHT
  const nextPageSize = Math.max(1, Math.floor(availableRowsHeight / ORDER_ROW_HEIGHT))
  if (nextPageSize === pagination.pageSize) return

  const firstVisibleOrderIndex = (pagination.page - 1) * pagination.pageSize
  pagination.pageSize = nextPageSize
  pagination.page = Math.floor(firstVisibleOrderIndex / nextPageSize) + 1

  if (!reload) return
  if (loading.value || syncing.value || upstreamRefreshing.value) {
    pageSizeReloadPending = true
    return
  }
  void loadOrders()
}

function schedulePageSizeSync() {
  if (tableResizeFrame) window.cancelAnimationFrame(tableResizeFrame)
  tableResizeFrame = window.requestAnimationFrame(() => {
    tableResizeFrame = undefined
    syncPageSizeFromTable()
  })
}

async function loadOrders(options: { silent?: boolean } = {}) {
  if (loading.value || syncing.value || upstreamRefreshing.value) return
  if (options.silent) {
    syncing.value = true
  } else {
    loading.value = true
  }

  try {
    const result = await fetchOrdersPage({ ...orderQuery(), page: pagination.page, pageSize: pagination.pageSize })
    orders.value = result.items
    pagination.total = result.total
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch {
    if (!options.silent) ElMessage.error('订单列表加载失败')
  } finally {
    loading.value = false
    syncing.value = false
    if (pageSizeReloadPending) {
      pageSizeReloadPending = false
      void loadOrders()
    }
  }
}

async function refreshOrdersWithUpstream() {
  if (loading.value || syncing.value || upstreamRefreshing.value) return
  upstreamRefreshing.value = true
  try {
    const result = await refreshUnfinishedOrders()
    const page = await fetchOrdersPage({ ...orderQuery(), page: pagination.page, pageSize: pagination.pageSize })
    orders.value = page.items
    pagination.total = page.total
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
    if (pageSizeReloadPending) {
      pageSizeReloadPending = false
      void loadOrders()
    }
  }
}

async function exportOrders() {
  exporting.value = true

  try {
    const blob = await exportOrdersExcel(orderQuery())
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
  expandedOrderNo.value = ''
  filters.search = ''
  filters.status = ''
  filters.goodsType = ''
  filters.timeRange = 'all'
  pagination.page = 1
  void loadOrders()
}

function searchOrders() {
  expandedOrderNo.value = ''
  pagination.page = 1
  void loadOrders()
}

function handlePageChange(page: number) {
  expandedOrderNo.value = ''
  pagination.page = page
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
      orderDetailCache.delete(row.orderNo)
      if (expandedOrderNo.value === row.orderNo) expandedOrderNo.value = ''
      pagination.total = Math.max(0, pagination.total - 1)
      ElMessage.success('订单已删除')
    }
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '手动处理失败'
    ElMessage.error(action === 'delete' ? `订单删除失败：${message}` : message)
  } finally {
    operatingOrder.value = ''
  }
}

onMounted(() => {
  syncPageSizeFromTable(false)
  if ('ResizeObserver' in window && ordersTableArea.value) {
    tableResizeObserver = new ResizeObserver(schedulePageSizeSync)
    tableResizeObserver.observe(ordersTableArea.value)
  }
  void loadOrders()
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
  if (tableResizeFrame) window.cancelAnimationFrame(tableResizeFrame)
  tableResizeObserver?.disconnect()
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

      <div class="filter-bar" aria-label="订单筛选">
        <el-select v-model="filters.status" clearable placeholder="订单状态" @change="searchOrders">
          <el-option v-for="status in statusOptions" :key="status.value" :label="status.label" :value="status.value" />
        </el-select>
        <el-select v-model="filters.goodsType" clearable placeholder="发货类型" @change="searchOrders">
          <el-option label="卡密" value="CARD" />
          <el-option label="直充" value="DIRECT" />
          <el-option label="代充" value="MANUAL" />
        </el-select>
        <el-select v-model="filters.timeRange" placeholder="创建时间" @change="searchOrders">
          <el-option v-for="option in timeRangeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" @click="searchOrders">查询</el-button>
        <el-button class="ghost-action" :icon="RotateCcw" @click="resetFilters">重置</el-button>
        <el-input
          v-model="filters.search"
          class="search-input"
          clearable
          placeholder="订单号 / 商品 / 充值账号"
          :prefix-icon="Search"
          @keyup.enter="searchOrders"
          @clear="searchOrders"
        />
      </div>
    </section>

    <section class="orders-table-shell">
      <div class="table-caption">
        <div>
          <strong>订单明细</strong>
          <span>{{ pagination.total ? `共 ${pagination.total} 笔，每页 ${pagination.pageSize} 笔` : '暂无订单数据' }}</span>
        </div>
      </div>

      <div ref="ordersTableArea" class="orders-table-area">
        <el-table
          v-loading="loading"
          :data="orders"
          :expand-row-keys="expandedOrderNo ? [expandedOrderNo] : []"
          :row-class-name="orderRowClassName"
          row-key="orderNo"
          height="100%"
          class="orders-table"
          style="width: 100%"
          @row-click="handleOrderRowClick"
        >
      <template #empty>
        <div class="orders-empty">
          <ClipboardList :size="34" />
          <strong>{{ loading ? '正在加载订单' : '没有匹配的订单' }}</strong>
          <span>{{ loading ? '稍等片刻，列表马上回来。' : '可以调整筛选条件，或先去前台完成一笔测试订单。' }}</span>
          <el-button v-if="!loading" type="primary" :icon="RefreshCw" @click="resetFilters">清空筛选并刷新</el-button>
        </div>
      </template>
      <el-table-column label="订单信息" width="260" fixed="left">
        <template #default="{ row }">
          <div class="order-primary-cell">
            <div class="order-meta-line">
              <time>{{ formatTime(row.createdAt) }}</time>
              <span aria-hidden="true">·</span>
              <span class="order-duration" :class="`is-${orderDurationTone(row.status)}`">
                耗时 <OrderDurationText :order="row" :now="nowTick" />
              </span>
            </div>
            <div>
              <button
                type="button"
                class="local-order-number"
                :title="`${row.orderNo} · 点击复制`"
                aria-label="复制本地订单号"
                @click="copyLocalOrderNo(row.orderNo)"
              >
                {{ row.orderNo }}
              </button>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="商品 / 货源" min-width="320" show-overflow-tooltip>
        <template #default="{ row }">
          <OrderGoodsCell :order="row" />
        </template>
      </el-table-column>
      <el-table-column label="充值账号" min-width="184" align="center" header-align="center">
        <template #default="{ row }">
          <div class="recharge-account-cell">
            <RechargeAccountTag :value="row.rechargeAccount" />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="160">
        <template #default="{ row }">
          <div class="amount-breakdown">
            <div>
              <small>实付金额</small>
              <strong :class="{ missing: !hasExternalAmount(row.externalMaxAmount) }">
                {{ formatExternalAmount(row.externalMaxAmount) }}
              </strong>
            </div>
            <div>
              <small>成本金额</small>
              <span>{{ formatMoney(row.amount) }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="支付方式" width="130">
        <template #default="{ row }">
          <OrderPaymentBadge :value="row.payMethod" />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <OrderStatusBadge :status="row.status" />
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
          <OrderSourceBadge :source="row.orderSource" :request-id="row.requestId" :platform="row.platform" :remark="row.buyerRemark" />
        </template>
      </el-table-column>
      <el-table-column label="下单用户" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <OrderBuyerCell :order="row" />
        </template>
      </el-table-column>
      <el-table-column
        type="expand"
        width="1"
        class-name="order-expand-column"
        label-class-name="order-expand-column"
      >
        <template #default="{ row }">
          <div class="order-expanded-detail" @click.stop>
            <OrderDetailView
              :order-number="row.orderNo"
              :initial-order="orderDetailCache.get(row.orderNo) || row"
              embedded
              @updated="handleDetailUpdated"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <button
              type="button"
              class="order-expand-trigger"
              :class="{ expanded: expandedOrderNo === row.orderNo }"
              :title="expandedOrderNo === row.orderNo ? '收起订单详情' : '展开订单详情'"
              :aria-label="expandedOrderNo === row.orderNo ? '收起订单详情' : '展开订单详情'"
              :aria-expanded="expandedOrderNo === row.orderNo"
              @click.stop="toggleOrderDetails(row)"
            >
              <ChevronDown :size="17" />
            </button>
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
      </div>
      <div class="table-pagination">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :current-page="pagination.page"
          :page-size="pagination.pageSize"
          :total="pagination.total"
          @current-change="handlePageChange"
        />
      </div>
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
  --order-font-primary: 13px;
  --order-font-secondary: 11px;
  --order-font-control: 12px;
  --order-weight-primary: 600;
  --order-weight-secondary: 500;
  --order-weight-control: 600;
  --order-line-primary: 19px;
  --order-line-secondary: 16px;
  --order-line-control: 18px;
  --order-row-primary-size: 13px;
  --order-row-primary-weight: 600;
  --order-row-primary-line: 18px;
  --order-row-secondary-size: 12px;
  --order-row-secondary-weight: 500;
  --order-row-secondary-line: 17px;
  height: calc(100vh - 48px);
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.orders-hero {
  flex: 0 0 auto;
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
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.title-block h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 21px;
  font-weight: 700;
  line-height: 30px;
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
  flex: 0 0 auto;
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
  grid-template-columns: 144px 144px 144px 88px 88px minmax(280px, 1fr);
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
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
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
  font-weight: 700;
  line-height: 27px;
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
  min-height: 0;
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  overflow: hidden;
  border-radius: 16px;
  background: rgba(5, 12, 25, 0.34);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.orders-table-area {
  min-height: 0;
  flex: 1 1 auto;
}

.orders-table {
  height: 100%;
}

.table-caption {
  min-height: 48px;
  display: flex;
  flex: 0 0 auto;
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
  font-weight: 650;
  line-height: 20px;
}

.table-caption span {
  color: rgba(255, 255, 255, 0.46);
  font-size: var(--order-font-secondary);
  font-weight: var(--order-weight-secondary);
  line-height: var(--order-line-secondary);
}

.table-pagination {
  flex: 0 0 auto;
  display: flex;
  justify-content: flex-end;
  padding: 12px 14px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.08);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.order-expand-trigger {
  width: 30px;
  height: 30px;
  display: inline-grid;
  flex: 0 0 30px;
  place-items: center;
  padding: 0;
  color: rgba(220, 234, 248, 0.56);
  border: 1px solid rgba(183, 215, 244, 0.12);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease, background 160ms ease;
}

.order-expand-trigger:hover,
.order-expand-trigger.expanded {
  color: #93c5fd;
  border-color: rgba(96, 165, 250, 0.28);
  background: rgba(59, 130, 246, 0.12);
}

.order-expand-trigger svg {
  transition: transform 180ms cubic-bezier(0.22, 1, 0.36, 1);
}

.order-expand-trigger.expanded svg {
  transform: rotate(180deg);
}

.row-action {
  --el-button-bg-color: rgba(255, 255, 255, 0.07);
  --el-button-border-color: rgba(255, 255, 255, 0.12);
  --el-button-text-color: rgba(255, 255, 255, 0.84);
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
}

.order-primary-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.order-meta-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: var(--order-row-primary-size);
  font-weight: var(--order-row-primary-weight);
  line-height: var(--order-row-primary-line);
  overflow: hidden;
  white-space: nowrap;
}

.order-meta-line time {
  color: rgba(255, 255, 255, 0.88);
  font-variant-numeric: tabular-nums;
}

.order-meta-line > span[aria-hidden="true"] {
  color: rgba(255, 255, 255, 0.24);
}

.order-duration {
  min-width: 0;
  overflow: hidden;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-duration.is-processing {
  color: #60a5fa;
}

.order-duration.is-success {
  color: #4ade80;
}

.order-duration.is-danger {
  color: #f87171;
}

.order-primary-cell > div {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.local-order-number {
  min-width: 0;
  overflow: hidden;
  padding: 2px 0;
  color: rgba(255, 255, 255, 0.44);
  font-size: var(--order-row-secondary-size);
  font-weight: var(--order-row-secondary-weight);
  line-height: var(--order-row-secondary-line);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  transition: color 160ms ease, background 160ms ease;
}

.local-order-number:hover {
  color: rgba(219, 234, 254, 0.72);
  background: rgba(59, 130, 246, 0.08);
}

.local-order-number:focus-visible {
  outline: 2px solid rgba(96, 165, 250, 0.7);
  outline-offset: 1px;
}

.recharge-account-cell {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.amount-breakdown {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.amount-breakdown > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.amount-breakdown > div:first-child small,
.amount-breakdown > div:first-child strong {
  font-size: var(--order-row-primary-size);
  font-weight: var(--order-row-primary-weight);
  line-height: var(--order-row-primary-line);
}

.amount-breakdown > div:last-child small,
.amount-breakdown > div:last-child span {
  font-size: var(--order-row-secondary-size);
  font-weight: var(--order-row-secondary-weight);
  line-height: var(--order-row-secondary-line);
}

.amount-breakdown small {
  color: rgba(255, 255, 255, 0.42);
  white-space: nowrap;
}

.amount-breakdown strong {
  color: #fff3a3;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.amount-breakdown strong.missing {
  color: rgba(255, 255, 255, 0.38);
}

.amount-breakdown span {
  color: rgba(255, 255, 255, 0.58);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
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
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
  background: rgba(255, 255, 255, 0.042) !important;
}

.orders-table :deep(.el-table__row) {
  cursor: pointer;
  transition: background 160ms ease;
}

.orders-table :deep(.el-table__row.is-expanded > td.el-table__cell) {
  background: rgba(59, 130, 246, 0.065) !important;
}

.orders-table :deep(.el-table__row td.el-table__cell) {
  height: 58px;
  border-bottom-color: rgba(255, 255, 255, 0.065);
}

.orders-table :deep(td.order-expand-column),
.orders-table :deep(th.order-expand-column) {
  width: 1px !important;
  padding: 0 !important;
}

.orders-table :deep(.order-expand-column .cell) {
  width: 0;
  overflow: hidden;
  padding: 0 !important;
}

.orders-table :deep(td.el-table__expanded-cell) {
  height: auto;
  padding: 0 !important;
  background: rgba(4, 12, 24, 0.72) !important;
}

.order-expanded-detail {
  width: 100%;
  min-width: 0;
}

.orders-table :deep(.el-table__fixed-right),
.orders-table :deep(.el-table__fixed) {
  background: rgba(7, 16, 30, 0.86);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
}

.orders-table :deep(td.el-table__cell .cell) {
  display: flex;
  align-items: center;
  font-size: var(--order-font-primary);
  font-weight: var(--order-weight-primary);
  line-height: var(--order-line-primary);
}

.orders-table :deep(.el-table__cell:nth-child(2) .cell),
.orders-table :deep(.el-table__cell:nth-child(3) .cell),
.orders-table :deep(.el-table__cell:nth-child(9) .cell) {
  align-items: stretch;
}

.orders-table :deep(.el-tag) {
  min-width: 72px;
  justify-content: center;
  border-radius: 9px;
  font-size: var(--order-font-control);
  font-weight: var(--order-weight-control);
  line-height: var(--order-line-control);
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
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .search-input {
    grid-column: auto;
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

@media (max-width: 900px) {
  .filter-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .filter-bar {
    grid-template-columns: minmax(0, 1fr);
  }
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}

@media (prefers-reduced-motion: reduce) {
  .order-expand-trigger svg {
    transition: none;
  }
}
</style>
