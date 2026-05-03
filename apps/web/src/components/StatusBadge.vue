<template>
  <span class="status-badge" :class="tone">{{ label }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status?: string }>()

const labelMap: Record<string, string> = {
  CREATED: '待支付',
  PENDING_PAY: '待支付',
  PAID: '已支付',
  DELIVERED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  FAILED: '失败',
  REFUNDED: '已退款'
}

const normalized = computed(() => (props.status || 'UNKNOWN').toUpperCase())
const label = computed(() => labelMap[normalized.value] || props.status || '未知')
const tone = computed(() => {
  if (['DELIVERED', 'COMPLETED', 'PAID'].includes(normalized.value)) return 'success'
  if (['CREATED', 'PENDING_PAY'].includes(normalized.value)) return 'warning'
  if (['FAILED', 'CANCELLED', 'REFUNDED'].includes(normalized.value)) return 'danger'
  return 'neutral'
})
</script>
