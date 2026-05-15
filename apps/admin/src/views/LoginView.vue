<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { LoaderCircle, LogIn } from 'lucide-vue-next'
import { loginAdmin } from '../api/auth'
import { fetchAdminCaptchaChallenge } from '../api/captcha'
import { sendAdminLoginSms } from '../api/smsLogin'
import type { CaptchaChallenge } from '../types/operations'

const router = useRouter()
const account = ref('')
const password = ref('')
const code = ref('')
const mode = ref<'login' | 'recover'>('login')
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const errorMessage = ref('')
const codeMessage = ref('')
const captchaDone = ref(false)
const captchaTicket = ref('')
const captchaRandstr = ref('')
const captchaChallenge = ref<CaptchaChallenge>({ enabled: false, provider: 'TENCENT', appId: '' })
let countdownTimer: number | undefined

declare global {
  interface Window {
    TencentCaptcha?: new (appId: string, callback: (res: { ret: number; ticket?: string; randstr?: string }) => void, options?: Record<string, unknown>) => { show: () => void }
  }
}

onMounted(loadCaptchaChallenge)

onBeforeUnmount(() => {
  if (countdownTimer) window.clearInterval(countdownTimer)
})

const title = computed(() => (mode.value === 'login' ? '管理员登录' : '找回管理员密码'))
const subtitle = computed(() => (mode.value === 'login' ? '密码、短信验证码和滑块校验共同保护后台入口。' : '管理员密码找回需要短信校验，后端重置接口确认后接入。'))

async function submit() {
  if (loading.value) return
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    errorMessage.value = '请先完成人机验证'
    return
  }
  if (mode.value === 'recover') {
    errorMessage.value = '找回密码后端接口待接入，请先联系超级管理员重置。'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const session = await loginAdmin(account.value.trim(), password.value, code.value.trim(), captchaTicket.value, captchaRandstr.value)
    localStorage.setItem('xiyiyun_admin_token', session.token)
    await router.push({ name: 'dashboard' })
  } catch (error) {
    resetCaptcha()
    errorMessage.value = error instanceof Error ? error.message : '账号或密码错误'
  } finally {
    loading.value = false
  }
}

async function sendCode() {
  if (!account.value.trim() || codeSending.value || countdown.value > 0) return
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    const passed = await completeCaptcha()
    if (!passed) return
  }
  codeSending.value = true
  errorMessage.value = ''
  codeMessage.value = ''
  try {
    codeMessage.value = await sendAdminLoginSms(account.value.trim(), captchaTicket.value, captchaRandstr.value)
    resetCaptcha()
    startCountdown()
  } catch (error) {
    resetCaptcha()
    errorMessage.value = error instanceof Error ? error.message : '验证码发送失败'
  } finally {
    codeSending.value = false
  }
}

function startCountdown() {
  countdown.value = 60
  if (countdownTimer) window.clearInterval(countdownTimer)
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && countdownTimer) {
      window.clearInterval(countdownTimer)
      countdownTimer = undefined
    }
  }, 1000)
}

async function completeCaptcha() {
  try {
    if (!captchaChallenge.value.enabled) {
      captchaDone.value = true
      errorMessage.value = ''
      return true
    }
    if (captchaChallenge.value.provider !== 'TENCENT' || !captchaChallenge.value.appId) {
      errorMessage.value = '人机验证未配置完整'
      return false
    }
    const result = await runTencentCaptcha(captchaChallenge.value.appId)
    captchaTicket.value = result.ticket
    captchaRandstr.value = result.randstr
    captchaDone.value = true
    errorMessage.value = ''
    return true
  } catch (error) {
    resetCaptcha()
    errorMessage.value = error instanceof Error ? error.message : '人机验证失败，请重试'
    return false
  }
}

function switchMode(next: 'login' | 'recover') {
  mode.value = next
  errorMessage.value = ''
  codeMessage.value = ''
  resetCaptcha()
}

function resetCaptcha() {
  captchaDone.value = false
  captchaTicket.value = ''
  captchaRandstr.value = ''
}

async function loadCaptchaChallenge() {
  try {
    captchaChallenge.value = await fetchAdminCaptchaChallenge()
    if (!captchaChallenge.value.enabled) captchaDone.value = true
  } catch {
    captchaChallenge.value = { enabled: false, provider: 'TENCENT', appId: '' }
    captchaDone.value = true
  }
}

