<script setup lang="ts">
import PlatformLogo from './PlatformLogo.vue'

type PlatformOption = {
  label: string
  value: string
  logo: string
}

const props = defineProps<{
  options: PlatformOption[]
  availablePlatforms?: string[]
  forbiddenPlatforms?: string[]
}>()

const emit = defineEmits<{
  'update:availablePlatforms': [value: string[]]
  'update:forbiddenPlatforms': [value: string[]]
}>()

function allPlatformValues() {
  return props.options.map((item) => item.value)
}

function salePlatformsUnlimited() {
  const selected = new Set(props.availablePlatforms || [])
  return props.options.length > 0 && props.options.every((item) => selected.has(item.value))
}

function forbiddenPlatformsUnlimited() {
  return !(props.forbiddenPlatforms || []).length
}

function selectSaleUnlimited() {
  emit('update:availablePlatforms', allPlatformValues())
}

function toggleSalePlatform(value: string) {
  const current = salePlatformsUnlimited() ? [] : [...(props.availablePlatforms || [])]
  emit(
    'update:availablePlatforms',
    current.includes(value) ? current.filter((item) => item !== value) : [...current, value]
  )
}

function toggleForbiddenPlatform(value: string) {
  const current = [...(props.forbiddenPlatforms || [])]
  emit(
    'update:forbiddenPlatforms',
    current.includes(value) ? current.filter((item) => item !== value) : [...current, value]
  )
}
</script>

<template>
  <div class="platform-inline-grid">
    <el-form-item label="可售平台">
      <div class="platform-card-grid">
        <button
          type="button"
          class="platform-card sale unlimited"
          :class="{ active: salePlatformsUnlimited() }"
          @click="selectSaleUnlimited"
        >
          <PlatformLogo name="unlimited" />
          <strong>无限制</strong>
        </button>
        <button
          v-for="item in options"
          :key="item.value"
          type="button"
          class="platform-card sale"
          :class="{ active: !salePlatformsUnlimited() && availablePlatforms?.includes(item.value) }"
          @click="toggleSalePlatform(item.value)"
        >
          <PlatformLogo :name="item.logo" />
          <strong>{{ item.label }}</strong>
        </button>
      </div>
    </el-form-item>
    <el-form-item label="不支持平台说明">
      <div class="platform-card-grid">
        <button
          v-for="item in options"
          :key="item.value"
          type="button"
          class="platform-card deny"
          :class="{ active: !forbiddenPlatformsUnlimited() && forbiddenPlatforms?.includes(item.value) }"
          @click="toggleForbiddenPlatform(item.value)"
        >
          <PlatformLogo :name="item.logo" />
          <strong>{{ item.label }}</strong>
        </button>
      </div>
    </el-form-item>
  </div>
</template>

<style scoped>
.platform-inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.platform-inline-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.platform-card-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.platform-card {
  min-width: 86px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-radius: 10px;
  color: rgba(210, 220, 232, 0.46);
  background: rgba(130, 145, 164, 0.07);
  border: 0.5px solid rgba(178, 190, 208, 0.1);
  cursor: pointer;
  filter: grayscale(0.72) saturate(0.42);
  opacity: 0.74;
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease, opacity 160ms ease, filter 160ms ease;
}

.platform-card strong {
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
}

.platform-card:hover {
  transform: translateY(-1px);
  opacity: 0.9;
  border-color: rgba(210, 224, 240, 0.22);
}

.platform-card.sale.active {
  color: #eafffb;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.24), rgba(0, 158, 126, 0.16));
  border-color: rgba(0, 255, 195, 0.72);
  box-shadow: 0 0 0 1px rgba(0, 255, 195, 0.18), 0 10px 22px rgba(0, 255, 195, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.14);
  filter: none;
  opacity: 1;
}

.platform-card.deny.active {
  color: #fff0f0;
  background: linear-gradient(135deg, rgba(255, 66, 84, 0.24), rgba(170, 25, 42, 0.16));
  border-color: rgba(255, 83, 99, 0.76);
  box-shadow: 0 0 0 1px rgba(255, 83, 99, 0.18), 0 10px 22px rgba(255, 66, 84, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  filter: none;
  opacity: 1;
}

.platform-card.active :deep(.platform-logo) {
  transform: scale(1.04);
}

.platform-card.sale.active :deep(.platform-logo) {
  box-shadow: 0 0 0 2px rgba(0, 255, 195, 0.18);
}

.platform-card.deny.active :deep(.platform-logo) {
  color: #fff6f6;
  background: linear-gradient(135deg, #ff5a66, #b11226);
  box-shadow: 0 0 0 2px rgba(255, 83, 99, 0.18);
}

.platform-card.unlimited {
  min-width: 100px;
}

@media (max-width: 760px) {
  .platform-inline-grid {
    grid-template-columns: 1fr;
  }
}
</style>
