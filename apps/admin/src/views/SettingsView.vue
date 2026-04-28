<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw, Save } from 'lucide-vue-next'
import { fetchSettings, updateSettings } from '../api/admin'
import type { SystemSetting } from '../types/operations'

const loading = ref(false)
const saving = ref(false)
const form = reactive<SystemSetting>({
  siteName: '喜易云',
  logoUrl: '',
  customerService: '',
  paymentMode: 'MOCK',
  autoRefundEnabled: true,
  smsProvider: 'TENCENT',
  smsEnabled: false,
  upstreamSyncSeconds: 30,
  autoShelfEnabled: true,
  autoPriceEnabled: false,
  notificationReceivers: { ops: 'ops@example.com' }
})

onMounted(loadSettings)

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
        <el-form-item label="Logo 地址">
          <el-input v-model="form.logoUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="客服信息">
          <el-input v-model="form.customerService" type="textarea" :rows="3" />
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
.settings-form :deep(.el-input-number) {
  width: 100%;
}

.save-bar {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
}
</style>
