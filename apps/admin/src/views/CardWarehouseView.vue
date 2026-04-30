<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Eye, Plus, RefreshCw, Upload } from 'lucide-vue-next'
import { createCardKind, fetchCardKindCards, fetchCardKinds, importCardKindCards } from '../api/admin'
import type { CardKind, CardKindCreatePayload, CardKindType, GoodsCard } from '../types/operations'

const cardTypeOptions: Array<{ label: string; value: CardKindType }> = [
  { label: '一次性卡', value: 'ONCE' },
  { label: '重复卡', value: 'REUSABLE' }
]

const cardKinds = ref<CardKind[]>([])
const cards = ref<GoodsCard[]>([])
const loading = ref(false)
const saving = ref(false)
const cardLoading = ref(false)
const importVisible = ref(false)
const cardsVisible = ref(false)
const selectedKind = ref<CardKind>()
const cardText = ref('')
const form = reactive<CardKindCreatePayload>({
  name: '',
  type: 'ONCE',
  cost: 0
})

const totalCost = computed(() => cardKinds.value.reduce((sum, item) => sum + Number(item.cost || 0), 0))
const parsedCards = computed(() => {
  const seen = new Set<string>()
  return cardText.value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => {
      if (seen.has(line)) return false
      seen.add(line)
      return true
    })
})
const ignoredImportCount = computed(() => {
  const filledLines = cardText.value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  return Math.max(filledLines.length - parsedCards.value.length, 0)
})

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

function cardKindTotal(row: CardKind) {
  const total = Number(row.totalCount || 0)
  if (total > 0) return total
  return Number(row.unusedCount || 0) + Number(row.usedCount || 0)
}

function cardKindUnused(row: CardKind) {
  return Number(row.unusedCount ?? row.stock ?? 0)
}

function cardKindUsed(row: CardKind) {
  return Number(row.usedCount || 0)
}

function cardDisplay(row: GoodsCard) {
  return row.content || row.preview || row.password || row.cardNo || '-'
}

function statusLabel(status?: string) {
  if (status === 'USED') return '已使用'
  if (status === 'AVAILABLE' || status === 'UNUSED') return '未使用'
  return status || '-'
}

function statusTagType(status?: string) {
  return status === 'USED' ? 'info' : 'success'
}

function applyCardStats(kindId: CardKind['id'], list: GoodsCard[]) {
  const target = cardKinds.value.find((item) => String(item.id) === String(kindId))
  if (!target) return

  const usedCount = list.filter((item) => item.status === 'USED').length
  target.usedCount = usedCount
  target.unusedCount = list.length - usedCount
  target.stock = target.unusedCount
  target.totalCount = list.length
}

function openImport(row: CardKind) {
  selectedKind.value = row
  cardText.value = ''
  importVisible.value = true
}

async function submitCards() {
  if (!selectedKind.value) return

  if (!parsedCards.value.length) {
    ElMessage.warning('请填写卡密，每行一条')
    return
  }

  cardLoading.value = true
  try {
    const result = await importCardKindCards(selectedKind.value.id, parsedCards.value)
    const successCount = result.successCount ?? parsedCards.value.length
    ElMessage.success(`已导入 ${successCount} 条卡密`)
    importVisible.value = false
    await loadCardKinds()
  } catch {
    ElMessage.error('卡密导入失败')
  } finally {
    cardLoading.value = false
  }
}

async function openCards(row: CardKind) {
  selectedKind.value = row
  cardsVisible.value = true
  cardLoading.value = true
  cards.value = []

  try {
    cards.value = await fetchCardKindCards(row.id)
    applyCardStats(row.id, cards.value)
  } catch {
    ElMessage.error('卡密列表加载失败')
  } finally {
    cardLoading.value = false
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
        <el-table-column label="库存" width="110">
          <template #default="{ row }">{{ cardKindUnused(row) }}</template>
        </el-table-column>
        <el-table-column label="未使用" width="110">
          <template #default="{ row }">{{ cardKindUnused(row) }}</template>
        </el-table-column>
        <el-table-column label="已使用" width="110">
          <template #default="{ row }">{{ cardKindUsed(row) }}</template>
        </el-table-column>
        <el-table-column label="总数" width="110">
          <template #default="{ row }">{{ cardKindTotal(row) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button size="small" :icon="Upload" @click="openImport(row)">添加卡密</el-button>
              <el-button size="small" :icon="Eye" @click="openCards(row)">查看</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </article>

    <el-dialog v-model="importVisible" title="添加卡密" width="560px">
      <p class="dialog-hint">{{ selectedKind?.name }}，每行一条卡密，提交前会自动去除空行和重复行。</p>
      <el-input
        v-model="cardText"
        type="textarea"
        :rows="12"
        placeholder="VIP-7D-ALPHA----8F2K&#10;VIP-7D-BRAVO----6P9Q"
      />
      <template #footer>
        <span class="dialog-count">有效 {{ parsedCards.length }} 条，重复 {{ ignoredImportCount }} 条</span>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="cardLoading" @click="submitCards">提交导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cardsVisible" :title="`${selectedKind?.name || '卡种'}卡密列表`" width="840px">
      <el-table v-loading="cardLoading" :data="cards" height="420" style="width: 100%">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="卡密" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ cardDisplay(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="orderNo" label="订单号" min-width="160" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="导入时间" width="180" />
        <el-table-column prop="usedAt" label="使用时间" width="180" />
      </el-table>
    </el-dialog>
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

.table-actions {
  display: flex;
  gap: 8px;
}

.dialog-hint {
  margin: 0 0 12px;
  color: rgba(255, 255, 255, 0.62);
}

.dialog-count {
  float: left;
  color: rgba(255, 255, 255, 0.62);
  line-height: 32px;
}
</style>
