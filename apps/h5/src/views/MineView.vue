<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LoaderCircle, LogOut, UserRound } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { authH5, fetchH5CaptchaChallenge, fetchH5Me, fetchH5Settings, sendH5LoginSms } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import type { CaptchaChallenge, H5SystemSetting, UserProfile } from '../types/h5'

const tokenKey = 'xiyiyun_h5_token'
const route = useRoute()
const router = useRouter()
const account = ref('')
const code = ref('')
const password = ref('')
const confirmPassword = ref('')
const mode = ref<'login' | 'register' | 'forgot'>('login')
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const errorMessage = ref('')
const codeMessage = ref('')
const captchaDone = ref(false)
const captchaTicket = ref('')
const captchaRandstr = ref('')
const captchaChallenge = ref<CaptchaChallenge>({ enabled: false, provider: 'TENCENT', appId: '' })
const turnstileBoxRef = ref<HTMLElement | null>(null)
const profile = ref<UserProfile | null>(null)
const setting = ref<H5SystemSetting>({
  registrationEnabled: true,
  registrationType: 'MOBILE'
})
let countdownTimer: number | undefined
let turnstileWidgetId = ''

declare global {
  interface Window {
    TencentCaptcha?: new (appId: string, callback: (res: { ret: number; ticket?: string; randstr?: string; errorCode?: number; errorMessage?: string }) => void, options?: Record<string, unknown>) => { show: () => void }
    turnstile?: {
      render: (container: HTMLElement, options: Record<string, unknown>) => string
      remove: (widgetId: string) => void
    }
  }
}

const modeTitle = computed(() => {
  if (mode.value === 'register') return '注册账号'
  if (mode.value === 'forgot') return '找回密码'
  return '登录账号'
})

const modeHint = computed(() => {
  if (mode.value === 'register') return '按后台注册设置创建会员账号。'
  if (mode.value === 'forgot') return '通过短信验证码确认身份并设置新密码。'
  return '登录后查看订单、卡密记录和账户余额。'
})

const codeRequiredForSubmit = computed(() =>
  mode.value === 'forgot'
    || (mode.value === 'register' && setting.value.registrationType === 'MOBILE')
    || (mode.value === 'login' && !password.value.trim())
)

const isTurnstileCaptcha = computed(() => captchaChallenge.value.enabled && captchaChallenge.value.provider === 'TURNSTILE')

onMounted(() => {
  void loadSettings()
  void loadCaptchaChallenge()
  const token = localStorage.getItem(tokenKey)
  if (token) void loadProfile(token)
  window.addEventListener('focus', refreshProfile)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshProfile)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
  if (countdownTimer) window.clearInterval(countdownTimer)
  clearTurnstileWidget()
})

function refreshProfile() {
  const token = localStorage.getItem(tokenKey)
  if (token) void loadProfile(token)
}

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') refreshProfile()
}

