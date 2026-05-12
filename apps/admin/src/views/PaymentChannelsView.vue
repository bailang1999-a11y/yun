<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CreditCard, Plus, RefreshCw, Save, Trash2, WalletCards } from 'lucide-vue-next'
import {
  createPaymentChannel,
  deletePaymentChannel,
  disablePaymentChannel,
  enablePaymentChannel,
  fetchPaymentChannels,
  updatePaymentChannel
} from '../api/paymentChannels'
import type { PaymentChannel, PaymentChannelPayload } from '../types/operations'

const channels = ref<PaymentChannel[]>([])
const loading = ref(false)
const saving = ref(false)
const editingId = ref<PaymentChannel['id']>()

const typeOptions = [
  { label: '余额支付', value: 'BALANCE' },
  { label: '微信支付', value: 'WECHAT' },
  { label: '支付宝', value: 'ALIPAY' },
  { label: '线下转账', value: 'BANK' },
  { label: '自定义通道', value: 'CUSTOM' }
]

const terminalOptions = [
  { label: 'H5', value: 'h5' },
  { label: 'Web', value: 'web' },
  { label: 'API', value: 'api' }
]

const configFieldMap: Record<string, Array<{ key: string; label: string; placeholder: string; sensitive?: boolean; textarea?: boolean }>> = {
  WECHAT: [
    { key: 'app_id', label: '微信 AppID', placeholder: '公众号/小程序/应用 AppID' },
    { key: 'mch_id', label: '微信商户号', placeholder: '微信支付商户号 mch_id' },
    { key: 'api_v3_key', label: 'APIv3 密钥', placeholder: '32 位 APIv3 密钥', sensitive: true },
    { key: 'merchant_serial_no', label: '证书序列号', placeholder: '微信支付商户证书序列号' },
    { key: 'private_key', label: '商户私钥', placeholder: '粘贴 apiclient_key.pem 内容', sensitive: true, textarea: true },
    { key: 'notify_url', label: '支付回调地址', placeholder: 'https://你的域名/api/payment/wechat/notify' },
    { key: 'sandbox', label: '沙箱模式', placeholder: 'true / false' }
  ],
  ALIPAY: [
    { key: 'app_id', label: '支付宝 AppID', placeholder: '支付宝开放平台应用 AppID' },
    { key: 'app_private_key', label: '应用私钥', placeholder: '粘贴应用私钥', sensitive: true, textarea: true },
    { key: 'alipay_public_key', label: '支付宝公钥', placeholder: '粘贴支付宝公钥', sensitive: true, textarea: true },
    { key: 'gateway_url', label: '网关地址', placeholder: 'https://openapi.alipay.com/gateway.do' },
    { key: 'notify_url', label: '支付回调地址', placeholder: 'https://你的域名/api/payment/alipay/notify' },
    { key: 'sandbox', label: '沙箱模式', placeholder: 'true / false' }
  ],
  BANK: [
    { key: 'account_name', label: '户名', placeholder: '收款账户户名' },
    { key: 'bank_name', label: '开户行', placeholder: '开户银行' },
    { key: 'bank_account', label: '银行账号', placeholder: '收款银行账号', sensitive: true },
    { key: 'qr_image_url', label: '收款码地址', placeholder: '收款码图片 URL' }
  ],
  CUSTOM: [
    { key: 'merchant_id', label: '商户号', placeholder: '第三方支付商户号' },
    { key: 'app_id', label: '应用 ID', placeholder: '第三方支付应用 ID' },
    { key: 'api_key', label: '接口密钥', placeholder: '第三方支付接口密钥', sensitive: true },
    { key: 'gateway_url', label: '网关地址', placeholder: '第三方支付网关 URL' },
    { key: 'notify_url', label: '支付回调地址', placeholder: '支付结果回调 URL' }
  ]
}

const form = reactive<PaymentChannelPayload>({
  code: '',
  name: '',
  type: 'CUSTOM',
  terminals: ['h5', 'web'],
  status: 'ENABLED',
  sort: 10,
  config: {},
  remark: ''
})

const enabledCount = computed(() => channels.value.filter((item) => item.status === 'ENABLED').length)
const h5Count = computed(() => channels.value.filter((item) => item.terminals.includes('h5') && item.status === 'ENABLED').length)
const webCount = computed(() => channels.value.filter((item) => item.terminals.includes('web') && item.status === 'ENABLED').length)
const formTitle = computed(() => (editingId.value ? '编辑支付通道' : '新增支付通道'))
const configFields = computed(() => configFieldMap[form.type] || configFieldMap.CUSTOM)

onMounted(loadChannels)

async function loadChannels() {
  loading.value = true
  try {
    channels.value = await fetchPaymentChannels()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '支付通道加载失败')
  } finally {
    loading.value = false
  }
}

