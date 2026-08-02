<script setup lang="ts">
import { computed } from 'vue'
import type { Order } from '../types/operations'

const props = defineProps<{
  order: Order
}>()

const buyerName = computed(() => props.order.buyerNickname || (props.order.userId ? `用户 ${props.order.userId}` : props.order.buyerAccount || '-'))
const buyerContact = computed(() => props.order.buyerContact || props.order.buyerMobile || props.order.buyerEmail || props.order.buyerAccount || '-')
</script>

<template>
  <div class="stack-cell">
    <strong>{{ buyerName }}</strong>
    <span>{{ buyerContact }}</span>
  </div>
</template>

<style scoped>
.stack-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.stack-cell strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.84);
  font-size: var(--order-row-primary-size, 13px);
  font-weight: var(--order-row-primary-weight, 600);
  line-height: var(--order-row-primary-line, 18px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stack-cell span {
  min-width: 0;
  color: rgba(255, 255, 255, 0.45);
  font-size: var(--order-row-secondary-size, 12px);
  font-weight: var(--order-row-secondary-weight, 500);
  line-height: var(--order-row-secondary-line, 17px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
