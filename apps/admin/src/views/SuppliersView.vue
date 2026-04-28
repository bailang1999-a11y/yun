<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PlugZap, Plus, RefreshCw, WalletCards } from 'lucide-vue-next'
import {
  createSupplier,
  fetchSuppliers,
  refreshSupplierBalance,
  setSupplierEnabled,
  testSupplierConnection
} from '../api/admin'
import type { Supplier, SupplierCreatePayload } from '../types/operations'

const suppliers = ref<Supplier[]>([])
const loading = ref(false)
const saving = ref(false)
const operatingId = ref('')

const form = reactive<SupplierCreatePayload>({
  name: '',
  baseUrl: '',
  appKey: '',
  appSecret: '',
  balance: 1000,
  status: 'ENABLED',
  remark: ''
})

onMounted(loadSuppliers)

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

async function submitSupplier() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写供应商名称')
    return
  }
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请填写 API 地址')
    return
  }
  saving.value = true
  try {
    await createSupplier({
      ...form,
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      appKey: form.appKey.trim(),
      appSecret: form.appSecret.trim(),
      remark: form.remark?.trim()
    })
    ElMessage.success('供应商已新增')
    form.name = ''
    form.baseUrl = ''
    form.appKey = ''
    form.appSecret = ''
    form.balance = 1000
    form.status = 'ENABLED'
    form.remark = ''
    await loadSuppliers()
  } catch {
    ElMessage.error('新增供应商失败')
  } finally {
    saving.value = false
  }
}

async function runOperation(row: Supplier, type: 'toggle' | 'balance' | 'test') {
  operatingId.value = `${type}:${row.id}`
  try {
    if (type === 'toggle') {
      await setSupplierEnabled(row.id, row.status !== 'ENABLED')
      ElMessage.success(row.status === 'ENABLED' ? '供应商已停用' : '供应商已启用')
    }
    if (type === 'balance') {
      await refreshSupplierBalance(row.id)
      ElMessage.success('余额已刷新')
    }
    if (type === 'test') {
      await testSupplierConnection(row.id)
      ElMessage.success('连接测试成功')
    }
    await loadSuppliers()
  } catch {
    ElMessage.error('操作失败')
  } finally {
    operatingId.value = ''
  }
}
</script>

<template>
  <section class="supplier-grid">
    <article class="create-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>新增供应商</h2>
          <span>用于直充渠道、余额预警和自动切换</span>
        </div>
        <PlugZap :size="20" />
      </div>

      <el-form :model="form" label-position="top" class="supplier-form">
        <el-form-item label="供应商名称">
          <el-input v-model="form.name" placeholder="例如：星河直充" />
        </el-form-item>
        <el-form-item label="API 地址">
          <el-input v-model="form.baseUrl" placeholder="https://api.example.com" />
        </el-form-item>
        <el-form-item label="AppKey">
          <el-input v-model="form.appKey" placeholder="用于鉴权的 Key" />
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input v-model="form.appSecret" type="password" show-password placeholder="保存后仅脱敏展示" />
        </el-form-item>
        <el-form-item label="初始余额">
          <el-input-number v-model="form.balance" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" />
        </el-form-item>
        <el-button type="primary" :icon="Plus" :loading="saving" @click="submitSupplier">新增供应商</el-button>
      </el-form>
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
        <el-table-column prop="name" label="供应商" min-width="150" show-overflow-tooltip />
        <el-table-column prop="baseUrl" label="API 地址" min-width="220" show-overflow-tooltip />
        <el-table-column prop="appKey" label="AppKey" min-width="150" show-overflow-tooltip />
        <el-table-column prop="appSecretMasked" label="密钥" width="130" />
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
        <el-table-column label="操作" width="310" fixed="right">
          <template #default="{ row }">
            <el-button-group>
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
                :icon="PlugZap"
                :loading="operatingId === `test:${row.id}`"
                @click="runOperation(row, 'test')"
              >
                测试
              </el-button>
              <el-button
                size="small"
                :loading="operatingId === `toggle:${row.id}`"
                @click="runOperation(row, 'toggle')"
              >
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </article>
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

.status-pill,
.balance {
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

.table-panel :deep(.el-button-group .el-button) {
  background: rgba(255, 255, 255, 0.055);
  border-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.78);
}
</style>
