<script setup lang="ts">
import { computed } from 'vue'
import type { Order } from '../types/operations'

const props = defineProps<{
  order: Order
}>()

const duration = computed(() => {
  const start = props.order.createdAt ? new Date(props.order.createdAt).getTime() : NaN
  const endValue = props.order.deliveredAt || props.order.paidAt
  const end = endValue ? new Date(endValue).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '-'

  const seconds = Math.floor((end - start) / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const restSeconds = seconds % 60
  if (minutes < 60) return `${minutes}m ${restSeconds}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
})
</script>

<template>
  <span>{{ duration }}</span>
</template>
