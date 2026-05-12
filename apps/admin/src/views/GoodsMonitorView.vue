<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Activity, RefreshCw, RotateCw, ScanLine } from 'lucide-vue-next'
import { fetchProductMonitorOverview, scanProductMonitor, scanProductMonitorChannel } from '../api/productMonitor'
import { subscribeOrderEvents } from '../api/realtime'
import type { ProductMonitorItem, ProductMonitorLog } from '../types/operations'
import { formatDateTime } from '../utils/formatters'

const items = ref<ProductMonitorItem[]>([])
const logs = ref<ProductMonitorLog[]>([])
const loading = ref(false)
const scanning = ref(false)
const lastSyncedAt = ref('')
const nowTick = ref(Date.now())
let unsubscribeRealtime: (() => void) | undefined
let ticker: number | undefined

const failedLogs = computed(() => logs.value.filter((item) => item.result === 'FAILED'))
const activeItems = computed(() => items.value.filter((item) => item.status !== 'FAILED'))

onMounted(() => {
  void loadOverview()
  ticker = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type !== 'PRODUCT_MONITOR_LOG' || !event.monitorLog) return
    upsertLog(event.monitorLog)
    void loadOverview({ silent: true })
  })
})

onBeforeUnmount(() => {
  unsubscribeRealtime?.()
  if (ticker) window.clearInterval(ticker)
})

