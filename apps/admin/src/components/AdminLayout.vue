<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Activity,
  BarChart3,
  ChevronDown,
  FileSearch,
  Package,
  PackageCheck,
  PlugZap,
  CreditCard,
  MessageSquareText,
  KeyRound,
  ScanLine,
  ShieldCheck,
  Settings,
  ShoppingCart,
  UserPlus,
  UserRound,
  Users,
  WalletCards
} from 'lucide-vue-next'
import { fetchAdminMe, updateSuperAdminCredentials } from '../api/auth'
import type { AdminCredentialPayload, AdminProfile } from '../types/operations'
import { formatMoney as formatAmount } from '../utils/formatters'

const route = useRoute()
const router = useRouter()
const profile = ref<AdminProfile | null>(null)
const sessionReady = ref(false)
const goodsMenuOpen = ref(false)
const credentialDialogVisible = ref(false)
const credentialSaving = ref(false)
const credentialForm = reactive<AdminCredentialPayload>({
  currentPassword: '',
  account: '',
  nickname: '',
  newPassword: '',
  confirmPassword: ''
})

const navItems = [
  { icon: BarChart3, label: '仪表盘', name: 'dashboard', permission: 'dashboard:read' },
  { icon: PlugZap, label: '供应商管理', name: 'suppliers', permission: 'goods:manage' },
  { icon: PackageCheck, label: '货源对接', name: 'source-connect', permission: 'goods:manage' },
  { icon: Activity, label: '上游监控', name: 'upstream-monitor', permission: 'goods:manage' },
  { icon: ScanLine, label: '商品监控', name: 'goods-monitor', permission: 'goods:manage' },
  { icon: CreditCard, label: '支付通道', name: 'payment-channels', permission: 'settings:manage' },
  { icon: Users, label: '用户与权限', name: 'users', permission: 'users:manage' },
  { icon: UserPlus, label: '员工管理', name: 'staff', permission: 'staff:manage' },
  { icon: Settings, label: '系统设置', name: 'settings', permission: 'settings:manage' },
  { icon: MessageSquareText, label: '短信登录', name: 'sms-login-settings', permission: 'settings:manage' },
  { icon: ShieldCheck, label: '人机验证', name: 'captcha-settings', permission: 'settings:manage' },
  { icon: FileSearch, label: '审计开放', name: 'audit', permission: 'dashboard:read' },
  { icon: ShoppingCart, label: '订单管理', name: 'orders', permission: 'orders:manage' }
]

const goodsChildren = [
  { label: '商品列表', name: 'goods' },
  { label: '卡密仓库', name: 'card-warehouse' },
  { label: '充值字段管理', name: 'recharge-fields' },
  { label: '价格模板配置', name: 'goods-price-templates' }
]

const goodsRouteNames = new Set(['categories', ...goodsChildren.map((item) => item.name)])
const currentTitle = computed(() => String(route.meta.title || '运营管理后台'))
const isGoodsActive = computed(() => goodsRouteNames.has(String(route.name || '')))
const displayName = computed(() => profile.value?.nickname || profile.value?.username || '运营管理员')
const displayAccount = computed(() => profile.value?.username || 'admin')
const visibleNavItems = computed(() => navItems.filter((item) => hasPermission(item.permission)))
const canUseGoodsMenu = computed(() => hasPermission('goods:manage'))
const isSuperAdmin = computed(() => String(profile.value?.id || '') === '1')

watch(
  () => route.name,
  (name) => {
    if (goodsRouteNames.has(String(name || ''))) {
      goodsMenuOpen.value = true
    }
  },
  { immediate: true }
)

onMounted(async () => {
  try {
    profile.value = await fetchAdminMe()
  } catch {
    localStorage.removeItem('xiyiyun_admin_token')
    profile.value = null
    void router.replace({ name: 'login' })
    return
  } finally {
    sessionReady.value = true
  }
})

function formatMoney(value?: number | string) {
  return formatAmount(value ?? 0, { fallback: '¥0.00' })
}

function toggleGoodsMenu() {
  goodsMenuOpen.value = !goodsMenuOpen.value
}

function hasPermission(permission: string) {
  if (!profile.value) return true
  return profile.value.permissions.includes(permission)
}

function openCredentialDialog() {
  if (!profile.value) return
  credentialForm.currentPassword = ''
  credentialForm.account = profile.value.username || ''
  credentialForm.nickname = profile.value.nickname || ''
  credentialForm.newPassword = ''
  credentialForm.confirmPassword = ''
  credentialDialogVisible.value = true
}

async function submitSuperAdminCredentials() {
  if (!credentialForm.currentPassword.trim()) {
    ElMessage.warning('请输入当前密码')
    return
  }
  if (!credentialForm.account.trim()) {
    ElMessage.warning('请输入超级管理员账号')
    return
  }
  if ((credentialForm.newPassword || credentialForm.confirmPassword) && (credentialForm.newPassword || '').length < 6) {
    ElMessage.warning('新密码至少需要 6 位')
    return
  }
  if ((credentialForm.newPassword || '') !== (credentialForm.confirmPassword || '')) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }

  credentialSaving.value = true
  try {
    await updateSuperAdminCredentials({
      currentPassword: credentialForm.currentPassword,
      account: credentialForm.account.trim(),
      nickname: credentialForm.nickname.trim(),
      newPassword: credentialForm.newPassword || '',
      confirmPassword: credentialForm.confirmPassword || ''
    })
    localStorage.removeItem('xiyiyun_admin_token')
    ElMessage.success('超级管理员账号密码已更新，请重新登录')
    credentialDialogVisible.value = false
    void router.replace({ name: 'login' })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '超级管理员账号密码更新失败')
  } finally {
    credentialSaving.value = false
  }
}
</script>

