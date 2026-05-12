<script setup lang="ts">
const props = defineProps<{
  modelValue?: string[]
  options: string[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

function toggle(value: string) {
  const current = [...(props.modelValue || [])]
  emit('update:modelValue', current.includes(value) ? current.filter((item) => item !== value) : [...current, value])
}
</script>

<template>
  <div class="benefit-card-grid">
    <button
      v-for="item in options"
      :key="item"
      type="button"
      class="benefit-card"
      :class="{ active: modelValue?.includes(item) }"
      @click="toggle(item)"
    >
      {{ item }}
    </button>
  </div>
</template>

<style scoped>
.benefit-card-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.benefit-card {
  min-width: 70px;
  height: 34px;
  padding: 0 14px;
  border-radius: 10px;
  color: rgba(210, 220, 232, 0.46);
  background: rgba(130, 145, 164, 0.07);
  border: 0.5px solid rgba(178, 190, 208, 0.1);
  cursor: pointer;
  filter: grayscale(0.72) saturate(0.42);
  opacity: 0.74;
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease, opacity 160ms ease, filter 160ms ease;
}

.benefit-card:hover {
  transform: translateY(-1px);
  opacity: 0.9;
  border-color: rgba(210, 224, 240, 0.22);
}

.benefit-card.active {
  color: #eafffb;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.24), rgba(0, 158, 126, 0.16));
  border-color: rgba(0, 255, 195, 0.72);
  box-shadow: 0 0 0 1px rgba(0, 255, 195, 0.18), 0 10px 22px rgba(0, 255, 195, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.14);
  filter: none;
  opacity: 1;
}
</style>
