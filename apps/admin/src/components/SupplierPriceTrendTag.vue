<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import type { ECharts } from 'echarts/core'
import { ChartNoAxesCombined, Minus, TrendingDown, TrendingUp } from 'lucide-vue-next'
import type { SupplierPriceTrend, SupplierPriceTrendPoint } from '../types/operations'

const props = defineProps<{
  trend?: SupplierPriceTrend
}>()

const chartElement = ref<HTMLDivElement>()
let chart: ECharts | undefined
let chartRuntimePromise: Promise<typeof import('../lib/priceTrendChartRuntime')> | undefined

function loadChartRuntime() {
  chartRuntimePromise ||= import('../lib/priceTrendChartRuntime')
  return chartRuntimePromise
}

function formatPrice(value?: number) {
  return Number.isFinite(value) ? `¥${Number(value).toFixed(2)}` : '--'
}

function formatTime(value?: string) {
  const date = new Date(value || '')
  if (Number.isNaN(date.getTime())) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date).replaceAll('/', '-')
}

function signedChange(point: SupplierPriceTrendPoint) {
  const value = Number(point.changeAmount || 0)
  if (point.direction === 'UP') return `+¥${Math.abs(value).toFixed(2)}`
  if (point.direction === 'DOWN') return `-¥${Math.abs(value).toFixed(2)}`
  return '±¥0.00'
}

function pointColor(point: SupplierPriceTrendPoint) {
  if (point.direction === 'UP') return '#86efac'
  if (point.direction === 'DOWN') return '#fda4af'
  return '#a5f3fc'
}

function chartTooltip(params: unknown) {
  const first = Array.isArray(params) ? params[0] as { dataIndex?: number } : undefined
  const point = props.trend?.points[first?.dataIndex ?? -1]
  if (!point) return ''
  return [
    `<strong>${formatTime(point.observedAt)}</strong>`,
    `采购价 ${formatPrice(point.unitPrice)}`,
    `变化 ${signedChange(point)}`
  ].join('<br>')
}

async function renderChart() {
  const [chartRuntime] = await Promise.all([loadChartRuntime(), nextTick()])
  if (!chartElement.value || !props.trend?.points.length) return
  chart ||= chartRuntime.initPriceTrendChart(chartElement.value)
  const points = props.trend.points
  chart.setOption({
    animationDuration: 180,
    grid: { left: 18, right: 18, top: 34, bottom: 42 },
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(7, 18, 31, 0.98)',
      borderColor: 'rgba(103, 232, 249, 0.24)',
      textStyle: { color: 'rgba(236, 254, 255, 0.9)', fontSize: 11 },
      formatter: chartTooltip
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((point) => formatTime(point.observedAt)),
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } },
      axisTick: { show: false },
      axisLabel: {
        color: 'rgba(203, 213, 225, 0.58)',
        fontSize: 9,
        interval: 0,
        rotate: points.length > 6 ? 28 : 0
      }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.1)' } },
      axisLabel: { show: false }
    },
    series: [{
      type: 'line',
      smooth: 0.22,
      symbol: 'circle',
      symbolSize: 7,
      lineStyle: { color: '#67e8f9', width: 2 },
      data: points.map((point) => ({
        value: point.unitPrice,
        itemStyle: { color: pointColor(point), borderColor: '#0b1d2c', borderWidth: 1 },
        label: {
          show: true,
          position: point.direction === 'DOWN' ? 'bottom' : 'top',
          distance: 7,
          color: pointColor(point),
          fontSize: 9,
          formatter: formatPrice(point.unitPrice)
        }
      }))
    }]
  }, true)
  chart.resize()
}

onBeforeUnmount(() => {
  chart?.dispose()
})
</script>

<template>
  <el-popover
    v-if="trend?.points.length"
    trigger="hover"
    placement="top"
    :width="360"
    :show-after="120"
    :hide-after="80"
    popper-class="xiyiyun-price-trend-popper"
    @show="renderChart"
  >
    <template #reference>
      <span
        class="price-trend-pill"
        :class="`is-${trend.latestDirection.toLowerCase()}`"
        :aria-label="`最新上游成本价 ${formatPrice(trend.latestUnitPrice)}`"
      >
        <ChartNoAxesCombined :size="13" aria-hidden="true" />
        <strong>{{ formatPrice(trend.latestUnitPrice) }}</strong>
        <span class="trend-corner" aria-hidden="true">
          <TrendingUp v-if="trend.latestDirection === 'UP'" :size="9" />
          <TrendingDown v-else-if="trend.latestDirection === 'DOWN'" :size="9" />
          <Minus v-else :size="9" />
        </span>
      </span>
    </template>

    <section class="trend-popover-content">
      <header>
        <div>
          <strong>上游成本价</strong>
          <span>{{ trend.supplierName }}</span>
        </div>
        <small>近 {{ trend.points.length }} 次变价</small>
      </header>
      <div
        ref="chartElement"
        class="trend-chart"
        role="img"
        :aria-label="`${trend.supplierName}近${trend.points.length}次采购价格趋势`"
      />
    </section>
  </el-popover>

  <span v-else class="price-trend-pill empty" aria-label="暂无上游采购价趋势">
    <ChartNoAxesCombined :size="13" aria-hidden="true" />
    <strong>--</strong>
  </span>
</template>

<style scoped>
.price-trend-pill {
  position: relative;
  min-width: 78px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0 9px;
  border: 0.5px solid rgba(34, 211, 238, 0.22);
  border-radius: 9px;
  color: rgba(207, 250, 254, 0.88);
  background: rgba(6, 182, 212, 0.1);
  font-size: var(--order-font-control, 12px);
  font-weight: var(--order-weight-control, 600);
  line-height: var(--order-line-control, 18px);
  cursor: default;
}

.price-trend-pill.is-up {
  color: #bbf7d0;
  border-color: rgba(34, 197, 94, 0.24);
  background: rgba(34, 197, 94, 0.1);
}

.price-trend-pill.is-down {
  color: #fecdd3;
  border-color: rgba(244, 63, 94, 0.24);
  background: rgba(244, 63, 94, 0.1);
}

.price-trend-pill.empty {
  color: rgba(255, 255, 255, 0.34);
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
}

.price-trend-pill strong {
  color: currentColor;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.trend-corner {
  position: absolute;
  top: -4px;
  right: -4px;
  width: 13px;
  height: 13px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(7, 18, 31, 0.94);
  border-radius: 50%;
  color: currentColor;
  background: rgba(12, 31, 48, 0.98);
}

.trend-popover-content {
  width: 100%;
}

.trend-popover-content header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 8px;
}

.trend-popover-content header div {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.trend-popover-content header strong {
  color: rgba(236, 254, 255, 0.92);
  font-size: 13px;
}

.trend-popover-content header span,
.trend-popover-content header small {
  overflow: hidden;
  color: rgba(203, 213, 225, 0.56);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trend-chart {
  width: 100%;
  height: 198px;
}
</style>
