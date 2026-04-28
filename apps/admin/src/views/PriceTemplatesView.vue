<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Save, Trash2 } from 'lucide-vue-next'
import { loadPriceTemplates, savePriceTemplates, type PriceTemplate } from '../utils/priceTemplates'

const templates = ref<PriceTemplate[]>(loadPriceTemplates())
const form = reactive<PriceTemplate>({
  id: '',
  name: '',
  groupName: '',
  markupPercent: 0,
  enabled: true
})

function resetForm() {
  form.id = ''
  form.name = ''
  form.groupName = ''
  form.markupPercent = 0
  form.enabled = true
}

function saveTemplate() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  if (!form.groupName.trim()) {
    ElMessage.warning('请填写会员组')
    return
  }

  const next: PriceTemplate = {
    id: form.id || `tpl-${Date.now()}`,
    name: form.name.trim(),
    groupName: form.groupName.trim(),
    markupPercent: Number(form.markupPercent) || 0,
    enabled: form.enabled
  }
  const index = templates.value.findIndex((item) => item.id === next.id)
  if (index >= 0) templates.value.splice(index, 1, next)
  else templates.value.push(next)
  savePriceTemplates(templates.value)
  resetForm()
  ElMessage.success('价格模板已保存')
}

function editTemplate(row: PriceTemplate) {
  form.id = row.id
  form.name = row.name
  form.groupName = row.groupName
  form.markupPercent = row.markupPercent
  form.enabled = row.enabled
}

function removeTemplate(row: PriceTemplate) {
  templates.value = templates.value.filter((item) => item.id !== row.id)
  savePriceTemplates(templates.value)
  ElMessage.success('价格模板已删除')
}
</script>

<template>
  <section class="template-grid">
    <article class="panel template-form">
      <div class="panel-head">
        <h2>{{ form.id ? '编辑价格模板' : '新增价格模板' }}</h2>
        <span>按会员组设置百分比加价</span>
      </div>
      <el-form :model="form" label-position="top">
        <el-form-item label="模板名称">
          <el-input v-model="form.name" placeholder="例如：VIP 渠道加价" />
        </el-form-item>
        <el-form-item label="会员组">
          <el-input v-model="form.groupName" placeholder="例如：VIP 会员" />
        </el-form-item>
        <el-form-item label="百分比加价">
          <el-input-number v-model="form.markupPercent" :min="0" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <div class="form-actions">
          <el-button @click="resetForm">清空</el-button>
          <el-button type="primary" :icon="form.id ? Save : Plus" @click="saveTemplate">
            {{ form.id ? '保存修改' : '新增模板' }}
          </el-button>
        </div>
      </el-form>
    </article>

    <article class="panel template-list">
      <div class="panel-head">
        <h2>价格模板配置</h2>
        <span>商品弹窗可直接选择模板并自动换算成本系数</span>
      </div>
      <el-table :data="templates" height="560" style="width: 100%">
        <el-table-column prop="name" label="模板名称" min-width="180" />
        <el-table-column prop="groupName" label="会员组" min-width="140" />
        <el-table-column label="加价比例" width="120">
          <template #default="{ row }">+{{ row.markupPercent }}%</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" @click="editTemplate(row)">编辑</el-button>
              <el-button size="small" type="danger" :icon="Trash2" @click="removeTemplate(row)">删除</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>
</template>

<style scoped>
.template-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
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

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.5);
}

.template-form :deep(.el-input-number) {
  width: 100%;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 1180px) {
  .template-grid {
    grid-template-columns: 1fr;
  }
}
</style>
