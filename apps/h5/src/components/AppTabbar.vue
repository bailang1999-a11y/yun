<script setup lang="ts">
import { ClipboardList, House, Ticket, User } from 'lucide-vue-next'
</script>

<template>
  <nav class="tabbar" aria-label="主导航">
    <RouterLink to="/"><House :size="20" />首页</RouterLink>
    <RouterLink to="/orders"><ClipboardList :size="20" />订单</RouterLink>
    <RouterLink to="/cards"><Ticket :size="20" />卡密</RouterLink>
    <RouterLink to="/mine"><User :size="20" />我的</RouterLink>
  </nav>
</template>

<style scoped>
.tabbar {
  position: fixed;
  left: 50%;
  bottom: calc(12px + env(safe-area-inset-bottom, 0px));
  width: min(
    calc(100% - (var(--h5-edge-gutter) * 2)),
    calc(var(--h5-max-width) - (var(--h5-edge-gutter) * 2))
  );
  height: var(--h5-tabbar-height);
  transform: translateX(-50%);
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  z-index: 40;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 24px;
  box-shadow: 0 18px 54px rgba(0, 0, 0, 0.36), inset 0 1px 0 rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(34px) saturate(180%);
  -webkit-backdrop-filter: blur(34px) saturate(180%);
}

.tabbar a {
  min-width: 0;
  min-height: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: rgba(255, 255, 255, 0.52);
  text-decoration: none;
  font-size: 12px;
  line-height: 1.2;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
}

.tabbar a:active {
  transform: scale(0.97);
}

.tabbar a.router-link-active {
  color: #00ffc3;
  text-shadow: 0 0 20px rgba(0, 255, 195, 0.42);
}

@media (prefers-reduced-motion: no-preference) {
  .tabbar a {
    transition: color 180ms ease, transform 180ms cubic-bezier(0.22, 1, 0.36, 1);
  }
}
</style>
