<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit3, KeyRound, PackageCheck, PlugZap, Plus, RefreshCw, ShieldCheck, Trash2, WalletCards, X } from 'lucide-vue-next'
import {
  createGoodsChannel,
  createSupplier,
  deleteSupplier,
  fetchGoods,
  fetchSuppliers,
  refreshSupplierBalance,
  syncSupplierGoods,
  updateSupplier
} from '../api/admin'
import type { Goods, RemoteGoods, RemoteGoodsSyncResult, Supplier, SupplierCreatePayload } from '../types/operations'

const KASUSHOU_BASE_URL = 'https://你的卡速售域名'

const platformOptions = [
  { label: '自定义供应商', value: 'CUSTOM' },
  { label: '卡速售 2.0', value: 'KASUSHOU_2' }
] as const

const capabilityGroups = [
  { group: '基础', items: ['余额查询'] },
  { group: '商品', items: ['商品分类', '商品列表', '商品详情', '商品调价记录', '商品变更通知', '商品下单模板'] },
  { group: '订单', items: ['订单提交', '订单详情', '订单异步回调', '订单撤单'] },
  { group: '售后', items: ['售后申请', '售后处理回调'] },
  { group: '回调', items: ['商品变更通知', '订单异步回调', '售后处理回调'] }
]

const suppliers = ref<Supplier[]>([])
const goods = ref<Goods[]>([])
const loading = ref(false)
const saving = ref(false)
const operatingId = ref('')
const editingSupplierId = ref<Supplier['id'] | ''>('')
const syncingId = ref<Supplier['id'] | ''>('')
const bindingKey = ref('')
const syncDialogVisible = ref(false)
const syncSupplierName = ref('')
const syncSupplierId = ref<Supplier['id'] | ''>('')
const syncResult = ref<RemoteGoodsSyncResult | null>(null)
const bindingGoodsId = ref<Goods['id'] | ''>('')
const bindForm = reactive({
  priority: 10,
  timeoutSeconds: 30,
  status: 'ENABLED'
})

const syncGoods = computed(() => syncResult.value?.goods ?? [])
const directGoods = computed(() => goods.value.filter((item) => item.deliveryType === 'DIRECT'))

const form = reactive<SupplierCreatePayload>({
  name: '',
  baseUrl: '',
  appKey: '',
  appSecret: '',
  platformType: 'CUSTOM',
  userId: '',
  appId: '',
  apiKey: '',
  callbackUrl: '',
  timeoutSeconds: 15,
  balance: 0,
  status: 'ENABLED',
  remark: ''
})

const isKasushouSelected = computed(() => form.platformType === 'KASUSHOU_2')
const baseUrlLabel = computed(() => (isKasushouSelected.value ? '卡速售API 对接地址' : 'API 地址'))
const formTitle = computed(() => (editingSupplierId.value ? '编辑供应商' : '新增供应商'))
const formSubtitle = computed(() => (editingSupplierId.value ? '更新渠道参数和密钥' : '用于直充渠道、余额预警和自动切换'))

onMounted(() => {
  void loadSuppliers()
  void loadBindingGoods()
})

watch(
  () => form.platformType,
  (platformType) => {
    if (platformType !== 'KASUSHOU_2') return
    if (!form.name.trim()) form.name = '卡速售 2.0'
    if (!form.baseUrl.trim()) form.baseUrl = KASUSHOU_BASE_URL
    if (!form.timeoutSeconds) form.timeoutSeconds = 15
  }
)

