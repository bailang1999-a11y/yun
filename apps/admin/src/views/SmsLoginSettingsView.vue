<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MessageSquareText, Save, ShieldCheck } from 'lucide-vue-next'
import { fetchSmsLoginSetting, updateSmsLoginSetting } from '../api/smsLogin'
import type { SmsLoginSettingPayload } from '../types/operations'

const loading = ref(false)
const saving = ref(false)

const providerOptions = [
  { label: '腾讯云短信', value: 'TENCENT' },
  { label: '阿里云短信', value: 'ALIYUN' },
  { label: '通用 HTTP', value: 'GENERIC' }
]

const form = reactive<SmsLoginSettingPayload>({
  enabled: false,
  adminLoginEnabled: false,
  h5LoginEnabled: false,
  webLoginEnabled: false,
  provider: 'TENCENT',
  adminMobile: '',
  codeLength: 6,
  ttlSeconds: 300,
  cooldownSeconds: 60,
  maxAttempts: 5,
  genericConfig: {
    url: '',
    method: 'POST',
    content_type: 'application/json; charset=UTF-8',
    body_template: '{"mobile":"{mobile}","code":"{code}","content":"{content}"}',
    success_keyword: ''
  },
  tencentConfig: {
    secret_id: '',
    secret_key: '',
    sdk_app_id: '',
    sign_name: '',
    template_id: '',
    region: 'ap-guangzhou',
    template_param_json: '["{code}"]'
  },
  aliyunConfig: {
    access_key_id: '',
    access_key_secret: '',
    sign_name: '',
    template_code: '',
    region: 'cn-hangzhou',
    template_param_json: '{"code":"{code}"}'
  }
})

const activeProviderLabel = computed(() => providerOptions.find((item) => item.value === form.provider)?.label || '短信服务商')

onMounted(loadSetting)

