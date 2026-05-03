<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CheckCircle2,
  ClipboardList,
  Download,
  Ellipsis,
  Eye,
  RefreshCw,
  Trash2,
  XCircle
} from 'lucide-vue-next'
import { deleteOrder, exportOrdersExcel, fetchOrders, markOrderFailed, markOrderSuccess } from '../api/orders'
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
const exporting = ref(false)
const lastSyncedAt = ref('')
const operatingOrder = ref('')
let refreshTimer: number | undefined
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
    { label: '当前结果', value: `${orders.value.length}`, hint: '笔订单' },
    { label: '订单金额', value: formatMoney(totalAmount), hint: '按当前筛选汇总' },
    { label: '处理中', value: `${activeCount}`, hint: '待支付 / 采购 / 人工' },
    { label: '已发货', value: `${deliveredCount}`, hint: failedCount ? `${failedCount} 笔异常或关闭` : '暂无异常' }
  ]
})

function formatTime(value?: string) {
  return formatDateTime(value, { compact: true })
}

async function loadOrders(options: { silent?: boolean } = {}) {
  if (loading.value || syncing.value) return
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
  } catch {
    ElMessage.error('手动处理失败')
  } finally {
    operatingOrder.value = ''
  }
}

onMounted(loadOrders)

onMounted(() => {
  refreshTimer = window.setInterval(() => {
    if (!exporting.value) void loadOrders({ silent: true })
  }, 10000)
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type === 'ORDER_UPDATED' && !exporting.value) void loadOrders({ silent: true })
  })
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  unsubscribeRealtime?.()
})
</script>

<template>
  <article class="panel">
    <div class="panel-head">
      <h2>订单列表</h2>
      <div class="head-actions">
        <span class="live-badge" role="status" aria-live="polite">
          <i :class="{ pulse: syncing }" />
          {{ syncing ? '同步中' : lastSyncedAt ? `已同步 ${lastSyncedAt}` : '实时同步' }}
        </span>
        <el-button :icon="Download" :loading="exporting" @click="exportOrders">导出 Excel</el-button>
        <el-button :icon="RefreshCw" :loading="loading || syncing" @click="() => loadOrders()">刷新</el-button>
      </div>
    </div>

    <section class="filter-bar">
      <el-input
        v-model="filters.search"
        clearable
        placeholder="订单号 / 商品 / 充值账号"
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
      <el-button type="primary" :loading="loading" @click="() => loadOrders()">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </section>

    <section class="order-summary" aria-label="订单概览">
      <article v-for="item in orderSummary" :key="item.label" class="summary-item">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <em>{{ item.hint }}</em>
      </article>
    </section>

    <el-table v-loading="loading" :data="orders" height="700" style="width: 100%">
      <template #empty>
        <div class="orders-empty">
          <ClipboardList :size="34" />
          <strong>{{ loading ? '正在加载订单' : '没有匹配的订单' }}</strong>
          <span>{{ loading ? '稍等片刻，列表马上回来。' : '可以调整筛选条件，或先去前台完成一笔测试订单。' }}</span>
          <el-button v-if="!loading" type="primary" :icon="RefreshCw" @click="resetFilters">清空筛选并刷新</el-button>
        </div>
      </template>
      <el-table-column prop="orderNo" label="订单号" min-width="190" fixed="left" show-overflow-tooltip />
      <el-table-column label="商品 / 货源" min-width="260" show-overflow-tooltip>
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
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
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
        <template #default="{ row }">{{ formatDeliveryType(row.deliveryType) }}</template>
      </el-table-column>
      <el-table-column label="来源" width="132">
        <template #default="{ row }">
          <OrderSourceBadge :source="row.orderSource" :request-id="row.requestId" :platform="row.platform" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="110">
        <template #default="{ row }"><OrderDurationText :order="row" /></template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" :icon="Eye" @click="router.push({ name: 'order-detail', params: { orderNo: row.orderNo } })">
              详情
            </el-button>
            <el-dropdown
              trigger="click"
              :disabled="Boolean(operatingOrder)"
              @command="handleManualCommand($event, row)"
            >
              <el-button size="small" :icon="Ellipsis" :loading="operatingOrder.startsWith(`${row.orderNo}:`)">
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
  </article>
</template>

<style scoped>
.panel {
  position: relative;
  padding: 18px;
  overflow: hidden;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

.filter-bar {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 160px 160px 88px 88px;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.035);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.order-summary {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.summary-item {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 13px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.038);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.summary-item span,
.summary-item em {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
  font-style: normal;
}

.summary-item strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 20px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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
  .filter-bar,
  .order-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