function formatMoney(value: Supplier['balance']) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? `¥${numberValue.toFixed(2)}` : '-'
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN')
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function loadSuppliers() {
  loading.value = true
  try {
    suppliers.value = await fetchSuppliers()
  } catch {
    ElMessage.error('供应商列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadBindingGoods() {
  try {
    goods.value = await fetchGoods()
    if (!bindingGoodsId.value) bindingGoodsId.value = directGoods.value[0]?.id ?? ''
  } catch {
    ElMessage.error('本地商品列表加载失败')
  }
}

function isKasushouSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('KASUSHOU') || signature.includes('KASU') || signature.includes('卡速售')
}

function platformLabel(row: Supplier) {
  return isKasushouSupplier(row) ? '卡速售 2.0' : '自定义'
}

function resetForm() {
  editingSupplierId.value = ''
  form.name = ''
  form.baseUrl = ''
  form.appKey = ''
  form.appSecret = ''
  form.platformType = 'CUSTOM'
  form.userId = ''
  form.appId = ''
  form.apiKey = ''
  form.callbackUrl = ''
  form.timeoutSeconds = 15
  form.balance = 0
  form.status = 'ENABLED'
  form.remark = ''
}

function fillSupplierForm(row: Supplier) {
  editingSupplierId.value = row.id
  form.name = row.name || ''
  form.baseUrl = row.baseUrl || ''
  form.appKey = row.appKey || ''
  form.appSecret = ''
  form.platformType = isKasushouSupplier(row) ? 'KASUSHOU_2' : 'CUSTOM'
  form.userId = row.userId || ''
  form.appId = row.appId || ''
  form.apiKey = ''
  form.callbackUrl = row.callbackUrl || ''
  form.timeoutSeconds = row.timeoutSeconds || 15
  form.balance = Number(row.balance) || 0
  form.status = row.status || 'ENABLED'
  form.remark = row.remark || ''
}

function supplierPayload() {
  const kasushouAppId = form.appId?.trim()
  const kasushouSecret = form.apiKey?.trim() || form.appSecret.trim()
  const normalizedAppKey = isKasushouSelected.value
    ? kasushouAppId || form.userId?.trim() || form.appKey.trim()
    : form.appKey.trim()
  const normalizedSecret = isKasushouSelected.value ? kasushouSecret : form.appSecret.trim()
  const normalizedTimeoutSeconds = Number(form.timeoutSeconds) || 15

  return {
    payload: {
      ...form,
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      appKey: normalizedAppKey,
      appSecret: normalizedSecret,
      userId: isKasushouSelected.value ? kasushouAppId : form.userId?.trim(),
      appId: kasushouAppId,
      apiKey: isKasushouSelected.value ? normalizedSecret : form.apiKey?.trim(),
      callbackUrl: isKasushouSelected.value ? '' : form.callbackUrl?.trim(),
      timeoutSeconds: normalizedTimeoutSeconds,
      remark: form.remark?.trim(),
      integrationConfig: isKasushouSelected.value
        ? {
            platformType: 'KASUSHOU_2',
            baseUrl: form.baseUrl.trim(),
            userId: kasushouAppId,
            appId: kasushouAppId,
            callbackUrl: '',
            timeoutSeconds: normalizedTimeoutSeconds
          }
        : undefined
    } as SupplierCreatePayload & { integrationConfig?: Record<string, unknown> },
    normalizedSecret
  }
}

async function submitSupplier() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写供应商名称')
    return
  }
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请填写 API 地址')
    return
  }
  const { payload, normalizedSecret } = supplierPayload()

  if (isKasushouSelected.value && !normalizedSecret && !editingSupplierId.value) {
    ElMessage.warning('请填写密钥')
    return
  }

  saving.value = true
  try {
    if (editingSupplierId.value) {
      await updateSupplier(editingSupplierId.value, payload)
      ElMessage.success('供应商已更新')
    } else {
      await createSupplier(payload)
      ElMessage.success('供应商已新增')
    }
    resetForm()
    await loadSuppliers()
  } catch {
    ElMessage.error(editingSupplierId.value ? '更新供应商失败' : '新增供应商失败')
  } finally {
    saving.value = false
  }
}

async function runOperation(row: Supplier, type: 'balance' | 'delete') {
  operatingId.value = `${type}:${row.id}`
  try {
    if (type === 'balance') {
      await refreshSupplierBalance(row.id)
      ElMessage.success('余额已刷新')
    }
    if (type === 'delete') {
      await deleteSupplier(row.id)
      if (String(editingSupplierId.value) === String(row.id)) resetForm()
      ElMessage.success('供应商已删除')
    }
    await loadSuppliers()
  } catch (error) {
    ElMessage.error(errorMessage(error, '操作失败'))
  } finally {
    operatingId.value = ''
  }
}

async function runGoodsSync(row: Supplier) {
  if (!isKasushouSupplier(row)) return

  syncingId.value = row.id
  try {
    if (!goods.value.length) await loadBindingGoods()
    syncResult.value = null
    syncDialogVisible.value = false
    syncResult.value = await syncSupplierGoods(row.id, { page: 1, limit: 20, cateId: 0, keyword: '' })
    syncSupplierName.value = row.name
    syncSupplierId.value = row.id
    if (!bindingGoodsId.value) bindingGoodsId.value = directGoods.value[0]?.id ?? ''
    syncDialogVisible.value = true
    ElMessage.success('商品同步完成')
    await loadSuppliers()
  } catch (error) {
    syncResult.value = null
    syncDialogVisible.value = false
    ElMessage.error(errorMessage(error, '商品同步失败'))
  } finally {
    syncingId.value = ''
  }
}

