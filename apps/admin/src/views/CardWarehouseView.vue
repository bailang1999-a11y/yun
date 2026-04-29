<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw } from 'lucide-vue-next'
import { createCardKind, fetchCardKinds } from '../api/admin'
import type { CardKind, CardKindCreatePayload, CardKindType } from '../types/operations'

const cardTypeOptions: Array<{ label: string; value: CardKindType }> = [
  { label: '一次性卡', value: 'ONCE' },
  { label: '重复卡', value: 'REUSABLE' }
]

const cardKinds = ref<CardKind[]>([])
const loading = ref(false)
const saving = ref(false)
const form = reactive<CardKindCreatePayload>({
  name: '',
  type: 'ONCE',
  cost: 0
})

const totalCost = computed(() => cardKinds.value.reduce((sum, item) => sum + Number(item.cost || 0), 0))

onMounted(loadCardKinds)

async function loadCardKinds() {
  loading.value = true
  try {
    cardKinds.value = await fetchCardKinds()
  } catch {
    ElMessage.error('卡种列表加载失败')
  } finally {
    loading.value = false
  }
}

function typeLabel(type: string) {
  return cardTypeOptions.find((item) => item.value === type)?.label || type || '-'
}

function resetForm() {
  form.name = ''
  form.type = 'ONCE'
  form.cost = 0
}

async function submitCardKind() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请填写卡种名称')
    return
  }
  if (!Number.isFinite(Number(form.cost)) || Number(form.cost) < 0) {
    ElMessage.warning('请填写有效成本')
    return
  }

  saving.value = true
  try {
    const created = await createCardKind({
      name,
      type: form.type,
      cost: Number(form.cost)
    })
    cardKinds.value.unshift(created)
    resetForm()
    ElMessage.success('卡种已创建')
  } catch {
    ElMessage.error('卡种创建失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="warehouse-shell">
    <article class="kind-form-panel">
      <div class="panel-head">
        <div>
          <h2>创建卡种</h2>
          <span>配置卡密库存归属的基础类型与成本</span>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="kind-form">
        <el-form-item label="卡种名称">
          <el-input v-model="form.name" maxlength="40" show-word-limit placeholder="例如：腾讯视频月卡" />
        </el-form-item>
        <el-form-item label="卡种类型">
          <el-radio-group v-model="form.type">
            <el-radio-button v-for="item in cardTypeOptions" :key="item.value" :label="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="成本">
          <el-input-number v-model="form.cost" :min="0" :precision="2" controls-position="right" />
        </el-form-item>
        <div class="form-actions">
          <el-button @click="resetForm">清空</el-button>
          <el-button type="primary" :icon="Plus" :loading="saving" @click="submitCardKind">新增卡种</el-button>
        </div>
      </el-form>
    </article>

    <article class="kind-list-panel">
      <div class="panel-head">
        <div>
          <h2>卡种列表</h2>
          <span>共 {{ cardKinds.length }} 个卡种，成本合计 {{ totalCost.toFixed(2) }} 元</span>
        </div>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadCardKinds">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="cardKinds" height="560" style="width: 100%">
        <el-table-column prop="name" label="卡种名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="卡种类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.type === 'REUSABLE' ? 'warning' : 'success'" effect="plain">
              {{ typeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成本" width="140">
          <template #default="{ row }">{{ Number(row.cost || 0).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" show-overflow-tooltip />
      </el-table>
    </article>
  </section>
</template>

<style scoped>
.warehouse-shell {
  display: grid;
  gap: 14px;
}

.kind-form-panel,
.kind-list-panel {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.kind-form-panel {
  max-width: 760px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  display: block;
  margin-top: 6px;
  color: rgba(255, 255, 255, 0.5);
}

.kind-form {
  max-width: 520px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
