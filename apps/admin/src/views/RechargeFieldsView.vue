<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RotateCcw, Save, SlidersHorizontal, Trash2 } from 'lucide-vue-next'
import {
  createRechargeField,
  deleteRechargeField,
  disableRechargeField,
  enableRechargeField,
  fetchRechargeFields,
  updateRechargeField
} from '../api/catalog'
import type { RechargeField, RechargeFieldPayload } from '../types/operations'

const fields = ref<RechargeField[]>([])
const loading = ref(false)
const saving = ref(false)
const editingId = ref<RechargeField['id']>()

const inputTypeOptions = [
  { label: '文本', value: 'text' },
  { label: '数字', value: 'number' },
  { label: '手机号', value: 'mobile' },
  { label: 'QQ号', value: 'qq' },
  { label: '剪映ID', value: 'jianying_id' },
  { label: '抖音ID', value: 'douyin_id' },
  { label: '邮箱', value: 'email' },
  { label: '多行文本', value: 'textarea' }
]

const form = reactive<RechargeFieldPayload>({
  code: '',
  label: '',
  placeholder: '',
  helpText: '',
  inputType: 'text',
  required: true,
  sort: 10,
  enabled: true
})

const formTitle = computed(() => (editingId.value ? '编辑字段' : '新增字段'))
const enabledCount = computed(() => fields.value.filter((item) => item.enabled).length)
const requiredCount = computed(() => fields.value.filter((item) => item.required).length)

onMounted(loadFields)

