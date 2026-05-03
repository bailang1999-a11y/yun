<script setup lang="ts">
import { computed } from 'vue'
import type { Order } from '../types/operations'

const props = defineProps<{
  order: Order
}>()

const goodsName = computed(() => {
  if (typeof props.order.goods === 'string') return props.order.goods
  return props.order.goodsName || props.order.goods?.name || '-'
})

const sourceGoods = computed(() => {
  const order = props.order
  if (order.supplierGoodsName || order.supplierGoodsId) {
    return [order.supplierName, order.supplierGoodsName || order.supplierGoodsId].filter(Boolean).join(' · ')
  }

  const successAttempt = order.channelAttempts?.find((attempt) => attempt.status === 'SUCCESS') || order.channelAttempts?.[0]
  if (!successAttempt) return '-'
  return [successAttempt.supplierName, successAttempt.supplierGoodsId].filter(Boolean).join(' · ')
})
</script>

<template>
  <div class="stack-cell">
    <strong>{{ goodsName }}</strong>
    <span>{{ sourceGoods }}</span>
  </div>
</template>

<style scoped>
.stack-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.stack-cell strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.84);
  font-size: 14px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stack-cell span {
  min-width: 0;
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
