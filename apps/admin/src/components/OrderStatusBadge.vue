<script setup lang="ts">
import { Ban, CheckCircle2, CircleAlert, Clock3, LoaderCircle, RotateCcw, ShieldX, UserRoundCog } from 'lucide-vue-next'
import { computed } from 'vue'
import { formatMoney, formatOrderStatus } from '../utils/formatters'

const props = defineProps<{
  status?: string
  rejectionReason?: string
  requestId?: string
  externalMaxAmount?: number | string
  expectedAmount?: number | string
}>()

function hasAmount(value?: number | string) {
  return value !== undefined && value !== null && value !== '' && Number.isFinite(Number(value))
}

function amountLabel(value?: number | string) {
  return hasAmount(value) ? formatMoney(value) : '未提供'
}

const meta = computed(() => {
  const label = formatOrderStatus(props.status)
  if (props.status === 'UNPAID') return { label, icon: Clock3, className: 'processing' }
  if (props.status === 'PROCURING') return { label, icon: LoaderCircle, className: 'processing' }
  if (props.status === 'WAITING_MANUAL') return { label, icon: UserRoundCog, className: 'processing' }
  if (props.status === 'DELIVERED') return { label, icon: CheckCircle2, className: 'success' }
  if (props.status === 'REJECTED') return { label, icon: ShieldX, className: 'danger' }
  if (props.status === 'REFUNDED') return { label, icon: RotateCcw, className: 'danger' }
  if (props.status === 'CANCELLED' || props.status === 'CLOSED') return { label, icon: Ban, className: 'danger' }
  return { label, icon: CircleAlert, className: props.status === 'FAILED' ? 'danger' : 'unknown' }
})
</script>

<template>
  <el-tooltip
    v-if="status === 'REJECTED'"
    placement="top"
    :show-after="120"
    popper-class="xiyiyun-order-tooltip xiyiyun-rejection-popper"
  >
    <template #content>
      <div class="rejection-tooltip" role="status">
        <strong>订单已拒绝</strong>
        <dl>
          <dt>拒绝原因</dt><dd>{{ rejectionReason || '未记录具体原因' }}</dd>
          <dt>外部订单号</dt><dd>{{ requestId || '未提供' }}</dd>
          <dt>实际支付价格</dt><dd>{{ amountLabel(externalMaxAmount) }}</dd>
          <dt>系统要求价格</dt><dd>{{ amountLabel(expectedAmount) }}</dd>
        </dl>
      </div>
    </template>
    <span
      class="status-card danger is-interactive"
      tabindex="0"
      :aria-label="`${meta.label}，${rejectionReason || '未记录具体原因'}`"
    >
      <component :is="meta.icon" :size="14" aria-hidden="true" />
      {{ meta.label }}
    </span>
  </el-tooltip>
  <span v-else class="status-card" :class="meta.className">
    <component :is="meta.icon" :size="14" aria-hidden="true" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.status-card {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 88px;
  height: 28px;
  padding: 0 9px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 9px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
  background: rgba(255, 255, 255, 0.045);
}

.status-card.processing {
  color: #60a5fa;
  border-color: rgba(96, 165, 250, 0.32);
  background: rgba(96, 165, 250, 0.12);
}

.status-card.success {
  color: #4ade80;
  border-color: rgba(74, 222, 128, 0.32);
  background: rgba(74, 222, 128, 0.12);
}

.status-card.danger {
  color: #f87171;
  border-color: rgba(248, 113, 113, 0.32);
  background: rgba(248, 113, 113, 0.12);
}

.status-card.is-interactive {
  cursor: help;
}

.status-card.is-interactive:focus-visible {
  outline: 2px solid rgba(248, 113, 113, 0.58);
  outline-offset: 2px;
}

.rejection-tooltip {
  display: grid;
  gap: 9px;
  width: 278px;
}

.rejection-tooltip strong {
  color: #fecaca;
  font-size: 13px;
  line-height: 19px;
}

.rejection-tooltip dl {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 7px 10px;
  margin: 0;
}

.rejection-tooltip dt,
.rejection-tooltip dd {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
}

.rejection-tooltip dt {
  color: rgba(255, 255, 255, 0.48);
}

.rejection-tooltip dd {
  color: rgba(255, 255, 255, 0.86);
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
</style>
