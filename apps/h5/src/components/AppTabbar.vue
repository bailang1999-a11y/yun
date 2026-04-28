<script setup lang="ts">
import { ClipboardList, House, Ticket, User } from 'lucide-vue-next'
</script>

<template>
  <nav class="tabbar">
    <RouterLink to="/"><House :size="20" />首页</RouterLink>
    <RouterLink to="/orders"><ClipboardList :size="20" />订单</RouterLink>
    <RouterLink to="/cards"><Ticket :size="20" />卡密</RouterLink>
    <RouterLink to="/mine"><User :size="20" />我的</RouterLink>
  </nav>
</template>

<style scoped>
.tabbar {
  position: fixed;
  left: 12px;
  right: 12px;
  bottom: 12px;
  height: 58px;
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
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: rgba(255, 255, 255, 0.52);
  text-decoration: none;
  font-size: 12px;
}

.tabbar a.router-link-active {
  color: #00ffc3;
  text-shadow: 0 0 20px rgba(0, 255, 195, 0.42);
}
</style>
