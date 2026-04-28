<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { Activity, BarChart3, ChevronDown, FileSearch, Package, PlugZap, Settings, ShoppingCart, Users } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()

const navItems = [
  { icon: BarChart3, label: '仪表盘', name: 'dashboard' },
  { icon: PlugZap, label: '供应商管理', name: 'suppliers' },
  { icon: Activity, label: '上游监控', name: 'upstream-monitor' },
  { icon: Users, label: '用户与权限', name: 'users' },
  { icon: Settings, label: '系统设置', name: 'settings' },
  { icon: FileSearch, label: '审计开放', name: 'audit' },
  { icon: ShoppingCart, label: '订单管理', name: 'orders' }
]

const goodsChildren = [
  { label: '商品分类', name: 'categories' },
  { label: '商品列表', name: 'goods' },
  { label: '价格模板配置', name: 'goods-price-templates' }
]

const currentTitle = computed(() => String(route.meta.title || '运营管理后台'))
const isGoodsActive = computed(() => goodsChildren.some((item) => item.name === route.name))
</script>

<template>
  <main class="admin-shell">
    <aside class="sidebar liquid-admin-panel">
      <div class="brand">喜易云</div>
      <div class="nav-group" :class="{ open: isGoodsActive }">
        <button type="button" class="group-trigger" :class="{ active: isGoodsActive }" @click="router.push({ name: 'goods' })">
          <Package :size="18" />
          <span>商品管理</span>
          <ChevronDown class="group-arrow" :size="16" />
        </button>
        <div class="subnav">
          <button
            v-for="child in goodsChildren"
            :key="child.name"
            type="button"
            class="subnav-item"
            :class="{ active: route.name === child.name }"
            @click="router.push({ name: child.name })"
          >
            {{ child.label }}
          </button>
        </div>
      </div>

      <button
        v-for="item in navItems"
        :key="item.name"
        type="button"
        :class="{ active: route.name === item.name }"
        @click="router.push({ name: item.name })"
      >
        <component :is="item.icon" :size="18" />
        <span>{{ item.label }}</span>
      </button>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div>
          <p>运营管理后台</p>
          <h1>{{ currentTitle }}</h1>
        </div>
        <slot name="actions" />
      </header>

      <RouterView />
    </section>
  </main>
</template>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 232px 1fr;
}

.sidebar {
  margin: 14px 0 14px 14px;
  padding: 18px 12px;
  color: rgba(255, 255, 255, 0.78);
  border-radius: 28px;
  overflow: hidden;
}

.brand {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 10px;
  font-size: 22px;
  font-weight: 800;
  color: #fff;
  text-shadow: 0 0 26px rgba(0, 255, 195, 0.24);
}

.sidebar button {
  width: 100%;
  height: 42px;
  margin-top: 6px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0.5px solid transparent;
  border-radius: 14px;
  color: rgba(255, 255, 255, 0.68);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: transform 160ms ease, background 160ms ease, box-shadow 160ms ease;
}

.sidebar button:active {
  transform: scale(0.98);
}

.sidebar button.active,
.sidebar button:hover {
  color: #fff;
  border-color: rgba(0, 255, 195, 0.22);
  background: rgba(0, 255, 195, 0.09);
  box-shadow: 0 0 28px rgba(0, 255, 195, 0.12);
}

.nav-group {
  margin-top: 6px;
  overflow: hidden;
  border-radius: 20px;
  background: rgba(49, 68, 96, 0.56);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.sidebar .group-trigger {
  margin-top: 0;
  border-radius: 18px 18px 0 0;
}

.group-trigger .group-arrow {
  margin-left: auto;
  transition: transform 180ms ease;
}

.nav-group.open .group-arrow {
  transform: rotate(180deg);
}

.subnav {
  padding: 0;
}

.sidebar .subnav-item {
  height: 42px;
  margin-top: 0;
  padding-left: 50px;
  border-radius: 0;
  border: 0;
  color: rgba(255, 255, 255, 0.7);
  background: rgba(14, 23, 38, 0.18);
  font-weight: 600;
}

.sidebar .subnav-item:hover {
  box-shadow: none;
  background: rgba(46, 160, 245, 0.35);
}

.sidebar .subnav-item.active {
  color: #fff;
  background: #2aa3f4;
  box-shadow: none;
}

.workspace {
  min-width: 0;
  padding: 24px;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.topbar p {
  margin: 0 0 4px;
  color: rgba(255, 255, 255, 0.42);
}

.topbar h1 {
  margin: 0;
  font-size: 28px;
  color: rgba(255, 255, 255, 0.92);
  font-weight: 600;
}
</style>
