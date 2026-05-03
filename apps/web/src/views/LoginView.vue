<template>
  <WebShell>
    <section class="auth-page">
      <form class="auth-panel" @submit.prevent="submit">
        <p>会员登录</p>
        <h1>登录后购买商品</h1>

        <label>
          <span>手机号 / 账号</span>
          <input v-model.trim="account" autocomplete="username" placeholder="请输入账号" />
        </label>
        <label>
          <span>验证码</span>
          <input v-model.trim="code" autocomplete="one-time-code" placeholder="测试环境可用 123456" />
        </label>

        <p v-if="error" class="alert-line">{{ error }}</p>
        <button class="primary-button wide" type="submit" :disabled="loading">{{ loading ? '登录中...' : '登录' }}</button>
      </form>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import { getApiErrorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const account = ref('13800000001')
const code = ref('123456')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await session.login(account.value, code.value)
    await router.replace(String(route.query.redirect || '/'))
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    loading.value = false
  }
}
</script>