<template>
  <main class="admin-shell">
    <aside class="sidebar liquid-admin-panel">
      <div class="brand">喜易云</div>
      <section class="account-card" aria-label="当前登录账号">
        <div class="account-main">
          <div class="account-avatar">
            <UserRound :size="18" />
          </div>
          <div class="account-copy">
            <strong>{{ displayName }}</strong>
            <span>{{ displayAccount }}</span>
          </div>
        </div>
        <div class="account-balance">
          <span>账户余额</span>
          <strong>
            <WalletCards :size="14" />
            {{ formatMoney(profile?.balance) }}
          </strong>
        </div>
        <button v-if="isSuperAdmin" type="button" class="account-security" @click="openCredentialDialog">
          <KeyRound :size="14" />
          <span>修改超管账号密码</span>
        </button>
      </section>
      <div v-if="canUseGoodsMenu" class="nav-group" :class="{ open: goodsMenuOpen, active: isGoodsActive }">
        <button
          type="button"
          class="group-trigger"
          :class="{ active: isGoodsActive }"
          :aria-expanded="goodsMenuOpen"
          aria-controls="goods-management-subnav"
          @click="toggleGoodsMenu"
        >
          <Package :size="18" />
          <span>商品管理</span>
          <ChevronDown class="group-arrow" :size="16" />
        </button>
        <div id="goods-management-subnav" v-show="goodsMenuOpen" class="subnav" :aria-hidden="!goodsMenuOpen">
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
        v-for="item in visibleNavItems"
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

      <RouterView v-if="sessionReady" />
    </section>

    <el-dialog v-model="credentialDialogVisible" title="修改超级管理员账号密码" width="620px" class="xiyiyun-glass-dialog admin-credential-dialog">
      <el-form label-position="top" class="admin-credential-form">
        <el-form-item label="当前密码">
          <el-input v-model="credentialForm.currentPassword" type="password" show-password autocomplete="current-password" placeholder="请输入当前超级管理员密码" />
        </el-form-item>
        <div class="credential-grid">
          <el-form-item label="超级管理员账号">
            <el-input v-model.trim="credentialForm.account" autocomplete="username" placeholder="例如 admin" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model.trim="credentialForm.nickname" placeholder="例如 运营管理员" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="credentialForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="留空则不修改密码" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="credentialForm.confirmPassword" type="password" show-password autocomplete="new-password" placeholder="再次输入新密码" />
          </el-form-item>
        </div>
        <p class="credential-hint">保存后会清理所有后台登录态，并要求使用新账号密码重新登录。</p>
      </el-form>
      <template #footer>
        <el-button @click="credentialDialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="KeyRound" :loading="credentialSaving" @click="submitSuperAdminCredentials">保存并重新登录</el-button>
      </template>
    </el-dialog>
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

.account-card {
  position: relative;
  display: grid;
  gap: 12px;
  margin: 4px 0 14px;
  padding: 12px;
  overflow: hidden;
  border-radius: 18px;
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.13), rgba(46, 160, 245, 0.08)),
    rgba(255, 255, 255, 0.04);
  border: 0.5px solid rgba(0, 255, 195, 0.18);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12), 0 12px 30px rgba(0, 0, 0, 0.12);
}

.account-card::after {
  content: '';
  position: absolute;
  right: -24px;
  top: -34px;
  width: 88px;
  height: 88px;
  border-radius: 999px;
  background: rgba(46, 160, 245, 0.12);
  pointer-events: none;
}

.account-main {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}

.account-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: #bdf8dc;
  border-radius: 14px;
  background: rgba(0, 255, 195, 0.13);
  border: 0.5px solid rgba(0, 255, 195, 0.24);
  box-shadow: 0 0 18px rgba(0, 255, 195, 0.1);
}

.account-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.account-copy strong,
.account-copy span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy strong {
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  font-weight: 800;
}

.account-copy span {
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
}

.account-balance {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border-radius: 12px;
  background: rgba(3, 7, 18, 0.18);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.account-balance > span {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
}

.account-balance strong {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  color: #d9f99d;
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.account-security {
  position: relative;
  z-index: 1;
  justify-content: center;
  height: 34px !important;
  margin-top: 0 !important;
  color: rgba(255, 255, 255, 0.76) !important;
  border-color: rgba(0, 255, 195, 0.18) !important;
  background: rgba(255, 255, 255, 0.055) !important;
  font-size: 12px;
}

.credential-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.credential-hint {
  margin: 2px 0 0;
  color: rgba(226, 232, 240, 0.62);
  font-size: 13px;
  line-height: 1.7;
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
  border-radius: 18px;
}

.nav-group.open .group-trigger {
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
  max-height: 0;
  padding: 0;
  overflow: hidden;
  opacity: 0;
  transition: max-height 180ms ease, opacity 140ms ease;
}

.nav-group.open .subnav {
  max-height: 220px;
  opacity: 1;
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
