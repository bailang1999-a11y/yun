<template>
  <WebShell>
    <section class="auth-page">
      <form class="auth-panel auth-panel-rich" @submit.prevent="submit">
        <p>会员中心</p>
        <h1>{{ modeTitle }}</h1>
        <small>{{ modeHint }}</small>

        <div class="auth-tabs">
          <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" :class="{ active: mode === 'register' }" :disabled="!setting.registrationEnabled" @click="switchMode('register')">注册</button>
          <button type="button" :class="{ active: mode === 'forgot' }" @click="switchMode('forgot')">找回密码</button>
        </div>

        <p v-if="!setting.registrationEnabled" class="alert-line subtle">当前系统暂未开放新用户注册，已有用户仍可登录。</p>
        <label>
          <span>{{ accountLabel }}</span>
          <input v-model.trim="account" autocomplete="username" :placeholder="accountPlaceholder" />
        </label>
        <label>
          <span>{{ mode === 'forgot' ? '新密码' : '登录密码' }}</span>
          <input v-model.trim="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :placeholder="mode === 'login' ? '请输入登录密码' : '至少 6 位密码'" />
        </label>
        <label v-if="mode === 'register' || mode === 'forgot'">
          <span>{{ mode === 'forgot' ? '确认新密码' : '确认密码' }}</span>
          <input v-model.trim="confirmPassword" type="password" autocomplete="new-password" placeholder="请再次输入密码" />
        </label>
        <label v-if="showCodeField">
          <span>短信验证码</span>
          <div class="code-entry">
            <input v-model.trim="code" autocomplete="one-time-code" placeholder="请输入短信验证码" />
            <button type="button" class="ghost-button" :disabled="codeSending || countdown > 0 || !account.trim()" @click="sendCode">
              {{ countdown > 0 ? `${countdown}s` : codeSending ? '获取中' : '获取验证码' }}
            </button>
          </div>
        </label>
        <button
          type="button"
          class="web-slider-check"
          :class="{ active: captchaChallenge.enabled && !captchaDone, done: captchaDone, idle: !captchaChallenge.enabled }"
          @click="completeCaptcha"
        >
          {{ captchaDone ? '人机验证完成' : captchaChallenge.enabled ? '点击完成人机验证' : '人机验证未启用' }}
        </button>

        <p v-if="codeMessage" class="success-line">{{ codeMessage }}</p>
        <p v-if="error" class="alert-line">{{ error }}</p>
        <button class="primary-button wide" type="submit" :disabled="loading || (mode === 'register' && !setting.registrationEnabled)">{{ loading ? '处理中...' : actionLabel }}</button>
      </form>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import { getApiErrorMessage } from '../api/client'
import { fetchSiteSettings, fetchWebCaptchaChallenge, sendWebLoginSms } from '../api/web'
import { useSessionStore } from '../stores/session'
import type { CaptchaChallenge, SiteSettings } from '../types/web'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const account = ref('')
const code = ref('')
const password = ref('')
const confirmPassword = ref('')
const mode = ref<'login' | 'register' | 'forgot'>('login')
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const error = ref('')
const codeMessage = ref('')
const captchaDone = ref(false)
const captchaTicket = ref('')
const captchaRandstr = ref('')
const captchaChallenge = ref<CaptchaChallenge>({ enabled: false, provider: 'TENCENT', appId: '' })
const setting = ref<SiteSettings>({
  siteName: '喜易云',
  registrationEnabled: true,
  registrationType: 'MOBILE'
})
let countdownTimer: number | undefined

declare global {
  interface Window {
    TencentCaptcha?: new (appId: string, callback: (res: { ret: number; ticket?: string; randstr?: string }) => void, options?: Record<string, unknown>) => { show: () => void }
  }
}

const modeTitle = computed(() => {
  if (mode.value === 'register') return '创建会员账号'
  if (mode.value === 'forgot') return '找回登录密码'
  return '登录后购买商品'
})

const modeHint = computed(() => {
  if (mode.value === 'register') return '注册后可同步订单、余额、卡密和售后记录。'
  if (mode.value === 'forgot') return '通过短信验证码确认身份并设置新密码。'
  return '支持密码登录；启用短信校验时需要验证码。'
})

const actionLabel = computed(() => {
  if (mode.value === 'register') return '注册并登录'
  if (mode.value === 'forgot') return '验证身份'
  return '登录'
})

