<script setup lang="ts">
import { computed } from 'vue'
import type { Order } from '../types/operations'
import { formatDurationFromOrder } from '../utils/formatters'

const props = defineProps<{
  order: Order
  now?: number
}>()

const duration = computed(() => {
  props.now
  const terminalStatuses = ['DELIVERED', 'FAILED', 'REFUNDED', 'CANCELLED', 'CLOSED']
  const endAt = terminalStatuses.includes(props.order.status) ? props.order.deliveredAt : undefined
  return formatDurationFromOrder(props.order.createdAt, endAt)
})
</script>

<template>
  <span>{{ duration }}</span>
</template>