function loadTencentCaptchaScript() {
  return new Promise<void>((resolve, reject) => {
    if (window.TencentCaptcha) {
      resolve()
      return
    }
    const existing = document.querySelector<HTMLScriptElement>('script[data-tencent-captcha="true"]')
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true })
      existing.addEventListener('error', () => reject(new Error('腾讯云验证码脚本加载失败')), { once: true })
      return
    }
    const script = document.createElement('script')
    script.src = 'https://turing.captcha.qcloud.com/TJCaptcha.js'
    script.async = true
    script.dataset.tencentCaptcha = 'true'
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('腾讯云验证码脚本加载失败'))
    document.head.appendChild(script)
  })
}

async function runTencentCaptcha(appId: string) {
  await loadTencentCaptchaScript()
  return new Promise<{ ticket: string; randstr: string }>((resolve, reject) => {
    const Captcha = window.TencentCaptcha
    if (!Captcha) {
      reject(new Error('腾讯云验证码脚本未就绪'))
      return
    }
    const captcha = new Captcha(appId, (res: { ret: number; ticket?: string; randstr?: string }) => {
      if (res.ret === 0 && res.ticket && res.randstr) {
        resolve({ ticket: res.ticket, randstr: res.randstr })
      } else {
        reject(new Error('人机验证已取消或未通过'))
      }
    })
    captcha.show()
  })
}
</script>

<template>
  <main class="login-shell">
    <section class="login-panel">
      <div>
        <p>运营管理后台</p>
        <h1>{{ title }}</h1>
        <small>{{ subtitle }}</small>
      </div>
      <div class="mode-tabs">
        <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button type="button" :class="{ active: mode === 'recover' }" @click="switchMode('recover')">找回密码</button>
      </div>
      <label>
        <span>账号</span>
        <input v-model.trim="account" autocomplete="username" />
      </label>
      <label v-if="mode === 'login'">
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" @keyup.enter="submit" />
      </label>
      <label>
        <span>短信验证码（开启后必填）</span>
        <div class="code-entry">
          <input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入验证码" @keyup.enter="submit" />
          <button type="button" class="code-button" :disabled="codeSending || countdown > 0 || !account.trim()" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s` : codeSending ? '发送中' : '获取验证码' }}
          </button>
        </div>
      </label>
      <button
        type="button"
        class="slider-check"
        :class="{ active: captchaChallenge.enabled && !captchaDone, done: captchaDone, idle: !captchaChallenge.enabled }"
        @click="completeCaptcha"
      >
        <span>{{ captchaDone ? '人机验证完成' : captchaChallenge.enabled ? '点击完成人机验证' : '人机验证未启用' }}</span>
      </button>
      <p v-if="codeMessage" class="success">{{ codeMessage }}</p>
      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <button class="login-button" type="button" :disabled="loading" @click="submit">
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

small {
  display: block;
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.48);
  line-height: 1.6;
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

.mode-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 4px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.055);
}

.mode-tabs button {
  height: 38px;
  color: rgba(255, 255, 255, 0.7);
  background: transparent;
}

.mode-tabs button.active {
  color: #06100e;
  background: rgba(223, 255, 246, 0.92);
}

.code-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  gap: 8px;
}

.code-button {
  height: 44px;
  color: rgba(255, 255, 255, 0.84);
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
}

.login-button {
  color: #06100e;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
}

.slider-check {
  position: relative;
  justify-content: flex-start;
  padding: 0 14px;
  color: rgba(255, 255, 255, 0.72);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.055);
  overflow: hidden;
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease, box-shadow 160ms ease;
}

.slider-check::before {
  content: '';
  width: 34px;
  height: 34px;
  margin-right: 12px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.16);
}

.slider-check.done {
  color: #b9ffe9;
  border-color: rgba(0, 255, 195, 0.35);
  background: rgba(0, 255, 195, 0.12);
}

.slider-check.active {
  color: #ffffff;
  border-color: rgba(116, 168, 255, 0.58);
  background: linear-gradient(135deg, rgba(61, 141, 255, 0.9), rgba(181, 125, 255, 0.82));
  box-shadow: 0 12px 26px rgba(86, 145, 255, 0.22);
}

.slider-check.active::before {
  background: rgba(255, 255, 255, 0.28);
}

button:active {
  transform: scale(0.98);
}

.error {
  color: #ff8d86;
}

.success {
  color: #7ef7d4;
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
