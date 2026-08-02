<script setup lang="ts">
import { ElMessage } from 'element-plus'

const props = defineProps<{
  value?: string
}>()

async function copyAccount() {
  const value = props.value?.trim()
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('充值账号已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}
</script>

<template>
  <button
    type="button"
    class="account-tag"
    :class="{ empty: !value?.trim() }"
    :disabled="!value?.trim()"
    :title="value?.trim() ? `${value.trim()} · 点击复制` : '未提供充值账号'"
    :aria-label="value?.trim() ? `复制充值账号 ${value.trim()}` : '未提供充值账号'"
    @click="copyAccount"
  >
    <span>{{ value?.trim() || '未提供' }}</span>
  </button>
</template>

<style scoped>
.account-tag {
  width: auto;
  max-width: 160px;
  min-width: 0;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 10px;
  color: rgba(207, 250, 254, 0.86);
  font-size: var(--order-font-control, 12px);
  font-weight: var(--order-weight-control, 600);
  line-height: var(--order-line-control, 18px);
  letter-spacing: 0;
  border: 0.5px solid rgba(34, 211, 238, 0.22);
  border-radius: 8px;
  background: rgba(6, 182, 212, 0.1);
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease, background 160ms ease;
}

.account-tag span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-tag:hover:not(:disabled) {
  color: rgba(236, 254, 255, 0.96);
  border-color: rgba(103, 232, 249, 0.38);
  background: rgba(6, 182, 212, 0.16);
}

.account-tag:focus-visible {
  outline: 2px solid rgba(34, 211, 238, 0.64);
  outline-offset: 2px;
}

.account-tag.empty {
  color: rgba(255, 255, 255, 0.34);
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
  cursor: default;
}
</style>