async function bindRemoteGoods(row: RemoteGoods) {
  if (!syncSupplierId.value) {
    ElMessage.warning('请先同步供应商商品')
    return
  }
  if (!bindingGoodsId.value) {
    ElMessage.warning('请选择要绑定的本地直充商品')
    return
  }
  if (!row.supplierGoodsId) {
    ElMessage.warning('远端商品缺少商品ID')
    return
  }

  bindingKey.value = row.supplierGoodsId
  try {
    await createGoodsChannel(bindingGoodsId.value, {
      supplierId: syncSupplierId.value,
      supplierGoodsId: row.supplierGoodsId,
      priority: Number(bindForm.priority) || 10,
      timeoutSeconds: Number(bindForm.timeoutSeconds) || 30,
      status: bindForm.status
    })
    ElMessage.success('已绑定为直充渠道')
  } catch {
    ElMessage.error('绑定渠道失败')
  } finally {
    bindingKey.value = ''
  }
}

async function removeSupplier(row: Supplier) {
  try {
    await ElMessageBox.confirm(`确定删除供应商「${row.name}」吗？关联的直充渠道也会一并移除。`, '删除供应商', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await runOperation(row, 'delete')
  } catch {
    // canceled
  }
}
</script>

<template>
  <section class="supplier-grid">
    <article class="create-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>{{ formTitle }}</h2>
          <span>{{ formSubtitle }}</span>
        </div>
        <PlugZap :size="20" />
      </div>

      <el-form :model="form" label-position="top" class="supplier-form">
        <el-form-item label="平台类型">
          <el-select v-model="form.platformType" placeholder="请选择平台类型">
            <el-option v-for="option in platformOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商名称">
          <el-input v-model="form.name" placeholder="例如：星河直充" />
        </el-form-item>
        <el-form-item :label="baseUrlLabel">
          <el-input v-model="form.baseUrl" :placeholder="isKasushouSelected ? KASUSHOU_BASE_URL : 'https://api.example.com'" />
        </el-form-item>
        <template v-if="isKasushouSelected">
          <div class="kasushou-fields">
            <el-form-item label="APPID">
              <el-input v-model="form.appId" placeholder="卡速售 APPID" />
            </el-form-item>
            <el-form-item label="密钥">
              <el-input v-model="form.apiKey" type="password" show-password placeholder="用于签名，保存后仅脱敏展示" />
            </el-form-item>
          </div>
        </template>
        <template v-else>
          <el-form-item label="AppKey">
            <el-input v-model="form.appKey" placeholder="用于鉴权的 Key" />
          </el-form-item>
          <el-form-item label="AppSecret">
            <el-input v-model="form.appSecret" type="password" show-password placeholder="保存后仅脱敏展示" />
          </el-form-item>
        </template>
        <div class="form-actions">
          <el-button type="primary" :icon="editingSupplierId ? Edit3 : Plus" :loading="saving" @click="submitSupplier">
            {{ editingSupplierId ? '保存供应商' : '新增供应商' }}
          </el-button>
          <el-button v-if="editingSupplierId" :icon="X" @click="resetForm">取消编辑</el-button>
        </div>
      </el-form>

      <section v-if="isKasushouSelected" class="integration-card">
        <div class="integration-title">
          <ShieldCheck :size="17" />
          <strong>卡速售接口能力</strong>
        </div>
        <div class="capability-groups">
          <div v-for="group in capabilityGroups" :key="group.group" class="capability-group">
            <span>{{ group.group }}</span>
            <p>{{ group.items.join(' / ') }}</p>
          </div>
        </div>
        <div class="signature-note">
          <div>
            <KeyRound :size="16" />
            <strong>签名规则</strong>
          </div>
          <p>Sign = sha1(Timestamp + 排序后的 JSON Body + 密钥)</p>
          <p>Headers 使用 Sign / Timestamp / APPID</p>
        </div>
      </section>
    </article>

    <article class="table-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>供应商列表</h2>
          <span>{{ suppliers.length }} 个上游</span>
        </div>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadSuppliers">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="suppliers" height="640" style="width: 100%">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="供应商" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="supplier-name">
              <strong>{{ row.name }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="平台" width="130">
          <template #default="{ row }">
            <span class="platform-pill" :data-kasushou="isKasushouSupplier(row)">{{ platformLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="API 地址" min-width="220" show-overflow-tooltip />
        <el-table-column label="鉴权标识" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ isKasushouSupplier(row) ? row.appId || row.userId || row.appKey || '-' : row.appKey || '-' }}</template>
        </el-table-column>
        <el-table-column label="密钥" width="130">
          <template #default="{ row }">{{ isKasushouSupplier(row) ? row.apiKeyMasked || '-' : row.appSecretMasked || '-' }}</template>
        </el-table-column>
        <el-table-column label="余额" width="130">
          <template #default="{ row }">
            <span class="balance" :data-low="Number(row.balance) < 300">{{ formatMoney(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="status-pill" :data-enabled="row.status === 'ENABLED'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最后同步" width="180">
          <template #default="{ row }">{{ formatTime(row.lastSyncAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="300">
          <template #default="{ row }">
            <div class="action-buttons">
              <el-button size="small" :icon="Edit3" @click="fillSupplierForm(row)">编辑</el-button>
              <el-button
                v-if="isKasushouSupplier(row)"
                size="small"
                :icon="PackageCheck"
                :loading="syncingId === row.id"
                @click="runGoodsSync(row)"
              >
                同步商品
              </el-button>
              <el-button
                size="small"
                :icon="WalletCards"
                :loading="operatingId === `balance:${row.id}`"
                @click="runOperation(row, 'balance')"
              >
                余额
              </el-button>
              <el-button
                size="small"
                type="danger"
                :icon="Trash2"
                :loading="operatingId === `delete:${row.id}`"
                @click="removeSupplier(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </article>

    <el-dialog v-model="syncDialogVisible" class="sync-result-dialog" width="980px" align-center>
      <template #header>
        <div class="sync-dialog-title">
          <PackageCheck :size="18" />
          <span>{{ syncSupplierName || '供应商' }}商品同步结果</span>
        </div>
      </template>

      <section class="sync-summary">
        <article>
          <span>同步时间</span>
          <strong>{{ formatTime(syncResult?.syncedAt) }}</strong>
        </article>
        <article>
          <span>商品总数</span>
          <strong>{{ syncResult?.total ?? 0 }}</strong>
        </article>
        <article>
          <span>本次展示</span>
          <strong>{{ syncGoods.length }}</strong>
        </article>
      </section>

      <section class="remote-bind-bar">
        <div>
          <span>绑定到本地直充商品</span>
          <strong>{{ directGoods.length ? '选择后可把远端商品加入订单渠道' : '暂无直充商品' }}</strong>
        </div>
        <el-select v-model="bindingGoodsId" filterable placeholder="选择本地直充商品" :disabled="!directGoods.length">
          <el-option
            v-for="item in directGoods"
            :key="item.id"
            :label="`${item.name} · ID ${item.id}`"
            :value="item.id"
          />
        </el-select>
        <el-input-number v-model="bindForm.priority" :min="1" :step="10" controls-position="right" />
        <el-input-number v-model="bindForm.timeoutSeconds" :min="5" :step="5" controls-position="right" />
        <el-select v-model="bindForm.status">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </section>

      <el-table :data="syncGoods" max-height="520" style="width: 100%">
        <el-table-column prop="supplierGoodsId" label="供应商商品ID" min-width="130" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="190" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column label="价格" width="100">
          <template #default="{ row }">{{ formatMoney(row.price) }}</template>
        </el-table-column>
        <el-table-column label="面值" width="100">
          <template #default="{ row }">{{ row.faceValue ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="库存" width="90">
          <template #default="{ row }">{{ row.stock ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="可售渠道" min-width="150">
          <template #default="{ row }">
            <div class="channel-tags">
              <el-tag v-for="channel in row.availablePlatforms" :key="channel" size="small" effect="dark">{{ channel }}</el-tag>
              <span v-if="!row.availablePlatforms.length" class="muted-text">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="禁售渠道" min-width="150">
          <template #default="{ row }">
            <div class="channel-tags">
              <el-tag v-for="channel in row.forbiddenPlatforms" :key="channel" size="small" type="warning" effect="dark">{{ channel }}</el-tag>
              <span v-if="!row.forbiddenPlatforms.length" class="muted-text">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              :loading="bindingKey === row.supplierGoodsId"
              :disabled="!bindingGoodsId"
              @click="bindRemoteGoods(row)"
            >
              绑定渠道
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.supplier-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 14px;
}

.create-panel,
.table-panel {
  padding: 18px;
  overflow: hidden;
  border-radius: 22px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.45);
  font-size: 13px;
}

.panel-head svg {
  color: #00ffc3;
  filter: drop-shadow(0 0 18px rgba(0, 255, 195, 0.35));
}

.supplier-form :deep(.el-input-number),
.supplier-form :deep(.el-select) {
  width: 100%;
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.kasushou-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
  padding: 12px;
  margin-bottom: 16px;
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  border-radius: 14px;
  background: rgba(0, 255, 195, 0.045);
}

.integration-card {
  margin-top: 16px;
  padding: 14px;
  border: 0.5px solid rgba(255, 255, 255, 0.09);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
}

.integration-title,
.signature-note div {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.88);
}

.integration-title svg,
.signature-note svg {
  color: #00ffc3;
}

.capability-groups {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.capability-group {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.capability-group span {
  display: inline-flex;
  justify-content: center;
  padding: 3px 0;
  border-radius: 8px;
  color: #00ffc3;
  font-size: 12px;
  background: rgba(0, 255, 195, 0.08);
}

.capability-group p,
.signature-note p {
  margin: 0;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  line-height: 1.7;
}

.signature-note {
  display: grid;
  gap: 6px;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.08);
}

.supplier-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.supplier-name strong {
  min-width: 0;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.86);
  text-overflow: ellipsis;
}

.status-pill,
.balance,
.platform-pill {
  display: inline-flex;
  padding: 4px 9px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.62);
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.status-pill[data-enabled="true"],
.balance {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border-color: rgba(0, 255, 195, 0.2);
}

.balance[data-low="true"] {
  color: #ffab00;
  background: rgba(255, 171, 0, 0.1);
  border-color: rgba(255, 171, 0, 0.22);
  box-shadow: 0 0 20px rgba(255, 171, 0, 0.12);
}

.platform-pill {
  flex: 0 0 auto;
  font-size: 12px;
}

.platform-pill[data-kasushou="true"] {
  color: #071614;
  font-weight: 700;
  background: #00ffc3;
  border-color: rgba(0, 255, 195, 0.45);
  box-shadow: 0 0 18px rgba(0, 255, 195, 0.24);
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.action-buttons :deep(.el-button) {
  margin-left: 0;
}

.table-panel :deep(.action-buttons .el-button) {
  background: rgba(255, 255, 255, 0.055);
  border-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.78);
}

.table-panel :deep(.action-buttons .el-button.el-button--danger) {
  color: #ff8b8b;
  background: rgba(255, 78, 78, 0.09);
  border-color: rgba(255, 78, 78, 0.24);
}

.sync-dialog-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.92);
  font-weight: 700;
}