async function loadSettings() {
  try {
    setting.value = await fetchH5Settings()
  } catch {
    setting.value = { registrationEnabled: true, registrationType: 'MOBILE' }
  }
}

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
  const cleanAccount = account.value.trim()
  if (!cleanAccount || loading.value) return
  if (mode.value === 'register' && !setting.value.registrationEnabled) {
    errorMessage.value = '当前系统暂未开放新用户注册'
    return
  }
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    errorMessage.value = '请先完成人机验证'
    return
  }
  if (mode.value === 'register' && password.value && password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }
  if (mode.value === 'forgot' && (!password.value || password.value !== confirmPassword.value)) {
    errorMessage.value = '请填写并确认新密码'
    return
  }
  if (mode.value === 'login' && !password.value.trim() && !code.value.trim()) {
    errorMessage.value = '请输入登录密码或短信验证码'
    return
  }
  if (needCode() && codeRequiredForSubmit.value && !code.value.trim()) {
    errorMessage.value = '请输入短信验证码'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const session = await authH5({
      account: cleanAccount,
      password: password.value,
      confirmPassword: confirmPassword.value,
      code: code.value.trim(),
      terminal: 'h5',
      captchaTicket: captchaTicket.value,
      captchaRandstr: captchaRandstr.value,
      mode: mode.value
    })
    localStorage.setItem(tokenKey, session.token)
    profile.value = session.profile
    await router.replace(String(route.query.redirect || '/'))
  } catch (error) {
    resetCaptcha()
    errorMessage.value = getApiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function switchMode(next: 'login' | 'register' | 'forgot') {
  if (next === 'register' && !setting.value.registrationEnabled) {
    errorMessage.value = '当前系统暂未开放新用户注册'
    return
  }
  mode.value = next
  errorMessage.value = ''
  codeMessage.value = ''
  resetCaptcha()
}

async function completeCaptcha() {
  try {
    if (!captchaChallenge.value.enabled) {
      captchaDone.value = true
      errorMessage.value = ''
      return true
    }
    if (!captchaChallenge.value.appId) {
      errorMessage.value = '人机验证未配置完整'
      return false
    }
    if (captchaChallenge.value.provider === 'TURNSTILE') {
      errorMessage.value = '请在页面中的 Cloudflare 验证框完成验证'
      void renderTurnstileWidget()
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
    errorMessage.value = getApiErrorMessage(error)
    return false
  }
}

function resetCaptcha() {
  captchaDone.value = false
  captchaTicket.value = ''
  captchaRandstr.value = ''
  if (isTurnstileCaptcha.value) void renderTurnstileWidget()
}

async function loadCaptchaChallenge() {
  try {
    captchaChallenge.value = await fetchH5CaptchaChallenge('h5')
    if (!captchaChallenge.value.enabled) captchaDone.value = true
    else if (captchaChallenge.value.provider === 'TURNSTILE') void renderTurnstileWidget()
  } catch {
    captchaChallenge.value = { enabled: false, provider: 'TENCENT', appId: '' }
    captchaDone.value = true
  }
}

function clearTurnstileWidget() {
  if (turnstileWidgetId && window.turnstile) window.turnstile.remove(turnstileWidgetId)
  turnstileWidgetId = ''
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

function loadTurnstileScript() {
  return new Promise<void>((resolve, reject) => {
    if (window.turnstile) {
      resolve()
      return
    }
    const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile="true"]')
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true })
      existing.addEventListener('error', () => reject(new Error('Cloudflare Turnstile 脚本加载失败')), { once: true })
      return
    }
    const script = document.createElement('script')
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
    script.async = true
    script.defer = true
    script.dataset.turnstile = 'true'
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Cloudflare Turnstile 脚本加载失败'))
    document.head.appendChild(script)
  })
}

async function renderTurnstileWidget() {
  if (!isTurnstileCaptcha.value || !captchaChallenge.value.appId) return
  await loadTurnstileScript()
  await nextTick()
  if (!window.turnstile || !turnstileBoxRef.value) return
  clearTurnstileWidget()
  turnstileWidgetId = window.turnstile.render(turnstileBoxRef.value, {
    sitekey: captchaChallenge.value.appId,
    theme: 'auto',
    callback: (token: string) => {
      captchaTicket.value = token
      captchaRandstr.value = 'turnstile'
      captchaDone.value = true
      errorMessage.value = ''
    },
    'error-callback': () => {
      captchaDone.value = false
      captchaTicket.value = ''
      captchaRandstr.value = ''
      errorMessage.value = 'Cloudflare Turnstile 验证失败，请重试'
    },
    'expired-callback': () => {
      captchaDone.value = false
      captchaTicket.value = ''
      captchaRandstr.value = ''
      errorMessage.value = 'Cloudflare Turnstile 已过期，请重新验证'
    }
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
    let settled = false
    const finish = (handler: () => void) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      handler()
    }
    const timer = window.setTimeout(() => {
      finish(() => reject(new Error('腾讯云验证码初始化超时，请检查 CaptchaAppId、域名白名单和服务状态')))
    }, 12000)
    const captcha = new Captcha(appId, (res: { ret: number; ticket?: string; randstr?: string; errorCode?: number; errorMessage?: string }) => {
      if (res.ret === 0 && res.ticket && res.randstr && !res.errorCode && !res.ticket.startsWith('trerror_')) {
        const ticket = res.ticket
        const randstr = res.randstr
        finish(() => resolve({ ticket, randstr }))
      } else {
        finish(() => reject(new Error(res.errorMessage || '人机验证已取消或未通过')))
      }
    }, { userLanguage: 'zh-cn' })
    try {
      captcha.show()
    } catch (error) {
      finish(() => reject(error instanceof Error ? error : new Error('腾讯云验证码启动失败')))
    }
  })
}

