<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { ImageUp, RefreshCw, Save, X } from 'lucide-vue-next'
import { fetchSettings, updateSettings } from '../api/operations'
import { fetchUserGroups } from '../api/users'
import type { SystemSetting, UserGroup } from '../types/operations'

const loading = ref(false)
const saving = ref(false)
const userGroups = ref<UserGroup[]>([])
const form = reactive<SystemSetting>({
  siteName: '喜易云',
  logoUrl: '',
  customerService: '',
  companyName: '',
  icpRecordNo: '',
  policeRecordNo: '',
  disclaimer: '',
  paymentMode: 'MOCK',
  autoRefundEnabled: true,
  smsProvider: 'TENCENT',
  smsEnabled: false,
  upstreamSyncSeconds: 30,
  autoShelfEnabled: true,
  autoPriceEnabled: false,
  registrationEnabled: true,
  registrationType: 'MOBILE',
  defaultUserGroupId: '1',
  notificationReceivers: { ops: 'ops@example.com' }
})

onMounted(() => {
  void loadSettings()
  void loadUserGroups()
})

async function loadUserGroups() {
  try {
    userGroups.value = await fetchUserGroups()
  } catch {
    userGroups.value = []
  }
}

async function loadSettings() {
  loading.value = true
  try {
    Object.assign(form, await fetchSettings())
  } catch {
    ElMessage.error('系统设置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  saving.value = true
  try {
    Object.assign(form, await updateSettings({ ...form, notificationReceivers: { ...form.notificationReceivers } }))
    ElMessage.success('系统设置已保存')
  } catch {
    ElMessage.error('系统设置保存失败')
  } finally {
    saving.value = false
  }
}

function fileToDataUrl(file: UploadRawFile) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

async function handleLogoUpload(file: UploadRawFile) {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return false
  }
  if (file.size > 1024 * 1024 * 2) {
    ElMessage.warning('Logo 图片不能超过 2MB')
    return false
  }
  form.logoUrl = await fileToDataUrl(file)
  ElMessage.success('Logo 已选择，保存后生效')
  return false
}

function clearLogo() {
  form.logoUrl = ''
}
</script>

<template>
  <section class="settings-grid" v-loading="loading">
    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>站点与客服</h2>
          <span>展示名称、Logo 和客服说明</span>
        </div>
        <el-button :icon="RefreshCw" :loading="loading" aria-label="刷新系统设置" @click="loadSettings" />
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="站点名称">
          <el-input v-model="form.siteName" placeholder="喜易云" />
        </el-form-item>
        <el-form-item label="站点 Logo">
          <div class="logo-uploader">
            <div class="logo-preview">
              <img v-if="form.logoUrl" :src="form.logoUrl" alt="站点 Logo 预览" />
              <span v-else>{{ form.siteName.slice(0, 1) || '喜' }}</span>
            </div>
            <div class="logo-actions">
              <el-upload accept="image/*" :show-file-list="false" :before-upload="handleLogoUpload">
                <el-button :icon="ImageUp">上传本地 Logo</el-button>
              </el-upload>
              <el-button :icon="X" :disabled="!form.logoUrl" @click="clearLogo">清除</el-button>
              <p>支持 PNG、JPG、WEBP、SVG，建议使用透明背景，大小不超过 2MB。</p>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="客服信息">
          <el-input v-model="form.customerService" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
    </article>

    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>备案与声明</h2>
          <span>前台页脚展示的公司主体、备案号和免责声明</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="公司名称">
          <el-input v-model="form.companyName" placeholder="杭州云创蜗牛科技有限公司" />
        </el-form-item>
        <el-form-item label="ICP备案号">
          <el-input v-model="form.icpRecordNo" placeholder="浙ICP备2024102496号" />
        </el-form-item>
        <el-form-item label="公安备案号">
          <el-input v-model="form.policeRecordNo" placeholder="浙公网安备 33000000000000号" />
        </el-form-item>
        <el-form-item label="免责声明">
          <el-input
            v-model="form.disclaimer"
            type="textarea"
            :rows="3"
            placeholder="本站所有素材来源于网络，如有侵犯到您的知识产权或任何利益，请联系我们删除！"
          />
        </el-form-item>
      </el-form>
    </article>

    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>支付与退款</h2>
          <span>先使用模拟渠道，后续可替换微信/支付宝</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="支付模式">
          <el-select v-model="form.paymentMode">
            <el-option label="模拟支付" value="MOCK" />
            <el-option label="微信 + 支付宝" value="WECHAT_ALIPAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="库存不足自动退款">
          <el-switch v-model="form.autoRefundEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
      </el-form>
    </article>

    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>短信通知</h2>
          <span>订单终态通知与测试发送配置</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="短信渠道">
          <el-select v-model="form.smsProvider">
            <el-option label="腾讯云短信" value="TENCENT" />
            <el-option label="阿里云短信" value="ALIYUN" />
          </el-select>
        </el-form-item>
        <el-form-item label="短信通知">
          <el-switch v-model="form.smsEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
      </el-form>
    </article>

    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>注册设置</h2>
          <span>控制开放方式、验证码校验和默认会员组</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="开放注册">
          <el-switch v-model="form.registrationEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="注册类型">
          <el-radio-group v-model="form.registrationType" class="register-type-group">
            <el-radio-button label="MOBILE">手机号</el-radio-button>
            <el-radio-button label="EMAIL">邮箱</el-radio-button>
            <el-radio-button label="FREE">自由注册</el-radio-button>
          </el-radio-group>
          <p class="form-help">手机号和邮箱注册必须填写验证码，演示验证码为 123456。</p>
        </el-form-item>
        <el-form-item label="新用户默认会员组">
          <el-select v-model="form.defaultUserGroupId" placeholder="选择默认会员组">
            <el-option
              v-for="group in userGroups"
              :key="group.id"
              :label="group.name"
              :value="String(group.id)"
            />
          </el-select>
        </el-form-item>
      </el-form>
    </article>

    <article class="panel">
      <div class="panel-head">
        <div>
          <h2>货源同步</h2>
          <span>上游价格库存轮询与自动处理</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="settings-form">
        <el-form-item label="同步频率（秒）">
          <el-input-number v-model="form.upstreamSyncSeconds" :min="5" :step="5" controls-position="right" />
        </el-form-item>
        <el-form-item label="自动上下架">
          <el-switch v-model="form.autoShelfEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="自动调价">
          <el-switch v-model="form.autoPriceEnabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
      </el-form>
    </article>

    <div class="save-bar">
      <el-button type="primary" :icon="Save" :loading="saving" @click="saveSettings">保存系统设置</el-button>
    </div>
  </section>
</template>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.panel {
  position: relative;
  padding: 18px;
  overflow: hidden;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 13px;
}

.settings-form :deep(.el-select),
.settings-form :deep(.el-input-number),
.register-type-group {
  width: 100%;
}

.register-type-group :deep(.el-radio-button) {
  flex: 1;
}

.register-type-group :deep(.el-radio-button__inner) {
  width: 100%;
}

.form-help {
  margin: 8px 0 0;
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
  line-height: 1.6;
}

.logo-uploader {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(56, 189, 248, 0.22);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.26);
}

.logo-preview {
  display: grid;
  flex: 0 0 88px;
  width: 88px;
  height: 88px;
  place-items: center;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(20, 184, 166, 0.22), rgba(59, 130, 246, 0.18));
  color: rgba(255, 255, 255, 0.88);
  font-size: 28px;
  font-weight: 700;
}

.logo-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  padding: 8px;
}

.logo-actions {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.logo-actions p {
  flex-basis: 100%;
  margin: 0;
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
  line-height: 1.6;
}

.save-bar {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}
</style>