.sync-dialog-title svg {
  color: #00ffc3;
}

.sync-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.sync-summary article {
  display: grid;
  gap: 7px;
  padding: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.045);
}

.sync-summary span,
.muted-text {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.sync-summary strong {
  color: rgba(255, 255, 255, 0.88);
  font-size: 16px;
}

.channel-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.remote-bind-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 1.2fr) 120px 120px 100px;
  gap: 12px;
  align-items: center;
  padding: 12px;
  margin-bottom: 14px;
  border: 0.5px solid rgba(0, 255, 195, 0.13);
  border-radius: 12px;
  background: rgba(0, 255, 195, 0.045);
}

.remote-bind-bar div {
  display: grid;
  gap: 5px;
}

.remote-bind-bar span {
  color: rgba(255, 255, 255, 0.88);
  font-size: 13px;
  font-weight: 700;
}

.remote-bind-bar strong {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
  font-weight: 500;
}

.remote-bind-bar :deep(.el-select),
.remote-bind-bar :deep(.el-input-number) {
  width: 100%;
}

:global(.sync-result-dialog) {
  --el-dialog-bg-color: rgba(14, 18, 26, 0.96);
  color: rgba(255, 255, 255, 0.84);
  background:
    radial-gradient(circle at 12% 0%, rgba(0, 255, 195, 0.12), transparent 34%),
    radial-gradient(circle at 92% 10%, rgba(88, 166, 255, 0.14), transparent 28%),
    rgba(14, 18, 26, 0.96) !important;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.42);
}

:global(.sync-result-dialog .el-dialog__header),
:global(.sync-result-dialog .el-dialog__body) {
  color: rgba(255, 255, 255, 0.84);
}

:global(.sync-result-dialog .el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(255, 255, 255, 0.05);
  --el-table-row-hover-bg-color: rgba(0, 255, 195, 0.08);
  --el-table-border-color: rgba(255, 255, 255, 0.08);
  --el-table-text-color: rgba(255, 255, 255, 0.78);
  --el-table-header-text-color: rgba(255, 255, 255, 0.58);
  background: transparent;
}

:global(.sync-result-dialog .el-table th.el-table__cell),
:global(.sync-result-dialog .el-table tr),
:global(.sync-result-dialog .el-table td.el-table__cell) {
  background: transparent;
}

@media (max-width: 1180px) {
  .supplier-grid {
    grid-template-columns: 1fr;
  }

  .remote-bind-bar {
    grid-template-columns: 1fr;
  }
}
</style>