function logout() {
  localStorage.removeItem(tokenKey)
  profile.value = null
  void router.replace({ name: 'login' })
}

function accountLabel() {
  if (mode.value !== 'register') return '手机号 / 邮箱 / 账号'
  if (setting.value.registrationType === 'MOBILE') return '手机号'
  if (setting.value.registrationType === 'EMAIL') return '邮箱'
  return '手机号 / 邮箱 / 账号'
}

function accountPlaceholder() {
  if (mode.value !== 'register') return '输入手机号、邮箱或账号'
  if (setting.value.registrationType === 'MOBILE') return '输入手机号'
  if (setting.value.registrationType === 'EMAIL') return '输入邮箱'
  return '输入账号'
}

function needCode() {
  return mode.value === 'forgot' || mode.value === 'login' || (mode.value === 'register' && setting.value.registrationType === 'MOBILE')
}

async function sendCode() {
  if (!account.value.trim() || codeSending.value || countdown.value > 0) return
  if (mode.value !== 'login' && !/^1[3-9]\d{9}$/.test(account.value.trim())) {
    errorMessage.value = '短信验证码仅支持手机号'
    return
  }
  if (captchaChallenge.value.enabled && !captchaDone.value) {
    const passed = await completeCaptcha()
    if (!passed) return
  }
  codeSending.value = true
  errorMessage.value = ''
  codeMessage.value = ''
  try {
    codeMessage.value = await sendH5LoginSms(account.value.trim(), captchaTicket.value, captchaRandstr.value, mode.value)
    resetCaptcha()
    startCountdown()
  } catch (error) {
    resetCaptcha()
    errorMessage.value = getApiErrorMessage(error)
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
</script>

<template>
  <main class="page page-pad">
    <section v-if="profile" class="profile liquid-surface">
      <div class="avatar">{{ profile?.nickname?.slice(0, 1) || '喜' }}</div>
      <div>
        <h1>{{ profile?.nickname || '游客用户' }}</h1>
        <p>{{ profile ? `${profile.groupName || '默认会员'} · 余额 ¥${Number(profile.balance || 0).toFixed(2)}` : '登录后查看订单、卡密记录和余额。' }}</p>
      </div>
    </section>

    <section v-if="!profile" class="login-panel liquid-surface">
      <div class="login-head">
        <div>
          <h2>{{ modeTitle }}</h2>
          <p>{{ modeHint }}</p>
        </div>
      </div>
      <div class="mode-tabs">
        <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button type="button" :class="{ active: mode === 'register' }" :disabled="!setting.registrationEnabled" @click="switchMode('register')">注册</button>
        <button type="button" :class="{ active: mode === 'forgot' }" @click="switchMode('forgot')">找回</button>
      </div>
      <p v-if="!setting.registrationEnabled" class="notice danger">当前系统暂未开放新用户注册，已有用户仍可登录。</p>
      <label>
        <span>{{ accountLabel() }}</span>
        <input v-model.trim="account" inputmode="email" autocomplete="username" :placeholder="accountPlaceholder()" />
      </label>
      <label>
        <span>{{ mode === 'forgot' ? '新密码' : '登录密码' }}</span>
        <input v-model="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :placeholder="mode === 'login' ? '请输入登录密码' : '至少 6 位密码'" />
      </label>
      <label v-if="mode === 'register' || mode === 'forgot'">
        <span>{{ mode === 'forgot' ? '确认新密码' : '确认密码' }}</span>
        <input v-model="confirmPassword" type="password" autocomplete="new-password" placeholder="请再次输入密码" />
      </label>
      <label v-if="needCode()">
        <span>短信验证码</span>
        <div class="code-entry">
          <input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入短信验证码" />
          <button type="button" :disabled="codeSending || countdown > 0 || !account.trim()" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s` : codeSending ? '获取中' : '获取验证码' }}
          </button>
        </div>
      </label>
      <div v-if="isTurnstileCaptcha" class="turnstile-check" :class="{ done: captchaDone }">
        <div ref="turnstileBoxRef" class="turnstile-box"></div>
        <span>{{ captchaDone ? '人机验证完成' : '请完成人机验证' }}</span>
      </div>
      <button
        v-else
        type="button"
        class="slider-check"
        :class="{ active: captchaChallenge.enabled && !captchaDone, done: captchaDone, idle: !captchaChallenge.enabled }"
        @click="completeCaptcha"
      >
        {{ captchaDone ? '人机验证完成' : captchaChallenge.enabled ? '点击完成人机验证' : '人机验证未启用' }}
      </button>
      <p v-if="codeMessage" class="notice success">{{ codeMessage }}</p>
      <button type="button" class="primary-action" :disabled="loading || !account.trim() || (mode === 'register' && !setting.registrationEnabled) || (needCode() && codeRequiredForSubmit && !code.trim())" @click="login">
        <LoaderCircle v-if="loading" class="spin" :size="16" />
        {{ mode === 'register' ? '注册并登录' : mode === 'forgot' ? '验证身份' : '登录' }}
      </button>
    </section>

    <section v-else class="menu-panel liquid-surface">
      <div class="menu-row"><UserRound :size="17" /><span>{{ profile.mobile || profile.email }}</span></div>
      <button type="button" class="menu-row logout" @click="logout"><LogOut :size="17" /><span>退出登录</span></button>
    </section>

    <p v-if="errorMessage" class="notice danger">{{ errorMessage }}</p>
    <AppTabbar v-if="profile" />
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

h2 {
  margin: 0 0 6px;
  color: rgba(255, 255, 255, 0.92);
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

.login-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  padding: 4px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.055);
}

.mode-tabs button {
  height: 36px;
  color: rgba(255, 255, 255, 0.68);
  border: 0;
  border-radius: 14px;
  background: transparent;
}

.mode-tabs button.active {
  color: #06100e;
  background: rgba(223, 255, 246, 0.92);
  font-weight: 800;
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

.code-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.code-entry button {
  height: 44px;
  padding: 0 12px;
  color: #06100e;
  border: 0;
  border-radius: 16px;
  background: rgba(223, 255, 246, 0.92);
  font-weight: 700;
  white-space: nowrap;
}

.code-entry button:disabled {
  opacity: 0.62;
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

.slider-check {
  min-height: 44px;
  color: rgba(255, 255, 255, 0.72);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.055);
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease, box-shadow 160ms ease;
}

.slider-check.active {
  color: #ffffff;
  border-color: rgba(116, 168, 255, 0.58);
  background: linear-gradient(135deg, rgba(61, 141, 255, 0.9), rgba(181, 125, 255, 0.82));
  box-shadow: 0 12px 26px rgba(86, 145, 255, 0.22);
}

.slider-check.done {
  color: #b9ffe9;
  border-color: rgba(0, 255, 195, 0.35);
  background: rgba(0, 255, 195, 0.12);
}

.turnstile-check {
  display: grid;
  gap: 10px;
  justify-items: center;
  min-height: 44px;
  padding: 12px;
  color: rgba(255, 255, 255, 0.72);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.055);
  font-weight: 700;
}

.turnstile-check.done {
  color: #b9ffe9;
  border-color: rgba(0, 255, 195, 0.35);
  background: rgba(0, 255, 195, 0.12);
}

.turnstile-box {
  min-height: 65px;
  display: grid;
  place-items: center;
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

.notice.success {
  color: #00ffc3;
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
