<script setup lang="ts">
import { computed } from 'vue'
import { formatRealNameType, formatVerificationStatus } from '../utils/formatters'

const props = defineProps<{
  realNameType?: string
  realName?: string
  subjectName?: string
  verificationStatus?: string
}>()

const displayName = computed(() => {
  if (props.realNameType === 'SUBJECT') return props.subjectName || props.realName || '-'
  return props.realName || '-'
})
</script>

<template>
  <div class="identity-cell">
    <strong>{{ displayName }}</strong>
    <span>{{ formatRealNameType(realNameType) }} · {{ formatVerificationStatus(verificationStatus) }}</span>
  </div>
</template>

<style scoped>
.identity-cell {
  display: grid;
  gap: 3px;
}

.identity-cell strong {
  color: rgba(255, 255, 255, 0.84);
  font-weight: 650;
}

.identity-cell span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}
</style>