async function loadSetting() {
  loading.value = true
  try {
    const setting = await fetchSmsLoginSetting()
    Object.assign(form, {
      ...setting,
      genericConfig: { ...form.genericConfig, ...setting.genericConfig },
      tencentConfig: { ...form.tencentConfig, ...setting.tencentConfig },
      aliyunConfig: { ...form.aliyunConfig, ...setting.aliyunConfig }
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '短信登录配置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSetting() {
  saving.value = true
  try {
    const next = await updateSmsLoginSetting({
      ...form,
      adminMobile: form.adminMobile.trim(),
      codeLength: Number(form.codeLength) || 6,
      ttlSeconds: Number(form.ttlSeconds) || 300,
      cooldownSeconds: Number(form.cooldownSeconds) || 60,
      maxAttempts: Number(form.maxAttempts) || 5,
      genericConfig: trimMap(form.genericConfig),
      tencentConfig: trimMap(form.tencentConfig),
      aliyunConfig: trimMap(form.aliyunConfig)
    })
    Object.assign(form, next)
    ElMessage.success('短信验证登录配置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function trimMap(value: Record<string, string>) {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, String(item ?? '').trim()]))
}
</script>

<template>
  <section class="sms-login-shell">
    <article class="sms-hero liquid-admin-panel">
      <div class="hero-icon"><ShieldCheck :size="22" /></div>
      <div>
        <p>Login Verification</p>
        <h1>短信验证登录</h1>
        <span>首次部署默认关闭。配置服务商并开启后，后台、H5、Web 登录会按开关校验短信验证码。</span>
      </div>
    </article>

    <section class="sms-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>登录校验开关</h2>
            <span>总开关关闭时，三端登录都不会要求短信验证码。</span>
          </div>
          <MessageSquareText :size="18" />
        </div>

        <el-form v-loading="loading" label-position="top">
          <el-form-item label="短信验证登录总开关">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" />
          </el-form-item>
          <div class="switch-grid">
            <label>
              <strong>后台管理员登录</strong>
              <span>开启后后台登录需要验证码</span>
              <el-switch v-model="form.adminLoginEnabled" />
            </label>
            <label>
              <strong>H5 用户端登录</strong>
              <span>开启后 H5 登录/注册需要验证码</span>
              <el-switch v-model="form.h5LoginEnabled" />
            </label>
            <label>
              <strong>Web 用户端登录</strong>
              <span>开启后 Web 登录/注册需要验证码</span>
              <el-switch v-model="form.webLoginEnabled" />
            </label>
          </div>

          <el-form-item label="管理员接收验证码手机号">
            <el-input v-model="form.adminMobile" placeholder="后台管理员登录验证码发送到这个手机号" />
          </el-form-item>

          <div class="number-grid">
            <el-form-item label="验证码位数">
              <el-input-number v-model="form.codeLength" :min="4" :max="8" controls-position="right" />
            </el-form-item>
            <el-form-item label="有效期（秒）">
              <el-input-number v-model="form.ttlSeconds" :min="60" :max="1800" controls-position="right" />
            </el-form-item>
            <el-form-item label="发送间隔（秒）">
              <el-input-number v-model="form.cooldownSeconds" :min="10" :max="300" controls-position="right" />
            </el-form-item>
            <el-form-item label="最大错误次数">
              <el-input-number v-model="form.maxAttempts" :min="1" :max="10" controls-position="right" />
            </el-form-item>
          </div>
        </el-form>
      </article>

      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>短信服务商</h2>
            <span>当前使用：{{ activeProviderLabel }}</span>
          </div>
        </div>
        <el-form label-position="top">
          <el-form-item label="服务商">
            <el-segmented v-model="form.provider" :options="providerOptions" />
          </el-form-item>

          <template v-if="form.provider === 'TENCENT'">
            <div class="config-grid">
              <el-form-item label="SecretId"><el-input v-model="form.tencentConfig.secret_id" /></el-form-item>
              <el-form-item label="SecretKey"><el-input v-model="form.tencentConfig.secret_key" type="password" show-password /></el-form-item>
              <el-form-item label="SdkAppId"><el-input v-model="form.tencentConfig.sdk_app_id" /></el-form-item>
              <el-form-item label="短信签名"><el-input v-model="form.tencentConfig.sign_name" /></el-form-item>
              <el-form-item label="模板 ID"><el-input v-model="form.tencentConfig.template_id" /></el-form-item>
              <el-form-item label="地域"><el-input v-model="form.tencentConfig.region" placeholder="ap-guangzhou" /></el-form-item>
            </div>
            <el-form-item label="模板参数 JSON">
              <el-input v-model="form.tencentConfig.template_param_json" placeholder='["{code}"]' />
            </el-form-item>
          </template>

          <template v-else-if="form.provider === 'ALIYUN'">
            <div class="config-grid">
              <el-form-item label="AccessKeyId"><el-input v-model="form.aliyunConfig.access_key_id" /></el-form-item>
              <el-form-item label="AccessKeySecret"><el-input v-model="form.aliyunConfig.access_key_secret" type="password" show-password /></el-form-item>
              <el-form-item label="短信签名"><el-input v-model="form.aliyunConfig.sign_name" /></el-form-item>
              <el-form-item label="模板 Code"><el-input v-model="form.aliyunConfig.template_code" /></el-form-item>
              <el-form-item label="地域"><el-input v-model="form.aliyunConfig.region" placeholder="cn-hangzhou" /></el-form-item>
            </div>
            <el-form-item label="模板参数 JSON">
              <el-input v-model="form.aliyunConfig.template_param_json" placeholder='{"code":"{code}"}' />
            </el-form-item>
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
      <span>敏感配置仅保存在后端，用户端不会返回 SecretKey 或 AccessKey。</span>
      <el-button type="primary" :icon="Save" :loading="saving" @click="saveSetting">保存配置</el-button>
    </div>
  </section>
</template>

<style scoped>
.sms-login-shell {
  display: grid;
  gap: 16px;
}

.sms-hero,
.panel,
.save-bar {
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.sms-hero {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 22px;
}

.hero-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 16px;
  color: #c7fff3;
  background: rgba(20, 184, 166, 0.16);
  border: 1px solid rgba(94, 234, 212, 0.28);
}

.sms-hero p,
.sms-hero h1 {
  margin: 0;
}

.sms-hero h1 {
  margin: 4px 0 8px;
  color: rgba(255, 255, 255, 0.94);
  font-size: 28px;
}

.sms-hero p,
.sms-hero span,
.panel-head span,
.save-bar span,
.switch-grid span {
  color: rgba(255, 255, 255, 0.56);
}

.sms-grid {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 16px;
}

.panel {
  padding: 18px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0 0 4px;
  color: rgba(255, 255, 255, 0.92);
  font-size: 18px;
}

.switch-grid,
.number-grid,
.config-grid {
  display: grid;
  gap: 12px;
}

.switch-grid {
  margin: 10px 0 16px;
}

.switch-grid label {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 12px;
  align-items: center;
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.09);
}

.switch-grid strong {
  color: rgba(255, 255, 255, 0.86);
}

.switch-grid span {
  grid-column: 1;
  font-size: 12px;
}

.number-grid,
.config-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.save-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
}

@media (max-width: 1180px) {
  .sms-grid,
  .number-grid,
  .config-grid {
    grid-template-columns: 1fr;
  }
}
</style>
