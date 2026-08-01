<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Key, KeyRound, LoaderCircle, LogOut, UserRound } from 'lucide-vue-next'
import { getApiErrorMessage } from '../api/client'
import { authH5, changeH5Password, fetchH5AltchaChallenge, fetchH5CaptchaChallenge, fetchH5Me, fetchH5MemberApi, fetchH5Settings, saveH5MemberApi, sendH5LoginSms } from '../api/h5'
import AppTabbar from '../components/AppTabbar.vue'
import type { CaptchaChallenge, H5SystemSetting, UserProfile } from '../types/h5'
import { formatMoney } from '../utils/formatters'

const tokenKey = 'xiyiyun_h5_token'
const route = useRoute()
const router = useRouter()
const account = ref('')
const code = ref('')
const password = ref('')
const confirmPassword = ref('')
const username = ref('')
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
const isAltchaCaptcha = computed(() => captchaChallenge.value.enabled && captchaChallenge.value.provider === 'ALTCHA')
const altchaChallengeJson = ref('')

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
  if (mode.value === 'register' && username.value.trim() && !/^[a-z0-9_-]{1,12}$/.test(username.value.trim())) {
    errorMessage.value = '用户名只能包含小写字母、数字、下划线和连字符，且不超过 12 个字符'
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
      mode: mode.value,
      username: mode.value === 'register' ? (username.value.trim() || undefined) : undefined
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
  username.value = ''
  resetCaptcha()
}

