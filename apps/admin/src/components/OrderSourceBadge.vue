<script setup lang="ts">
import { MonitorSmartphone, Webhook } from 'lucide-vue-next'
import { computed } from 'vue'
import { formatOrderSource } from '../utils/formatters'

const props = defineProps<{
  source?: string
  requestId?: string
  platform?: string
}>()

const meta = computed(() => {
  const label = formatOrderSource(props.source, Boolean(props.requestId), props.platform)
  if (label.includes('API')) return { label, icon: Webhook, className: 'api' }
  if (label.includes('前台')) return { label, icon: MonitorSmartphone, className: 'front' }
  return { label, icon: MonitorSmartphone, className: 'unknown' }
})
</script>

<template>
  <span class="source-card" :class="meta.className">
    <component :is="meta.icon" :size="14" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.source-card {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 88px;
  height: 28px;
  padding: 0 9px;
  border-radius: 9px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  white-space: nowrap;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.045);
}

.source-card.api {
  color: #c4b5fd;
  border-color: rgba(167, 139, 250, 0.24);
  background: rgba(124, 58, 237, 0.12);
}

.source-card.front {
  color: #a7f3d0;
  border-color: rgba(20, 184, 166, 0.24);
  background: rgba(20, 184, 166, 0.1);
}
</style>
