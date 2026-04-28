<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { LoaderCircle, LogOut, UserRound } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { fetchH5Me, loginH5 } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import type { UserProfile } from '../types/h5'

const tokenKey = 'xiyiyun_h5_token'
const account = ref('13800000001')
const loading = ref(false)
const errorMessage = ref('')
const profile = ref<UserProfile | null>(null)

onMounted(() => {
  const token = localStorage.getItem(tokenKey)
  if (token) void loadProfile(token)
})

async function loadProfile(token: string) {
  loading.value = true
  errorMessage.value = ''
  try {
    profile.value = await fetchH5Me(token)
  } catch (error) {
    localStorage.removeItem(tokenKey)
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

async function login() {
  if (!account.value.trim() || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const session = await loginH5(account.value.trim())
    localStorage.setItem(tokenKey, session.token)
    profile.value = session.profile
  } catch (error) {
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function logout() {
  localStorage.removeItem(tokenKey)
  profile.value = null
}
</script>

<template>
  <main class="page page-pad">
    <section class="profile liquid-surface">
      <div class="avatar">{{ profile?.nickname?.slice(0, 1) || '喜' }}</div>
      <div>
        <h1>{{ profile?.nickname || '游客用户' }}</h1>
        <p>{{ profile ? `${profile.groupName || '默认会员'} · 余额 ¥${Number(profile.balance || 0).toFixed(2)}` : '登录后查看订单、卡密记录和余额。' }}</p>
      </div>
    </section>

    <section v-if="!profile" class="login-panel liquid-surface">
      <label>
        <span>手机号 / 邮箱</span>
        <input v-model.trim="account" inputmode="email" autocomplete="username" placeholder="输入手机号或邮箱" />
      </label>
      <button type="button" class="primary-action" :disabled="loading || !account.trim()" @click="login">
        <LoaderCircle v-if="loading" class="spin" :size="16" />
        登录 / 注册
      </button>
    </section>

    <section v-else class="menu-panel liquid-surface">
      <div class="menu-row"><UserRound :size="17" /><span>{{ profile.mobile || profile.email }}</span></div>
      <button type="button" class="menu-row logout" @click="logout"><LogOut :size="17" /><span>退出登录</span></button>
    </section>

    <p v-if="errorMessage" class="notice danger">{{ errorMessage }}</p>
    <AppTabbar />
  </main>
</template>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  margin-top: 8px;
  border-radius: 24px;
}

.avatar {
  width: 54px;
  height: 54px;
  display: grid;
  place-items: center;
  color: #fff;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 20%, #fff, #00ffc3 28%, #0b5753 78%);
  box-shadow: 0 0 28px rgba(0, 255, 195, 0.24);
  font-weight: 800;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

p {
  margin: 0;
  color: rgba(255, 255, 255, 0.55);
}

.login-panel,
.menu-panel {
  display: grid;
  gap: 12px;
  margin-top: 12px;
  padding: 16px;
  border-radius: 24px;
}

label {
  display: grid;
  gap: 8px;
  color: rgba(255, 255, 255, 0.62);
}

input {
  height: 44px;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.9);
  border-radius: 16px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.06);
  outline: none;
}

.primary-action,
.menu-row {
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 16px;
}

.primary-action {
  justify-content: center;
  color: #06100e;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  font-weight: 700;
}

.menu-row {
  width: 100%;
  padding: 0 12px;
  color: rgba(255, 255, 255, 0.78);
  background: rgba(255, 255, 255, 0.05);
}

.logout {
  color: #ff8d86;
}

.notice {
  margin-top: 12px;
  padding: 12px;
}

.notice.danger {
  color: #ff8d86;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
