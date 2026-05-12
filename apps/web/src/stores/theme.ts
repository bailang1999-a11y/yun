import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'dark'

function systemThemeByTime(): ThemeMode {
  const hour = new Date().getHours()
  return hour >= 7 && hour < 18 ? 'light' : 'dark'
}

function applyTheme(mode: ThemeMode) {
  document.documentElement.dataset.theme = mode
  document.documentElement.style.colorScheme = mode
}

export function applySystemTheme() {
  applyTheme(systemThemeByTime())
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(systemThemeByTime())
  const source = ref<'system' | 'manual'>('system')

  const isDark = computed(() => mode.value === 'dark')
  const label = computed(() => (isDark.value ? '深色' : '浅色'))
  const nextLabel = computed(() => (isDark.value ? '浅色' : '深色'))

  function init() {
    if (source.value === 'system') mode.value = systemThemeByTime()
    applyTheme(mode.value)
  }

  function toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
    source.value = 'manual'
    applyTheme(mode.value)
  }

  function useSystem() {
    source.value = 'system'
    mode.value = systemThemeByTime()
    applyTheme(mode.value)
  }

  return { mode, source, isDark, label, nextLabel, init, toggle, useSystem }
})
