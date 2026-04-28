<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Eye, RefreshCw } from 'lucide-vue-next'
import { exportOrdersExcel, fetchOrders } from '../api/admin'
import { subscribeOrderEvents } from '../api/realtime'
import type { Order } from '../types/operations'

const router = useRouter()
const orders = ref<Order[]>([])
const loading = ref(false)
const syncing = ref(false)
const exporting = ref(false)
const lastSyncedAt = ref('')
let refreshTimer: number | undefined
let unsubscribeRealtime: (() => void) | undefined
const filters = reactive({
  search: '',
  status: '',
  goodsType: ''
})

const statusOptions = [
  { label: '待支付', value: 'UNPAID' },
  { label: '采购中', value: 'PROCURING' },
  { label: '待人工', value: 'WAITING_MANUAL' },
  { label: '已发货', value: 'DELIVERED' },
  { label: '失败', value: 'FAILED' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '已取消', value: 'CANCELLED' }
]

const statusLabel = Object.fromEntries(statusOptions.map((item) => [item.value, item.label]))
const statusType: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  UNPAID: 'warning',
  PROCURING: 'primary',
  WAITING_MANUAL: 'warning',
  DELIVERED: 'success',
  FAILED: 'danger',
  REFUNDED: 'info',
  CANCELLED: 'info'
}

function goodsName(row: Order) {
  if (typeof row.goods === 'string') {
    return row.goods
  }

  return row.goodsName || row.goods?.name || '-'
}

function formatMoney(value: Order['amount']) {
  const numberValue = Number(value)

  if (Number.isNaN(numberValue)) {
    return String(value ?? '-')
  }

  return `¥${numberValue.toFixed(2)}`
}

function formatStatus(status: string) {
  return statusLabel[status] ?? status
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

    <el-table v-loading="loading" :data="orders" height="700" style="width: 100%">
      <el-table-column prop="orderNo" label="订单号" min-width="210" show-overflow-tooltip />
      <el-table-column label="商品" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ goodsName(row) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="130">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusType[row.status] || 'info'" effect="dark">{{ formatStatus(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deliveryType" label="发货类型" width="140" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :icon="Eye" @click="router.push({ name: 'order-detail', params: { orderNo: row.orderNo } })">
            详情
          </el-button>
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

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
