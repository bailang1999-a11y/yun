<template>
  <div class="web-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/">
        <span class="brand-mark">喜</span>
        <span>
          <strong>喜易云</strong>
          <small>用户商城</small>
        </span>
      </RouterLink>

      <nav class="topnav" aria-label="主导航">
        <RouterLink to="/">商品</RouterLink>
        <RouterLink to="/orders">订单</RouterLink>
        <RouterLink to="/account/api">API</RouterLink>
        <RouterLink to="/account">会员中心</RouterLink>
      </nav>

      <div class="top-actions">
        <button class="theme-toggle" type="button" :aria-label="`切换到${theme.nextLabel}模式`" @click="theme.toggle()">
          <span aria-hidden="true">{{ theme.isDark ? '夜' : '昼' }}</span>
          <strong>{{ theme.label }}</strong>
        </button>
        <template v-if="session.isLoggedIn">
          <RouterLink class="balance-pill" to="/account">
            余额 ¥{{ money(session.profile?.balance || 0) }}
          </RouterLink>
          <button class="ghost-button" type="button" @click="session.logout()">退出</button>
        </template>
        <RouterLink v-else class="primary-button" to="/login">登录</RouterLink>
      </div>
    </header>

    <main class="page-main">
      <slot />
    </main>

    <footer v-if="footerItems.length" class="site-footer">
      <span v-if="settings.companyName">{{ settings.companyName }}</span>
      <span v-if="settings.policeRecordNo" class="police-record">
        <i aria-hidden="true">安</i>
        {{ settings.policeRecordNo }}
      </span>
      <span v-if="settings.icpRecordNo">{{ settings.icpRecordNo }}</span>
      <span v-if="settings.disclaimer">{{ settings.disclaimer }}</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchSiteSettings } from '../api/web'
import { useSessionStore } from '../stores/session'
import { useThemeStore } from '../stores/theme'
import type { SiteSettings } from '../types/web'

const session = useSessionStore()
const theme = useThemeStore()
const settings = reactive<SiteSettings>({
  siteName: '喜易云',
  companyName: '',
  icpRecordNo: '',
  policeRecordNo: '',
  disclaimer: ''
})
session.restore()
theme.init()

const footerItems = computed(() =>
  [settings.companyName, settings.icpRecordNo, settings.policeRecordNo, settings.disclaimer].filter((item) => item && item.trim())
)

onMounted(async () => {
  try {
    Object.assign(settings, await fetchSiteSettings())
  } catch {
    // Public settings are decorative; keep the store usable if the endpoint is unavailable.
  }
})

function money(value: number) {
  return value.toFixed(2)
}
</script>
