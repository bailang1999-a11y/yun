<script setup lang="ts">
import { Ban, CheckCircle2, CircleAlert, Clock3, LoaderCircle, RotateCcw, UserRoundCog } from 'lucide-vue-next'
import { computed } from 'vue'
import { formatOrderStatus } from '../utils/formatters'

const props = defineProps<{
  status?: string
}>()

const meta = computed(() => {
  const label = formatOrderStatus(props.status)
  if (props.status === 'UNPAID') return { label, icon: Clock3, className: 'processing' }
  if (props.status === 'PROCURING') return { label, icon: LoaderCircle, className: 'processing' }
  if (props.status === 'WAITING_MANUAL') return { label, icon: UserRoundCog, className: 'processing' }
  if (props.status === 'DELIVERED') return { label, icon: CheckCircle2, className: 'success' }
  if (props.status === 'REFUNDED') return { label, icon: RotateCcw, className: 'danger' }
  if (props.status === 'CANCELLED' || props.status === 'CLOSED') return { label, icon: Ban, className: 'danger' }
  return { label, icon: CircleAlert, className: props.status === 'FAILED' ? 'danger' : 'unknown' }
})
</script>

<template>
  <span class="status-card" :class="meta.className">
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
</style>
