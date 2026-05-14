<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { FlaskConical, Save, ShieldCheck } from 'lucide-vue-next'
import { fetchCaptchaSetting, testCaptchaSetting, updateCaptchaSetting } from '../api/captcha'
import type { CaptchaSettingPayload } from '../types/operations'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)

const providerOptions = [
  { label: '腾讯云验证码', value: 'TENCENT' },
  { label: '通用 HTTP 校验', value: 'GENERIC' }
]

const form = reactive<CaptchaSettingPayload>({
  enabled: false,
  adminLoginEnabled: false,
  h5LoginEnabled: false,
  webLoginEnabled: false,
  provider: 'TENCENT',
  tencentConfig: {
    secret_id: '',
    secret_key: '',
    captcha_app_id: '',
    app_secret_key: '',
    region: 'ap-guangzhou',
    scene: 'login'
  },
  genericConfig: {
    url: '',
    method: 'POST',
    content_type: 'application/json; charset=UTF-8',
    body_template: '{"ticket":"{ticket}","randstr":"{randstr}","ip":"{ip}"}',
    success_keyword: ''
  }
})

const activeProviderLabel = computed(() => providerOptions.find((item) => item.value === form.provider)?.label || '验证码服务商')
const needProviderConfig = computed(() => form.enabled && (form.adminLoginEnabled || form.h5LoginEnabled || form.webLoginEnabled))

onMounted(loadSetting)

