<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { Order } from '../types/operations'

const props = withDefaults(defineProps<{
  order: Pick<Order, 'orderNo' | 'requestId' | 'upstreamOrderNo'>
  compact?: boolean
}>(), {
  compact: false
})

const numbers = computed(() => [
  { key: 'source', label: '来源订单号', value: props.order.requestId, empty: '暂未提供' },
  { key: 'upstream', label: '上游订单号', value: props.order.upstreamOrderNo, empty: '暂未获取' },
  { key: 'local', label: '本地订单号', value: props.order.orderNo, empty: '暂未生成' }
])

async function copyNumber(value: string | undefined, label: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}
</script>

<template>
  <div class="order-numbers" :class="{ 'is-compact': compact }">
    <div v-for="item in numbers" :key="item.key" class="number-row">
      <span>{{ item.label }}</span>
      <button
        type="button"
        class="number-value"
        :class="{ empty: !item.value }"
        :disabled="!item.value"
        :title="item.value ? `${item.value} · 点击复制` : item.empty"
        :aria-label="item.value ? `复制${item.label}` : `${item.label}${item.empty}`"
        @click="copyNumber(item.value, item.label)"
      >
        {{ item.value || item.empty }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.order-numbers {
  display: grid;
  gap: 6px;
  width: min(100%, 420px);
}

.number-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.number-row span {
  color: rgba(255, 255, 255, 0.46);
  font-size: var(--order-font-secondary, 11px);
  font-weight: var(--order-weight-secondary, 500);
  line-height: var(--order-line-secondary, 16px);
  white-space: nowrap;
}

.number-value {
  min-width: 0;
  overflow: hidden;
  padding: 2px 4px;
  text-align: left;
  color: rgba(255, 255, 255, 0.9);
  font-size: var(--order-font-primary, 13px);
  font-weight: var(--order-weight-primary, 600);
  line-height: var(--order-line-primary, 19px);
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  transition: color 160ms ease, background 160ms ease;
}

.number-value:hover:not(:disabled) {
  color: #dbeafe;
  background: rgba(59, 130, 246, 0.1);
}

.number-value:focus-visible {
  outline: 2px solid rgba(96, 165, 250, 0.7);
  outline-offset: 1px;
}

.number-value.empty {
  color: rgba(255, 255, 255, 0.34);
  font-weight: var(--order-weight-secondary, 500);
  cursor: not-allowed;
}

.order-numbers.is-compact {
  gap: 2px;
}

.is-compact .number-row {
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 5px;
}

.is-compact .number-row span {
  font-size: var(--order-font-secondary, 11px);
}

.is-compact .number-value {
  font-size: var(--order-font-control, 12px);
}
</style>
