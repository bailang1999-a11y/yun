<script setup lang="ts">
import { formatVerificationStatus, verificationStatusTagType } from '../utils/formatters'

defineProps<{
  status?: string
}>()
</script>

<template>
  <el-tag :type="verificationStatusTagType(status)" effect="plain">
    {{ formatVerificationStatus(status) }}
  </el-tag>
</template>
