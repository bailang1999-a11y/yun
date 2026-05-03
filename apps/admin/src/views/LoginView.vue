<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { LoaderCircle, LogIn } from 'lucide-vue-next'
import { loginAdmin } from '../api/auth'

const router = useRouter()
const account = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

async function submit() {
  if (loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const session = await loginAdmin(account.value.trim(), password.value)
    localStorage.setItem('xiyiyun_admin_token', session.token)
    await router.push({ name: 'dashboard' })
  } catch {
    errorMessage.value = '账号或密码错误'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-panel">
      <div>
        <p>运营管理后台</p>
        <h1>喜易云</h1>
      </div>
      <label>
        <span>账号</span>
        <input v-model.trim="account" autocomplete="username" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" @keyup.enter="submit" />
      </label>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <button type="button" :disabled="loading" @click="submit">
        <LoaderCircle v-if="loading" class="spin" :size="17" />
        <LogIn v-else :size="17" />
        登录
      </button>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.login-panel {
  width: min(420px, 100%);
  display: grid;
  gap: 16px;
  padding: 28px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.05);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(32px) saturate(180%);
}

p,
h1 {
  margin: 0;
}

p,
label span {
  color: rgba(255, 255, 255, 0.52);
}

h1 {
  color: rgba(255, 255, 255, 0.94);
  font-size: 32px;
}

label {
  display: grid;
  gap: 8px;
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

button {
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #06100e;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
  font-weight: 700;
}

button:active {
  transform: scale(0.98);
}

.error {
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