async function completeCaptcha() {
  try {
    if (!captchaChallenge.value.enabled) {
      captchaDone.value = true
      errorMessage.value = ''
      return true
    }
    if (captchaChallenge.value.provider === 'ALTCHA') {
      // Altcha 由 widget 自动完成，completeCaptcha 时检查是否已有票据
      if (captchaDone.value) return true
      errorMessage.value = '请等待人机验证完成'
      return false
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
  if (isAltchaCaptcha.value) void fetchH5AltchaChallenge().then(j => { altchaChallengeJson.value = j })
}

async function loadCaptchaChallenge() {
  try {
    captchaChallenge.value = await fetchH5CaptchaChallenge('h5')
    if (!captchaChallenge.value.enabled) {
      captchaDone.value = true
    } else if (captchaChallenge.value.provider === 'TURNSTILE') {
      void renderTurnstileWidget()
    } else if (captchaChallenge.value.provider === 'ALTCHA') {
      altchaChallengeJson.value = await fetchH5AltchaChallenge()
    }
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

// ──────────── 修改密码 ────────────
const showPwdDialog = ref(false)
const pwdSaving = ref(false)
const pwdForm = ref({ current: '', next: '', confirm: '' })
const pwdError = ref('')

async function submitChangePassword() {
  pwdError.value = ''
  if (!pwdForm.value.current) { pwdError.value = '请输入当前密码'; return }
  if (pwdForm.value.next.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (pwdForm.value.next !== pwdForm.value.confirm) { pwdError.value = '两次输入不一致'; return }
  pwdSaving.value = true
  try {
    await changeH5Password(pwdForm.value.current, pwdForm.value.next, pwdForm.value.confirm)
    showPwdDialog.value = false
    pwdForm.value = { current: '', next: '', confirm: '' }
    errorMessage.value = ''
    alert('密码已修改，请重新登录')
    logout()
  } catch (err) {
    pwdError.value = getApiErrorMessage(err)
  } finally {
    pwdSaving.value = false
  }
}

// ──────────── API 配置 ────────────
const showApiDialog = ref(false)
const apiInfo = ref<{ appKey: string; appSecret: string; callbackUrl: string; status: string; dailyLimit: number } | null>(null)
const apiLoading = ref(false)
const apiSaving = ref(false)
const callbackUrl = ref('')
const apiError = ref('')
const apiMessage = ref('')

async function openApiDialog() {
  showApiDialog.value = true
  apiLoading.value = true
  apiError.value = ''
  apiMessage.value = ''
  try {
    apiInfo.value = await fetchH5MemberApi()
    callbackUrl.value = apiInfo.value?.callbackUrl || ''
  } catch {
    apiInfo.value = null
  } finally {
    apiLoading.value = false
  }
}

function isHttpUrl(value: string) {
  if (!value.trim()) return true
  try {
    const url = new URL(value.trim())
    return Boolean(url.hostname) && (url.protocol === 'http:' || url.protocol === 'https:')
  } catch {
    return false
  }
}

async function submitMemberApi() {
  apiError.value = ''
  apiMessage.value = ''
  if (!isHttpUrl(callbackUrl.value)) {
    apiError.value = '请输入有效的 http 或 https 回调地址'
    return
  }
  apiSaving.value = true
  try {
    apiInfo.value = await saveH5MemberApi(callbackUrl.value.trim())
    callbackUrl.value = apiInfo.value?.callbackUrl || ''
    apiMessage.value = '回调地址已保存'
  } catch (error) {
    apiError.value = getApiErrorMessage(error)
  } finally {
    apiSaving.value = false
  }
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
        <p>{{ profile ? `${profile.groupName || '默认会员'} · 余额 ¥${formatMoney(profile.balance || 0)}` : '登录后查看订单、卡密记录和余额。' }}</p>
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
      <label v-if="mode === 'register'">
        <span>用户名（选填）</span>
        <input v-model.trim="username" type="text" autocomplete="username" maxlength="12" placeholder="2-12位，仅小写字母、数字、-、_" />
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
      <div v-else-if="isAltchaCaptcha" class="altcha-check">
        <altcha-widget
          v-if="altchaChallengeJson"
          :challenge="altchaChallengeJson"
          auto="onload"
          configuration='{"hideFooter":true}'
          @statechange="(e: CustomEvent) => {
            if (e.detail?.state === 'verified') {
              captchaTicket = e.detail.payload || ''
              captchaDone = true
            }
          }"
        />
        <span>{{ captchaDone ? '人机验证完成 ✓' : '验证中...' }}</span>
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
      <button type="button" class="menu-row" @click="showPwdDialog = true"><Key :size="17" /><span>修改密码</span></button>
      <button type="button" class="menu-row" @click="openApiDialog"><KeyRound :size="17" /><span>API 配置</span></button>
      <button type="button" class="menu-row logout" @click="logout"><LogOut :size="17" /><span>退出登录</span></button>
    </section>

    <!-- 修改密码弹窗 -->
    <div v-if="showPwdDialog" class="dialog-mask" @click.self="showPwdDialog = false">
      <div class="dialog liquid-surface">
        <h3>修改密码</h3>
        <label><span>当前密码</span><input v-model="pwdForm.current" type="password" placeholder="请输入当前密码" /></label>
        <label><span>新密码</span><input v-model="pwdForm.next" type="password" placeholder="至少 6 位" /></label>
        <label><span>确认新密码</span><input v-model="pwdForm.confirm" type="password" placeholder="再次输入新密码" /></label>
        <p v-if="pwdError" class="notice danger">{{ pwdError }}</p>
        <div class="dialog-footer">
          <button type="button" class="btn-cancel" @click="showPwdDialog = false">取消</button>
          <button type="button" class="btn-primary" :disabled="pwdSaving" @click="submitChangePassword">
            <LoaderCircle v-if="pwdSaving" class="spin" :size="14" />
            {{ pwdSaving ? '保存中' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- API 配置弹窗 -->
    <div v-if="showApiDialog" class="dialog-mask" @click.self="showApiDialog = false">
      <div class="dialog liquid-surface">
        <h3>API 配置</h3>
        <p v-if="apiLoading" class="notice">加载中…</p>
        <template v-else-if="apiInfo && apiInfo.status === 'ENABLED'">
          <label><span>用户 ID</span><input :value="profile?.id" readonly /></label>
          <label><span>App Key</span><input :value="apiInfo.appKey" readonly /></label>
          <label><span>App Secret</span><input :value="apiInfo.appSecret" type="password" readonly /></label>
          <label for="api-callback-url">订单状态回调地址（选填）</label>
          <input
            id="api-callback-url"
            v-model="callbackUrl"
            type="url"
            inputmode="url"
            autocomplete="url"
            maxlength="500"
            placeholder="https://example.com/api/order/callback"
            :aria-describedby="apiError ? 'api-callback-help api-callback-error' : 'api-callback-help'"
            :aria-invalid="Boolean(apiError)"
            @input="apiError = ''; apiMessage = ''"
          />
          <p id="api-callback-help" class="api-hint">订单状态变化时通知此地址；留空表示不接收回调。</p>
          <p v-if="apiError" id="api-callback-error" class="notice danger" role="alert">{{ apiError }}</p>
          <p v-if="apiMessage" class="notice success" role="status">{{ apiMessage }}</p>
          <p class="api-hint">每日下单限额：{{ apiInfo.dailyLimit }} 笔</p>
        </template>
        <p v-else class="notice">API 下单功能未开启，请联系平台管理员。</p>
        <div class="dialog-footer">
          <button type="button" class="btn-cancel" :disabled="apiSaving" @click="showApiDialog = false">关闭</button>
          <button v-if="apiInfo && apiInfo.status === 'ENABLED'" type="button" class="btn-primary" :disabled="apiSaving" @click="submitMemberApi">
            <LoaderCircle v-if="apiSaving" class="spin" :size="14" />
            {{ apiSaving ? '保存中' : '保存' }}
          </button>
        </div>
      </div>
    </div>

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

.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: grid;
  place-items: center;
  z-index: 100;
  padding: 16px;
}

.dialog {
  width: 100%;
  max-width: 400px;
  display: grid;
  gap: 12px;
  padding: 20px;
  border-radius: 24px;
}

.dialog h3 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 18px;
}

.dialog-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 4px;
}

.btn-cancel, .btn-primary {
  height: 40px;
  padding: 0 20px;
  border: 0;
  border-radius: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-cancel {
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.07);
}

.btn-primary {
  color: #06100e;
  background: linear-gradient(135deg, #00ffc3, #dffff6);
}

.btn-primary:disabled {
  opacity: 0.6;
}

.api-hint {
  margin: 0;
  color: rgba(255, 255, 255, 0.5);
  font-size: 13px;
}
</style>