async function loadFields() {
  loading.value = true
  try {
    fields.value = await fetchRechargeFields()
  } catch {
    ElMessage.error('充值字段加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = undefined
  form.code = ''
  form.label = ''
  form.placeholder = ''
  form.helpText = ''
  form.inputType = 'text'
  form.required = true
  form.sort = nextSort()
  form.enabled = true
}

function editField(row: RechargeField) {
  editingId.value = row.id
  form.code = row.code
  form.label = row.label
  form.placeholder = row.placeholder
  form.helpText = row.helpText
  form.inputType = row.inputType || 'text'
  form.required = row.required
  form.sort = row.sort
  form.enabled = row.enabled
}

async function saveField() {
  if (!form.code.trim() || !form.label.trim()) {
    ElMessage.warning('请填写字段标识和字段名称')
    return
  }

  saving.value = true
  try {
    const payload = {
      ...form,
      code: normalizeCode(form.code),
      label: form.label.trim(),
      placeholder: form.placeholder?.trim(),
      helpText: form.helpText?.trim(),
      sort: Number(form.sort) || nextSort()
    }
    if (editingId.value) {
      await updateRechargeField(editingId.value, payload)
      ElMessage.success('充值字段已更新')
    } else {
      await createRechargeField(payload)
      ElMessage.success('充值字段已新增')
    }
    resetForm()
    await loadFields()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: RechargeField) {
  try {
    if (row.enabled) await disableRechargeField(row.id)
    else await enableRechargeField(row.id)
    ElMessage.success(row.enabled ? '字段已停用' : '字段已启用')
    await loadFields()
  } catch {
    ElMessage.error('状态更新失败')
  }
}

async function removeField(row: RechargeField) {
  try {
    await ElMessageBox.confirm(`确认删除充值字段「${row.label}」？`, '删除字段', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'xiyiyun-glass-message-box'
    })
    await deleteRechargeField(row.id)
    if (String(editingId.value) === String(row.id)) resetForm()
    ElMessage.success('字段已删除')
    await loadFields()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}

function normalizeCode(value: string) {
  return value.trim().toLowerCase().replace(/[^a-z0-9_]+/g, '_')
}

function inputTypeLabel(value: string) {
  return inputTypeOptions.find((item) => item.value === value)?.label || value
}

function nextSort() {
  return Math.max(0, ...fields.value.map((item) => Number(item.sort) || 0)) + 10
}
</script>

<template>
  <section class="field-shell">
    <article class="summary-grid">
      <div class="metric-card liquid-admin-panel">
        <span>字段总数</span>
        <strong>{{ fields.length }}</strong>
      </div>
      <div class="metric-card liquid-admin-panel">
        <span>启用字段</span>
        <strong>{{ enabledCount }}</strong>
      </div>
      <div class="metric-card liquid-admin-panel">
        <span>必填字段</span>
        <strong>{{ requiredCount }}</strong>
      </div>
    </article>

    <section class="content-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>充值字段列表</h2>
            <span>商品创建、货源对接时可复用这些字段</span>
          </div>
          <el-button :icon="RotateCcw" :loading="loading" @click="loadFields">刷新</el-button>
        </div>

        <el-table v-loading="loading" :data="fields" height="560" style="width: 100%">
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column prop="label" label="字段名称" min-width="130" />
          <el-table-column prop="code" label="字段标识" min-width="140" show-overflow-tooltip />
          <el-table-column label="输入类型" width="100">
            <template #default="{ row }">{{ inputTypeLabel(row.inputType) }}</template>
          </el-table-column>
          <el-table-column label="校验" width="90">
            <template #default="{ row }">
              <el-tag :type="row.required ? 'warning' : 'info'" effect="plain">{{ row.required ? '必填' : '选填' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="placeholder" label="提示文案" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button-group>
                <el-button size="small" @click="editField(row)">编辑</el-button>
                <el-button size="small" @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                <el-button size="small" type="danger" :icon="Trash2" @click="removeField(row)">删除</el-button>
              </el-button-group>
            </template>
          </el-table-column>
        </el-table>
      </article>

      <article class="panel editor-panel liquid-admin-panel">
        <div class="panel-head">
          <div>
            <h2>{{ formTitle }}</h2>
            <span>字段标识建议使用英文、数字、下划线</span>
          </div>
          <SlidersHorizontal :size="18" />
        </div>

        <div class="field-form">
          <label>
            <span>字段名称</span>
            <el-input v-model="form.label" placeholder="例如：手机号" />
          </label>
          <label>
            <span>字段标识</span>
            <el-input v-model="form.code" placeholder="例如：mobile" @blur="form.code = normalizeCode(form.code)" />
          </label>
          <label>
            <span>输入类型</span>
            <el-select v-model="form.inputType">
              <el-option v-for="item in inputTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </label>
          <label>
            <span>提示文案</span>
            <el-input v-model="form.placeholder" placeholder="请输入充值账号" />
          </label>
          <label>
            <span>说明</span>
            <el-input v-model="form.helpText" type="textarea" :rows="3" placeholder="前台或运营识别用途" />
          </label>
          <div class="inline-form">
            <label>
              <span>排序</span>
              <el-input-number v-model="form.sort" :min="0" :step="10" controls-position="right" />
            </label>
            <label>
              <span>必填</span>
              <el-switch v-model="form.required" />
            </label>
            <label>
              <span>启用</span>
              <el-switch v-model="form.enabled" />
            </label>
          </div>
        </div>

        <div class="editor-actions">
          <el-button @click="resetForm">清空</el-button>
          <el-button type="primary" :icon="editingId ? Save : Plus" :loading="saving" @click="saveField">
            {{ editingId ? '保存修改' : '新增字段' }}
          </el-button>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.field-shell {
  display: grid;
  gap: 14px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.metric-card,
.panel {
  padding: 18px;
  border-radius: 20px;
}

.metric-card {
  display: grid;
  gap: 8px;
}

.metric-card span,
.panel-head span,
.field-form span {
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.metric-card strong {
  color: rgba(255, 255, 255, 0.94);
  font-size: 28px;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 0.55fr);
  gap: 14px;
}

.panel-head,
.editor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-head {
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0 0 4px;
  color: rgba(255, 255, 255, 0.92);
  font-size: 20px;
}

.editor-panel {
  align-self: start;
}

.field-form {
  display: grid;
  gap: 14px;
}

.field-form label {
  display: grid;
  gap: 8px;
}

.inline-form {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 80px 80px;
  gap: 12px;
  align-items: end;
}

.editor-actions {
  margin-top: 18px;
  justify-content: flex-end;
}

@media (max-width: 1180px) {
  .summary-grid,
  .content-grid {
    grid-template-columns: 1fr;
  }
}
</style>