async function loadSetting() {
  loading.value = true
  try {
    const setting = await fetchCaptchaSetting()
    Object.assign(form, {
      ...setting,
      tencentConfig: { ...form.tencentConfig, ...setting.tencentConfig },
      genericConfig: { ...form.genericConfig, ...setting.genericConfig }
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '人机验证配置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSetting() {
  const message = validateSetting()
  if (message) {
    ElMessage.warning(message)
    return
  }
  saving.value = true
  try {
    const next = await updateCaptchaSetting({
      ...form,
      tencentConfig: trimMap(form.tencentConfig),
      genericConfig: trimMap(form.genericConfig)
    })
    Object.assign(form, {
      ...next,
      tencentConfig: { ...form.tencentConfig, ...next.tencentConfig },
      genericConfig: { ...form.genericConfig, ...next.genericConfig }
    })
    ElMessage.success('人机验证配置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testSetting() {
  const message = validateSetting()
  if (message) {
    ElMessage.warning(message)
    return
  }
  testing.value = true
  try {
    const result = await testCaptchaSetting(normalizedPayload())
    ElMessage.success(result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试失败')
  } finally {
    testing.value = false
  }
}

function normalizedPayload(): CaptchaSettingPayload {
  return {
    ...form,
    tencentConfig: trimMap(form.tencentConfig),
    genericConfig: trimMap(form.genericConfig)
  }
}

function validateSetting() {
  if (!needProviderConfig.value) return ''
  if (form.provider === 'GENERIC') {
    return form.genericConfig.url.trim() ? '' : '通用 HTTP 校验请求地址不能为空'
  }
  const requiredFields = [
    ['secret_id', '腾讯云 SecretId（AKID 开头）'],
    ['secret_key', '腾讯云 SecretKey'],
    ['captcha_app_id', '验证码 CaptchaAppId'],
    ['app_secret_key', '验证码 AppSecretKey']
  ] as const
  const missing = requiredFields.find(([key]) => !form.tencentConfig[key]?.trim())
  return missing ? `腾讯云 ${missing[1]} 不能为空` : ''
}

function trimMap(value: Record<string, string>) {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, String(item ?? '').trim()]))
}
</script>

<template>
  <section class="captcha-shell">
    <article class="captcha-hero liquid-admin-panel">
      <div class="hero-icon"><ShieldCheck :size="22" /></div>
      <div>
        <p>Human Verification</p>
        <h1>人机验证配置</h1>
        <span>首次部署默认关闭。配置腾讯云验证码后，可让后台、H5、Web 登录注册找回都走真实人机校验。</span>
      </div>
    </article>

    <section class="captcha-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>启用范围</h2>
            <span>总开关关闭时，三端登录都不会要求人机验证。</span>
          </div>
          <ShieldCheck :size="18" />
        </div>

        <el-form v-loading="loading" label-position="top">
          <el-form-item label="人机验证总开关">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" />
          </el-form-item>
          <div class="switch-grid">
            <label>
              <strong>后台管理员登录</strong>
              <span>后台登录前弹出真实验证码</span>
              <el-switch v-model="form.adminLoginEnabled" />
            </label>
            <label>
              <strong>H5 用户端</strong>
              <span>H5 登录/注册/找回密码校验</span>
              <el-switch v-model="form.h5LoginEnabled" />
            </label>
            <label>
              <strong>Web 用户端</strong>
              <span>Web 登录/注册/找回密码校验</span>
              <el-switch v-model="form.webLoginEnabled" />
            </label>
          </div>
        </el-form>
      </article>

      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>验证码服务商</h2>
            <span>当前使用：{{ activeProviderLabel }}</span>
          </div>
        </div>

        <el-form label-position="top">
          <el-form-item label="服务商">
            <el-segmented v-model="form.provider" :options="providerOptions" />
          </el-form-item>

          <template v-if="form.provider === 'TENCENT'">
            <div v-if="needProviderConfig" class="config-tip">
              <strong>启用后必填：</strong>
              <span>腾讯云 SecretId 与 SecretKey 来自 CAM「API 密钥管理」；验证码 CaptchaAppId 与 AppSecretKey 来自验证码应用。不要把账号 APPID 填到 SecretId。</span>
            </div>
            <div class="config-grid">
              <el-form-item label="腾讯云 SecretId（AKID 开头）">
                <el-input v-model="form.tencentConfig.secret_id" placeholder="来自访问管理 CAM / API 密钥管理，不是账号 APPID" />
              </el-form-item>
              <el-form-item label="腾讯云 SecretKey">
                <el-input v-model="form.tencentConfig.secret_key" type="password" show-password placeholder="与上方 SecretId 同一条密钥对应" />
              </el-form-item>
              <el-form-item label="验证码 CaptchaAppId">
                <el-input v-model="form.tencentConfig.captcha_app_id" placeholder="例如：192246501" />
              </el-form-item>
              <el-form-item label="验证码 AppSecretKey">
                <el-input v-model="form.tencentConfig.app_secret_key" type="password" show-password placeholder="验证码应用详情里的 AppSecretKey" />
              </el-form-item>
              <el-form-item label="地域"><el-input v-model="form.tencentConfig.region" placeholder="ap-guangzhou" /></el-form-item>
              <el-form-item label="场景"><el-input v-model="form.tencentConfig.scene" placeholder="login" /></el-form-item>
            </div>
          </template>

          <template v-else>
            <div class="config-grid">
              <el-form-item label="请求地址"><el-input v-model="form.genericConfig.url" /></el-form-item>
              <el-form-item label="请求方式"><el-input v-model="form.genericConfig.method" placeholder="POST / GET" /></el-form-item>
              <el-form-item label="Content-Type"><el-input v-model="form.genericConfig.content_type" /></el-form-item>
              <el-form-item label="成功关键字"><el-input v-model="form.genericConfig.success_keyword" placeholder="响应中包含该关键字即成功，可空" /></el-form-item>
            </div>
            <el-form-item label="请求 Body 模板">
              <el-input v-model="form.genericConfig.body_template" type="textarea" :rows="4" />
            </el-form-item>
          </template>
        </el-form>
      </article>
    </section>

    <div class="save-bar liquid-admin-panel">
      <div>
        <strong>保存后立即影响登录校验</strong>
        <span>启用前请确认服务商参数完整，否则会导致对应端登录失败。</span>
      </div>
      <el-button type="primary" :loading="saving" @click="saveSetting">
        <Save :size="16" />
        保存配置
      </el-button>
      <el-button :loading="testing" @click="testSetting">
        <FlaskConical :size="16" />
        测试配置
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.captcha-shell {
  display: grid;
  gap: 18px;
}

.captcha-hero,
.save-bar,
.panel {
  border-radius: 24px;
}

.captcha-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 22px;
}

.hero-icon {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  color: #071512;
  border-radius: 18px;
  background: linear-gradient(135deg, #00ffc3, #e6fff8);
}

.captcha-hero p,
.captcha-hero h1,
.captcha-hero span,
.panel h2,
.panel span,
.save-bar strong,
.save-bar span {
  margin: 0;
}

.captcha-hero p {
  color: rgba(255, 255, 255, 0.5);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.captcha-hero h1 {
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.94);
  font-size: 28px;
}

.captcha-hero span,
.panel span,
.save-bar span {
  color: rgba(255, 255, 255, 0.56);
}

.captcha-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.85fr) minmax(420px, 1.15fr);
  gap: 18px;
}

.panel {
  padding: 20px;
}

.panel-head,
.save-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel-head {
  margin-bottom: 18px;
}

.panel h2 {
  color: rgba(255, 255, 255, 0.92);
  font-size: 18px;
}

.switch-grid,
.config-grid {
  display: grid;
  gap: 12px;
}

.switch-grid label {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 12px;
  align-items: center;
  padding: 14px;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.055);
}

.switch-grid strong {
  color: rgba(255, 255, 255, 0.88);
}

.switch-grid span {
  grid-column: 1 / 2;
  font-size: 12px;
}

.config-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.config-tip {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 12px;
  margin-bottom: 12px;
  color: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(56, 189, 248, 0.2);
  border-radius: 14px;
  background: rgba(14, 165, 233, 0.1);
  font-size: 13px;
  line-height: 1.6;
}

.config-tip strong {
  color: rgba(147, 197, 253, 0.95);
  white-space: nowrap;
}

.save-bar {
  padding: 16px 18px;
}

.save-bar div {
  display: grid;
  gap: 4px;
}

@media (max-width: 1120px) {
  .captcha-grid {
    grid-template-columns: 1fr;
  }
}
</style>
