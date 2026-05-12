<script setup lang="ts">
import { BadgeCent, Banknote, MessageCircle, WalletCards } from 'lucide-vue-next'
import { computed } from 'vue'
import { formatPaymentMethod } from '../utils/formatters'

const props = defineProps<{
  value?: string
}>()

const meta = computed(() => {
  const key = String(props.value || '').toLowerCase()
  if (key.includes('wechat') || key.includes('微信')) return { label: '微信', icon: MessageCircle, className: 'wechat' }
  if (key.includes('alipay') || key.includes('支付宝')) return { label: '支付宝', icon: BadgeCent, className: 'alipay' }
  if (key.includes('balance') || key.includes('余额')) return { label: '余额', icon: WalletCards, className: 'balance' }
  if (key.includes('mock') || key.includes('模拟')) return { label: '模拟', icon: Banknote, className: 'mock' }
  return { label: formatPaymentMethod(props.value), icon: Banknote, className: 'unknown' }
})
</script>

<template>
  <span class="method-pill" :class="meta.className">
    <component :is="meta.icon" :size="14" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.method-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 9px;
  border-radius: 9px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.045);
}

.method-pill.wechat {
  color: #bdf8dc;
  border-color: rgba(34, 197, 94, 0.22);
  background: rgba(34, 197, 94, 0.1);
}

.method-pill.alipay {
  color: #c9e6ff;
  border-color: rgba(59, 130, 246, 0.25);
  background: rgba(59, 130, 246, 0.12);
}

.method-pill.balance {
  color: #d9f99d;
  border-color: rgba(132, 204, 22, 0.24);
  background: rgba(132, 204, 22, 0.1);
}
</style>
