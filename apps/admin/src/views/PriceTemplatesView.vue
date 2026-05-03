<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Save, Trash2 } from 'lucide-vue-next'
import { fetchUserGroups } from '../api/users'
import type { UserGroup } from '../types/operations'
import { loadPriceTemplates, savePriceTemplates, type PriceGroupRate, type PriceTemplate } from '../utils/priceTemplates'

const rateColors = ['#ffb300', '#3aa5ff', '#12a594', '#0d9488', '#8b5cf6', '#f97316', '#22c55e']
const fallbackGroups: UserGroup[] = [
  { id: 'retail', name: '默认会员', rules: [] },
  { id: 'vip', name: '渠道 VIP', rules: [] },
  { id: 'limited', name: '受限会员', rules: [] }
]

const templates = ref<PriceTemplate[]>(loadPriceTemplates())
const userGroups = ref<UserGroup[]>([])
const form = reactive<PriceTemplate>({
  id: '',
  name: '',
  adjustMode: 'percent',
  referencePrice: 100,
  groupRates: [],
  enabled: true
})

const activeGroups = computed(() => (userGroups.value.length ? userGroups.value : fallbackGroups))
const preview = computed(() =>
  form.groupRates.map((rate) => ({
    ...rate,
    price: form.adjustMode === 'percent'
      ? (Number(form.referencePrice) * Number(rate.value)) / 100
      : Number(form.referencePrice) + Number(rate.value)
  }))
)

onMounted(async () => {
  try {
    userGroups.value = await fetchUserGroups()
  } catch {
    userGroups.value = fallbackGroups
  }
  form.groupRates = ratesFromGroups(activeGroups.value, form.groupRates)
  templates.value = templates.value.map((item) => ({ ...item, groupRates: ratesFromGroups(activeGroups.value, item.groupRates) }))
  savePriceTemplates(templates.value)
})

function defaultRate(index: number) {
  return index === 0 ? 100 : 100 + Math.max(2, 8 - index)
}

function ratesFromGroups(groups: UserGroup[], existing: PriceGroupRate[] = []) {
  return groups.map((group, index) => {
    const matched = existing.find((item) => item.groupName === group.name)
    return {
      groupName: group.name,
      color: matched?.color || rateColors[index % rateColors.length],
      value: matched?.value ?? defaultRate(index)
    }
  })
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.adjustMode = 'percent'
  form.referencePrice = 100
  form.groupRates = ratesFromGroups(activeGroups.value)
  form.enabled = true
}

function saveTemplate() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写模板标题')
    return
  }
  const next: PriceTemplate = {
    id: form.id || `tpl-${Date.now()}`,
    name: form.name.trim(),
    adjustMode: form.adjustMode,
    referencePrice: Number(form.referencePrice) || 100,
    groupRates: form.groupRates.map((item) => ({ ...item, value: Number(item.value) || 100 })),
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
  form.adjustMode = row.adjustMode
  form.referencePrice = row.referencePrice
  form.groupRates = ratesFromGroups(activeGroups.value, row.groupRates)
  form.enabled = row.enabled
}

function removeTemplate(row: PriceTemplate) {
  templates.value = templates.value.filter((item) => item.id !== row.id)
  savePriceTemplates(templates.value)
  ElMessage.success('价格模板已删除')
}
</script>

<template>
  <section class="template-shell">
    <article class="template-card">
      <div class="template-form-line">
        <label>模板标题</label>
        <el-input v-model="form.name" placeholder="请填写模板标题" />
      </div>

      <div class="template-form-line">
        <label>加/减价方式</label>
        <el-radio-group v-model="form.adjustMode">
          <el-radio label="fixed">按固定金额</el-radio>
          <el-radio label="percent">按百分比</el-radio>
        </el-radio-group>
      </div>

      <div class="template-form-line">
        <label>参考价</label>
        <div>
          <el-input-number v-model="form.referencePrice" :min="0" :precision="2" controls-position="right" />
          <p>参考价仅用于预览，例如成本价 100 元，会员分组数值 105，前台看到的价格为 105.00 元。</p>
        </div>
      </div>

      <div v-for="(rate, index) in form.groupRates" :key="rate.groupName" class="template-form-line rate-line">
        <label><i :style="{ background: rate.color }"></i>{{ rate.groupName }}倍率</label>
        <el-input-number v-model="rate.value" :min="0" :precision="2" controls-position="right" />
        <span>预览：<strong>{{ preview[index].price.toFixed(2) }} 元</strong></span>
      </div>

      <div class="template-actions">
        <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        <el-button @click="resetForm">清空</el-button>
        <el-button type="primary" :icon="form.id ? Save : Plus" @click="saveTemplate">{{ form.id ? '保存修改' : '新增模板' }}</el-button>
      </div>
    </article>

    <article class="panel template-list">
      <div class="panel-head">
        <h2>价格模板配置</h2>
        <span>商品弹窗选择模板后按会员等级倍率进行预览和销售价计算</span>
      </div>
      <el-table :data="templates" height="420" style="width: 100%">
        <el-table-column prop="name" label="模板标题" min-width="180" />
        <el-table-column label="方式" width="120">
          <template #default="{ row }">{{ row.adjustMode === 'percent' ? '按百分比' : '按固定金额' }}</template>
        </el-table-column>
        <el-table-column label="会员等级" min-width="260">
          <template #default="{ row }">
            <div class="rate-tags">
              <el-tag v-for="rate in row.groupRates" :key="rate.groupName" effect="plain" size="small">
                {{ rate.groupName }} {{ rate.value }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
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
.template-shell {
  display: grid;
  gap: 14px;
}

.template-card,
.panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.template-card {
  max-width: 760px;
}

.template-form-line {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) 160px;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.template-form-line label {
  text-align: right;
  color: rgba(255, 255, 255, 0.86);
}

.template-form-line p {
  margin: 8px 0 0;
  line-height: 1.55;
  color: rgba(255, 255, 255, 0.48);
}

.rate-line label {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}

.rate-line i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.rate-line strong {
  color: #3aa5ff;
  padding: 4px 8px;
  border-radius: 6px;
  background: rgba(58, 165, 255, 0.1);
}

.template-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
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

.rate-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