async function loadOverview(options: { silent?: boolean } = {}) {
  if (!options.silent) loading.value = true
  try {
    const overview = await fetchProductMonitorOverview()
    items.value = overview.items
    logs.value = overview.logs
    lastSyncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch {
    if (!options.silent) ElMessage.error('商品监控数据加载失败')
  } finally {
    loading.value = false
  }
}

async function scanAllNow() {
  scanning.value = true
  try {
    await scanProductMonitor()
    ElMessage.success('已触发全量扫描')
    await loadOverview()
  } catch {
    ElMessage.error('全量扫描失败')
  } finally {
    scanning.value = false
  }
}

async function scanOneNow(item: ProductMonitorItem) {
  scanning.value = true
  try {
    await scanProductMonitorChannel(item.channelId)
    ElMessage.success('已触发渠道扫描')
    await loadOverview()
  } catch {
    ElMessage.error('渠道扫描失败')
  } finally {
    scanning.value = false
  }
}

function upsertLog(log: ProductMonitorLog) {
  logs.value = [log, ...logs.value.filter((item) => String(item.id) !== String(log.id))].slice(0, 200)
}

function formatTime(value?: string) {
  return formatDateTime(value)
}

function countdown(value?: string) {
  if (!value) return '-'
  const target = new Date(value).getTime()
  if (Number.isNaN(target)) return '-'
  const seconds = Math.max(0, Math.ceil((target - nowTick.value) / 1000))
  return seconds <= 0 ? '即将扫描' : `${seconds}s`
}

function resultLabel(result: string) {
  if (result === 'CHANGED') return '有变动'
  if (result === 'FAILED') return '失败'
  if (result === 'SCANNING') return '扫描中'
  if (result === 'WAITING') return '等待'
  return '无变动'
}

function resultTone(result: string) {
  if (result === 'CHANGED') return 'success'
  if (result === 'FAILED') return 'danger'
  if (result === 'SCANNING') return 'warning'
  return 'info'
}
</script>

<template>
  <section class="goods-monitor-shell">
    <div class="monitor-head liquid-admin-panel">
      <div>
        <span>Goods Monitor</span>
        <h2>商品监控</h2>
      </div>
      <div class="head-actions">
        <span class="live-badge" role="status" aria-live="polite">
          <i />
          {{ lastSyncedAt ? `已同步 ${lastSyncedAt}` : '实时监听中' }}
        </span>
        <el-button :icon="RotateCw" :loading="scanning" @click="scanAllNow">立即扫描全部</el-button>
        <el-button :icon="RefreshCw" :loading="loading" @click="() => loadOverview()">刷新</el-button>
      </div>
    </div>

    <div class="metric-grid">
      <article class="metric-card">
        <ScanLine :size="18" />
        <span>监控渠道</span>
        <strong>{{ items.length }}</strong>
      </article>
      <article class="metric-card">
        <Activity :size="18" />
        <span>运行中</span>
        <strong>{{ activeItems.length }}</strong>
      </article>
      <article class="metric-card" :data-warn="failedLogs.length > 0">
        <RefreshCw :size="18" />
        <span>近日日志</span>
        <strong>{{ logs.length }}</strong>
      </article>
    </div>

    <section class="panel-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>已绑定货源扫描</h2>
          <span>只扫描本地商品绑定的上游商品ID，每个渠道结束后 60s 再继续</span>
        </div>
        <el-table v-loading="loading" :data="items" height="470" style="width: 100%">
          <el-table-column prop="goodsName" label="本地商品" min-width="180" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" width="150" show-overflow-tooltip />
          <el-table-column prop="supplierGoodsId" label="上游商品ID" min-width="150" show-overflow-tooltip />
          <el-table-column label="同步策略" width="110">
            <template #default="{ row }">
              <el-tag :type="row.primaryChannel ? 'success' : 'info'" effect="plain">
                {{ row.primaryChannel ? '主渠道' : '仅记录' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近结果" width="120">
            <template #default="{ row }">
              <el-tag :type="resultTone(row.lastResult)" effect="plain">{{ resultLabel(row.lastResult) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扫描次数" width="100">
            <template #default="{ row }">{{ row.scanCount }}</template>
          </el-table-column>
          <el-table-column label="变动次数" width="100">
            <template #default="{ row }">{{ row.changeCount }}</template>
          </el-table-column>
          <el-table-column label="下次扫描" width="130">
            <template #default="{ row }">{{ countdown(row.nextScanAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :loading="scanning" @click="scanOneNow(row)">立即扫描</el-button>
            </template>
          </el-table-column>
        </el-table>
        <section v-if="!items.length && !loading" class="empty">
          暂无绑定货源。请先在商品中绑定上游渠道；未绑定货源的商品视为本地货源，不进入监控。
        </section>
      </article>

      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>实时监控日志</h2>
          <span>有无变动都会记录</span>
        </div>
        <div class="log-feed">
          <article v-for="log in logs" :key="log.id" :data-result="log.result">
            <div>
              <strong>{{ resultLabel(log.result) }}</strong>
              <span>{{ formatTime(log.scannedAt) }}</span>
            </div>
            <p>{{ log.goodsName }} · {{ log.supplierName }} · {{ log.supplierGoodsId }}</p>
            <small>{{ log.message }}</small>
            <ul v-if="log.changes.length">
              <li v-for="change in log.changes" :key="change">{{ change }}</li>
            </ul>
          </article>
          <section v-if="!logs.length" class="empty">暂无监控日志，后台 worker 会自动写入扫描结果。</section>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.goods-monitor-shell {
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
.log-feed article div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.monitor-head span,
.panel-head span,
.metric-card span,
.log-feed span,
.log-feed small {
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

.live-badge i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #00ffc3;
  box-shadow: 0 0 14px rgba(0, 255, 195, 0.5);
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
  grid-template-columns: minmax(0, 1.25fr) minmax(420px, 0.85fr);
  gap: 14px;
}

.log-feed {
  display: grid;
  gap: 10px;
  max-height: 560px;
  overflow: auto;
  padding-right: 4px;
}

.log-feed article,
.empty {
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.log-feed article[data-result="CHANGED"] {
  border-color: rgba(0, 255, 195, 0.22);
  box-shadow: 0 0 22px rgba(0, 255, 195, 0.08);
}

.log-feed article[data-result="FAILED"] {
  border-color: rgba(255, 171, 0, 0.24);
  box-shadow: 0 0 22px rgba(255, 171, 0, 0.08);
}

.log-feed strong {
  color: rgba(255, 255, 255, 0.88);
}

.log-feed p {
  margin: 8px 0 4px;
  color: rgba(255, 255, 255, 0.68);
  overflow-wrap: anywhere;
}

.log-feed ul {
  margin: 8px 0 0;
  padding-left: 18px;
  color: rgba(0, 255, 195, 0.86);
  font-size: 12px;
}

.empty {
  color: rgba(255, 255, 255, 0.56);
}
</style>
