<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { subscribeOrderEvents } from '../api/realtime'
import { useDashboardStore } from '../stores/dashboard'

const dashboard = useDashboardStore()
const liveFlash = ref(false)
let unsubscribeRealtime: (() => void) | undefined
let flashTimer: number | undefined

onMounted(() => {
  void dashboard.loadDashboard()
  unsubscribeRealtime = subscribeOrderEvents((event) => {
    if (event.type !== 'ORDER_UPDATED') return
    liveFlash.value = true
    if (flashTimer) window.clearTimeout(flashTimer)
    flashTimer = window.setTimeout(() => {
      liveFlash.value = false
    }, 1400)
    void dashboard.loadDashboard({ silent: true })
  })
})

onBeforeUnmount(() => {
  unsubscribeRealtime?.()
  if (flashTimer) window.clearTimeout(flashTimer)
})
</script>

<template>
  <div class="metric-grid">
    <article v-for="metric in dashboard.metrics" :key="metric.label" class="metric-card">
      <span>{{ metric.label }}</span>
      <strong>{{ metric.value }}</strong>
      <em :data-tone="metric.tone">{{ metric.trend }}</em>
    </article>
  </div>

  <section class="panel-grid">
    <article class="panel">
      <div class="panel-head">
        <h2>最近订单动态</h2>
        <div class="head-actions">
          <span class="live-badge" :class="{ flash: liveFlash }" role="status" aria-live="polite">
            <i :class="{ pulse: dashboard.syncing || liveFlash }" />
            {{ dashboard.syncing ? '实时更新中' : dashboard.lastSyncedAt ? `已同步 ${dashboard.lastSyncedAt}` : '实时监听中' }}
          </span>
          <el-button :icon="RefreshCw" :loading="dashboard.loading || dashboard.syncing" @click="() => dashboard.loadDashboard()">刷新</el-button>
        </div>
      </div>
      <el-table v-loading="dashboard.loading" :data="dashboard.recentOrders" style="width: 100%">
        <el-table-column prop="orderNo" label="订单编号" width="180" />
        <el-table-column prop="goods" label="商品" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="amount" label="金额" width="120" />
      </el-table>
    </article>

    <article class="panel">
      <div class="panel-head">
        <h2>上游监控</h2>
        <span>余额 / 状态</span>
      </div>
      <div class="feed">
        <p v-for="item in dashboard.supplierFeeds" :key="item.id" :data-tone="item.tone">
          <b>{{ item.name }}</b> {{ item.message }}
        </p>
        <p v-if="!dashboard.supplierFeeds.length"><b>暂无供应商</b> 请先在供应商管理中新增上游。</p>
      </div>
    </article>
  </section>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  position: relative;
  padding: 18px;
  overflow: hidden;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.metric-card::before {
  content: "";
  position: absolute;
  right: 16px;
  top: 16px;
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: #00ffc3;
  box-shadow: 0 0 20px rgba(0, 255, 195, 0.75);
}

.metric-card:has(em[data-tone="warn"])::before {
  background: #ffab00;
  box-shadow: 0 0 20px rgba(255, 171, 0, 0.75);
}

.metric-card span,
.metric-card em {
  color: rgba(255, 255, 255, 0.52);
  font-style: normal;
}

.metric-card strong {
  display: block;
  margin: 12px 0 8px;
  color: rgba(255, 255, 255, 0.92);
  font-size: 28px;
  text-shadow: 0 0 30px rgba(0, 255, 195, 0.12);
}

.panel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 0.8fr);
  gap: 14px;
  margin-top: 14px;
}

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
  transition: border-color 180ms ease, box-shadow 180ms ease, color 180ms ease;
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

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.45);
}

.feed {
  display: grid;
  gap: 10px;
}

.feed p {
  margin: 0;
  padding: 12px;
  line-height: 1.55;
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
}

.feed p[data-tone="warn"] {
  border-color: rgba(255, 171, 0, 0.2);
  box-shadow: 0 0 24px rgba(255, 171, 0, 0.08);
}

.feed b {
  color: #00ffc3;
}

@keyframes live-pulse {
  50% {
    transform: scale(1.45);
    opacity: 0.45;
  }
}
</style>