function editChannel(row: PaymentChannel) {
  editingId.value = row.id
  form.code = row.code
  form.name = row.name
  form.type = row.type || 'CUSTOM'
  form.terminals = [...row.terminals]
  form.status = row.status || 'ENABLED'
  form.sort = row.sort || 10
  form.config = { ...(row.config || {}) }
  form.remark = row.remark || ''
  ensureConfigDefaults()
}

function resetForm() {
  editingId.value = undefined
  form.code = ''
  form.name = ''
  form.type = 'CUSTOM'
  form.terminals = ['h5', 'web']
  form.status = 'ENABLED'
  form.sort = nextSort()
  form.config = {}
  form.remark = ''
  ensureConfigDefaults()
}

async function saveChannel() {
  if (!form.name.trim() || !form.code.trim()) {
    ElMessage.warning('请填写通道名称和通道编码')
    return
  }
  if (!form.terminals.length) {
    ElMessage.warning('请至少选择一个可用端')
    return
  }

  saving.value = true
  try {
    const payload = {
      ...form,
      code: normalizeCode(form.code),
      name: form.name.trim(),
      sort: Number(form.sort) || nextSort(),
      config: normalizeConfig(form.config || {}),
      remark: form.remark?.trim()
    }
    if (editingId.value) {
      await updatePaymentChannel(editingId.value, payload)
      ElMessage.success('支付通道已更新')
    } else {
      await createPaymentChannel(payload)
      ElMessage.success('支付通道已新增')
    }
    resetForm()
    await loadChannels()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: PaymentChannel) {
  try {
    if (row.status === 'ENABLED') await disablePaymentChannel(row.id)
    else await enablePaymentChannel(row.id)
    ElMessage.success(row.status === 'ENABLED' ? '支付通道已停用' : '支付通道已启用')
    await loadChannels()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

async function removeChannel(row: PaymentChannel) {
  try {
    await ElMessageBox.confirm(`确认删除支付通道「${row.name}」？删除后前台不会再展示该支付方式。`, '删除支付通道', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'xiyiyun-glass-message-box'
    })
    await deletePaymentChannel(row.id)
    if (String(editingId.value) === String(row.id)) resetForm()
    ElMessage.success('支付通道已删除')
    await loadChannels()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

function normalizeCode(value: string) {
  return value.trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '_')
}

function nextSort() {
  return Math.max(0, ...channels.value.map((item) => Number(item.sort) || 0)) + 10
}

function typeLabel(value: string) {
  return typeOptions.find((item) => item.value === value)?.label || value || '自定义通道'
}

function terminalLabel(value: string) {
  return terminalOptions.find((item) => item.value === value)?.label || value
}

function ensureConfigDefaults() {
  const next = { ...(form.config || {}) }
  for (const field of configFields.value) {
    if (next[field.key] === undefined) next[field.key] = ''
  }
  form.config = next
}

function normalizeConfig(config: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(config)
      .map(([key, value]) => [key.trim(), String(value ?? '').trim()])
      .filter(([key]) => key)
  )
}
</script>

<template>
  <section class="payment-channel-shell">
    <article class="payment-hero liquid-admin-panel">
      <div>
        <p>Payment Channels</p>
        <h1>支付通道管理</h1>
        <span>统一管理 H5、Web、API 可用的支付方式，前台下单实时读取这里的启用通道。</span>
      </div>
      <div class="hero-metrics">
        <div><strong>{{ channels.length }}</strong><span>通道总数</span></div>
        <div><strong>{{ enabledCount }}</strong><span>启用中</span></div>
        <div><strong>{{ h5Count }} / {{ webCount }}</strong><span>H5 / Web</span></div>
      </div>
    </article>

    <section class="payment-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>通道列表</h2>
            <span>停用后对应前台不会再展示，也无法继续使用该方式支付</span>
          </div>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadChannels">刷新</el-button>
        </div>

        <el-table v-loading="loading" :data="channels" height="590" class="payment-table" style="width: 100%">
          <el-table-column prop="sort" label="排序" width="78" />
          <el-table-column label="通道" min-width="180">
            <template #default="{ row }">
              <div class="channel-cell">
                <span class="channel-icon"><WalletCards :size="16" /></span>
                <div>
                  <strong>{{ row.name }}</strong>
                  <small>{{ row.code }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="118">
            <template #default="{ row }"><span class="type-chip">{{ typeLabel(row.type) }}</span></template>
          </el-table-column>
          <el-table-column label="可用端" min-width="170">
            <template #default="{ row }">
              <div class="terminal-tags">
                <span v-for="terminal in row.terminals" :key="terminal">{{ terminalLabel(terminal) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <span class="status-pill" :class="{ disabled: row.status !== 'ENABLED' }">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="210" show-overflow-tooltip />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button-group>
                <el-button size="small" @click="editChannel(row)">编辑</el-button>
                <el-button size="small" @click="toggleStatus(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
                <el-button size="small" type="danger" :icon="Trash2" @click="removeChannel(row)">删除</el-button>
              </el-button-group>
            </template>
          </el-table-column>
        </el-table>
      </article>

      <article class="panel editor-panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>{{ formTitle }}</h2>
            <span>编码会作为下单 payMethod 使用</span>
          </div>
          <CreditCard :size="18" />
        </div>

        <el-form label-position="top">
          <el-form-item label="通道名称">
            <el-input v-model="form.name" placeholder="例如：微信支付" />
          </el-form-item>
          <el-form-item label="通道编码">
            <el-input v-model="form.code" placeholder="例如：wechat" @blur="form.code = normalizeCode(form.code)" />
          </el-form-item>
          <el-form-item label="通道类型">
            <el-select v-model="form.type" style="width: 100%" @change="ensureConfigDefaults">
              <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="可用端">
            <el-checkbox-group v-model="form.terminals" class="terminal-checks">
              <el-checkbox-button v-for="item in terminalOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </el-checkbox-button>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="状态与排序">
            <div class="inline-controls">
              <el-switch v-model="form.status" active-value="ENABLED" inactive-value="DISABLED" active-text="启用" inactive-text="停用" />
              <el-input-number v-model="form.sort" :min="0" :step="10" controls-position="right" />
            </div>
          </el-form-item>
          <section v-if="form.type !== 'BALANCE'" class="config-section">
            <div class="config-title">
              <strong>通道配置</strong>
              <span>{{ typeLabel(form.type) }}商户参数</span>
            </div>
            <el-form-item v-for="field in configFields" :key="field.key" :label="field.label">
              <el-input
                v-model="form.config![field.key]"
                :type="field.textarea ? 'textarea' : field.sensitive ? 'password' : 'text'"
                :rows="field.textarea ? 4 : undefined"
                :show-password="field.sensitive && !field.textarea"
                :placeholder="field.placeholder"
              />
            </el-form-item>
          </section>
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="填写接口、费率或使用说明" />
          </el-form-item>
        </el-form>

        <div class="editor-actions">
          <el-button @click="resetForm">清空</el-button>
          <el-button type="primary" :icon="editingId ? Save : Plus" :loading="saving" @click="saveChannel">
            {{ editingId ? '保存修改' : '新增通道' }}
          </el-button>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.payment-channel-shell {
  display: grid;
  gap: 16px;
}

.payment-hero,
.panel {
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.payment-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 22px;
}

.payment-hero p,
.payment-hero h1 {
  margin: 0;
}

.payment-hero p,
.payment-hero span,
.panel-head span {
  color: rgba(255, 255, 255, 0.56);
}

.payment-hero h1 {
  margin: 4px 0 8px;
  font-size: 28px;
  color: rgba(255, 255, 255, 0.94);
}

.hero-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(96px, 1fr));
  gap: 10px;
}

.hero-metrics div {
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.hero-metrics strong {
  display: block;
  color: #fff;
  font-size: 22px;
}

.payment-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
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

.channel-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.channel-icon {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: #bdf5ff;
  background: rgba(21, 184, 214, 0.16);
  border: 1px solid rgba(125, 231, 255, 0.28);
}

.channel-cell strong,
.channel-cell small {
  display: block;
}

.channel-cell strong {
  color: rgba(255, 255, 255, 0.9);
}

.channel-cell small {
  color: rgba(255, 255, 255, 0.48);
}

.type-chip,
.status-pill,
.terminal-tags span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.type-chip {
  color: #fdf4ff;
  background: rgba(168, 85, 247, 0.18);
  border: 1px solid rgba(216, 180, 254, 0.28);
}

.status-pill {
  color: #d8fff1;
  background: rgba(16, 185, 129, 0.18);
  border: 1px solid rgba(110, 231, 183, 0.28);
}

.status-pill.disabled {
  color: #d7dce8;
  background: rgba(148, 163, 184, 0.16);
  border-color: rgba(203, 213, 225, 0.2);
}

.terminal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.terminal-tags span {
  color: #dff6ff;
  background: rgba(59, 130, 246, 0.16);
  border: 1px solid rgba(147, 197, 253, 0.26);
}

.editor-panel :deep(.el-form-item__label) {
  color: rgba(255, 255, 255, 0.72);
}

.terminal-checks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.inline-controls,
.editor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.config-section {
  margin: 12px 0 16px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.config-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.config-title strong {
  color: rgba(255, 255, 255, 0.9);
}

.config-title span {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
}

.editor-actions {
  justify-content: flex-end;
  margin-top: 8px;
}

@media (max-width: 1180px) {
  .payment-grid,
  .payment-hero {
    grid-template-columns: 1fr;
    display: grid;
  }
}
</style>