const accountLabel = computed(() => {
  if (mode.value !== 'register') return '手机号 / 邮箱 / 账号'
  if (setting.value.registrationType === 'MOBILE') return '手机号'
  if (setting.value.registrationType === 'EMAIL') return '邮箱'
  return '手机号 / 邮箱 / 账号'
})

const accountPlaceholder = computed(() => {
  if (mode.value !== 'register') return '请输入手机号、邮箱或账号'
  if (setting.value.registrationType === 'MOBILE') return '请输入手机号'
  if (setting.value.registrationType === 'EMAIL') return '请输入邮箱'
  return '请输入账号'
})

const showCodeField = computed(() =>
  mode.value === 'login' || mode.value === 'forgot' || (mode.value === 'register' && setting.value.registrationType === 'MOBILE')
)

async function submit() {
  const cleanAccount = account.value.trim()
  if (!cleanAccount) {
    error.value = '请输入账号'
    return
  }
  if (mode.value === 'register' && !setting.value.registrationEnabled) {
    error.value = '当前系统暂未开放新用户注册'
    return
  }
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    error.value = '请先完成人机验证'
    return
  }
  if (mode.value === 'register' && password.value && password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (mode.value === 'forgot' && (!password.value || password.value !== confirmPassword.value)) {
    error.value = '请填写并确认新密码'
    return
  }
  if (mode.value === 'login' && !password.value.trim() && !code.value.trim()) {
    error.value = '请输入登录密码或短信验证码'
    return
  }
  if (showCodeField.value && mode.value !== 'login' && !code.value.trim()) {
    error.value = '请输入短信验证码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await session.auth({
      account: cleanAccount,
      password: password.value,
      confirmPassword: confirmPassword.value,
      code: code.value,
      terminal: 'web',
      captchaTicket: captchaTicket.value,
      captchaRandstr: captchaRandstr.value,
      mode: mode.value
    })
    await router.replace(String(route.query.redirect || '/'))
  } catch (err) {
    resetCaptcha()
    error.value = getApiErrorMessage(err)
  } finally {
    loading.value = false
  }
}

function switchMode(next: 'login' | 'register' | 'forgot') {
  if (next === 'register' && !setting.value.registrationEnabled) {
    error.value = '当前系统暂未开放新用户注册'
    return
  }
  mode.value = next
  error.value = ''
  codeMessage.value = ''
  resetCaptcha()
}

async function completeCaptcha() {
  try {
    if (!captchaChallenge.value.enabled) {
      captchaDone.value = true
      error.value = ''
      return true
    }
    if (captchaChallenge.value.provider !== 'TENCENT' || !captchaChallenge.value.appId) {
      error.value = '人机验证未配置完整'
      return false
    }
    const result = await runTencentCaptcha(captchaChallenge.value.appId)
    captchaTicket.value = result.ticket
    captchaRandstr.value = result.randstr
    captchaDone.value = true
    error.value = ''
    return true
  } catch (err) {
    resetCaptcha()
    error.value = getApiErrorMessage(err)
    return false
  }
}

function resetCaptcha() {
  captchaDone.value = false
  captchaTicket.value = ''
  captchaRandstr.value = ''
}

async function loadCaptchaChallenge() {
  try {
    captchaChallenge.value = await fetchWebCaptchaChallenge()
    if (!captchaChallenge.value.enabled) captchaDone.value = true
  } catch {
    captchaChallenge.value = { enabled: false, provider: 'TENCENT', appId: '' }
    captchaDone.value = true
  }
}

async function loadSettings() {
  try {
    setting.value = await fetchSiteSettings()
  } catch {
    setting.value = { siteName: '喜易云', registrationEnabled: true, registrationType: 'MOBILE' }
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

async function sendCode() {
  if (!account.value.trim() || codeSending.value || countdown.value > 0) return
  if (mode.value !== 'login' && !/^1[3-9]\d{9}$/.test(account.value.trim())) {
    error.value = '短信验证码仅支持手机号'
    return
  }
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    const passed = await completeCaptcha()
    if (!passed) return
  }
  codeSending.value = true
  error.value = ''
  codeMessage.value = ''
  try {
    codeMessage.value = await sendWebLoginSms(account.value.trim(), captchaTicket.value, captchaRandstr.value, mode.value)
    resetCaptcha()
    startCountdown()
  } catch (err) {
    resetCaptcha()
    error.value = getApiErrorMessage(err)
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

onBeforeUnmount(() => {
  if (countdownTimer) window.clearInterval(countdownTimer)
})

onMounted(() => {
  void loadCaptchaChallenge()
  void loadSettings()
})
</script>
