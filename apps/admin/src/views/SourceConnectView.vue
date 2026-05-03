<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Layers3, PackageCheck, RefreshCw, Search, Settings2, Wand2 } from 'lucide-vue-next'
import { fetchCategories } from '../api/catalog'
import { cloneSourceGoods, fetchSourceConnectGoods, fetchSuppliers } from '../api/suppliers'
import type { Category, RemoteGoods, SourceCloneConfig, SourceCloneResult, Supplier } from '../types/operations'
import { formatMoney as formatAmount } from '../utils/formatters'

interface SourceCloneDraft extends SourceCloneConfig {
  accountTypesText: string
}

const suppliers = ref<Supplier[]>([])
const categories = ref<Category[]>([])
const remoteGoods = ref<RemoteGoods[]>([])
const selectedGoods = ref<RemoteGoods[]>([])
const draftItems = ref<SourceCloneDraft[]>([])
const cloneResult = ref<SourceCloneResult>()
const loading = ref(false)
const cloning = ref(false)

const form = reactive({
  supplierId: '',
  keyword: '',
  page: 1,
  limit: 20,
  cateId: 0,
  categoryId: '',
  priority: 10,
  timeoutSeconds: 30
})

const batchForm = reactive({
  categoryId: '',
  status: 'ON_SALE',
  coverUrl: '',
  accountTypesText: '账号',
  requireRechargeAccount: true,
  priority: 10,
  timeoutSeconds: 30
})

const enabledSuppliers = computed(() => suppliers.value.filter((item) => item.status === 'ENABLED'))
const canPrepare = computed(() => Boolean(form.supplierId) && selectedGoods.value.length > 0)
const canClone = computed(() => Boolean(form.supplierId) && draftItems.value.length > 0 && !cloning.value)

onMounted(async () => {
  await Promise.all([loadSuppliers(), loadCategories()])
  if (!form.supplierId && enabledSuppliers.value.length) form.supplierId = String(enabledSuppliers.value[0].id)
  if (!form.categoryId && categories.value.length) {
    form.categoryId = String(categories.value[0].id)
    batchForm.categoryId = form.categoryId
  }
})

async function loadSuppliers() {
  try {
    suppliers.value = await fetchSuppliers()
  } catch {
    ElMessage.error('供应商加载失败')
  }
}

async function loadCategories() {
  try {
    categories.value = await fetchCategories()
  } catch {
    ElMessage.error('分类加载失败')
  }
}

async function loadRemoteGoods() {
  if (!form.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }

  loading.value = true
  selectedGoods.value = []
  draftItems.value = []
  cloneResult.value = undefined
  try {
    const result = await fetchSourceConnectGoods(form.supplierId, {
      page: Number(form.page) || 1,
      limit: Number(form.limit) || 20,
      cateId: form.cateId || 0,
      keyword: form.keyword.trim()
    })
    remoteGoods.value = result.goods
    ElMessage.success(`已获取 ${result.goods.length} 个上游商品`)
  } catch {
    ElMessage.error('上游商品获取失败')
  } finally {
    loading.value = false
  }
}

function prepareDrafts() {
  if (!canPrepare.value) {
    ElMessage.warning('请先选择需要对接的上游商品')
    return
  }

  draftItems.value = selectedGoods.value.map((item) => ({
    supplierGoodsId: item.supplierGoodsId,
    name: item.name,
    categoryId: form.categoryId || undefined,
    price: numeric(item.price),
    originalPrice: numeric(item.faceValue) || numeric(item.price),
    stock: Math.max(0, Math.floor(numeric(item.stock))),
    status: upstreamStatus(item),
    coverUrl: '',
    description: `货源对接自动创建，已绑定上游商品 ${item.supplierGoodsId}`,
    accountTypes: splitAccountTypes(batchForm.accountTypesText),
    accountTypesText: batchForm.accountTypesText,
    requireRechargeAccount: batchForm.requireRechargeAccount,
    priority: Number(form.priority) || 10,
    timeoutSeconds: Number(form.timeoutSeconds) || 30
  }))
  cloneResult.value = undefined
  ElMessage.success(`已生成 ${draftItems.value.length} 个待对接配置`)
}

function applyBatch() {
  if (!draftItems.value.length) {
    ElMessage.warning('请先生成待对接配置')
    return
  }
  draftItems.value = draftItems.value.map((item) => ({
    ...item,
    categoryId: batchForm.categoryId || item.categoryId,
    status: batchForm.status || item.status,
    coverUrl: batchForm.coverUrl.trim() || item.coverUrl,
    accountTypes: splitAccountTypes(batchForm.accountTypesText),
    accountTypesText: batchForm.accountTypesText,
    requireRechargeAccount: batchForm.requireRechargeAccount,
    priority: Number(batchForm.priority) || item.priority,
    timeoutSeconds: Number(batchForm.timeoutSeconds) || item.timeoutSeconds
  }))
  ElMessage.success('已批量应用设置')
}

async function runClone() {
  if (!canClone.value) return

  cloning.value = true
  try {
    cloneResult.value = await cloneSourceGoods(form.supplierId, {
      items: draftItems.value.map(toCloneConfig)
    })
    ElMessage.success(`创建 ${cloneResult.value.createdCount} 个，跳过 ${cloneResult.value.skippedCount} 个`)
    selectedGoods.value = []
    draftItems.value = []
  } catch {
    ElMessage.error('一键对接失败')
  } finally {
    cloning.value = false
  }
}

function handleSelectionChange(selection: RemoteGoods[]) {
  selectedGoods.value = selection
}

function removeDraft(id: string) {
  draftItems.value = draftItems.value.filter((item) => item.supplierGoodsId !== id)
}

function toCloneConfig(item: SourceCloneDraft): SourceCloneConfig {
  return {
    supplierGoodsId: item.supplierGoodsId,
    name: item.name.trim(),
    categoryId: item.categoryId || undefined,
    price: numeric(item.price),
    originalPrice: numeric(item.originalPrice),
    stock: Math.max(0, Math.floor(numeric(item.stock))),
    status: item.status,
    coverUrl: item.coverUrl?.trim(),
    description: item.description?.trim(),
    accountTypes: splitAccountTypes(item.accountTypesText),
    requireRechargeAccount: item.requireRechargeAccount,
    priority: Number(item.priority) || 10,
    timeoutSeconds: Number(item.timeoutSeconds) || 30
  }
}

function splitAccountTypes(value: string) {
  return value
    .split(/[\s,，、/]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function upstreamStatus(item: RemoteGoods) {
  return item.status === 'SOLD_OUT' || item.status === 'OFF_SALE' ? 'OFF_SALE' : 'ON_SALE'
}

function numeric(value?: number | string) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function formatMoney(value?: number | string) {
  return formatAmount(value, { currency: false })
}

function statusTone(status: string) {
  if (status === 'CREATED') return 'success'
  if (status === 'SKIPPED') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}
</script>

<template>
  <section class="source-connect-shell">
    <div class="source-head liquid-admin-panel">
      <div>
        <span>Source Connect</span>
        <h2>货源对接</h2>
      </div>
      <div class="head-actions">
        <el-button :icon="RefreshCw" :loading="loading" @click="loadRemoteGoods">获取上游商品</el-button>
        <el-button :icon="Settings2" :disabled="!canPrepare" @click="prepareDrafts">配置对接</el-button>
        <el-button type="primary" :icon="Wand2" :disabled="!canClone" :loading="cloning" @click="runClone">一键对接</el-button>
      </div>
    </div>

    <section class="panel liquid-admin-panel">
      <div class="connect-form">
        <label>
          <span>供应商</span>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商">
            <el-option v-for="supplier in enabledSuppliers" :key="supplier.id" :label="supplier.name" :value="String(supplier.id)" />
          </el-select>
        </label>
        <label>
          <span>搜索关键词</span>
          <el-input v-model="form.keyword" :prefix-icon="Search" placeholder="按上游商品名搜索" @keyup.enter="loadRemoteGoods" />
        </label>
        <label>
          <span>默认本地分类</span>
          <el-select v-model="form.categoryId" filterable placeholder="选择本地分类">
            <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="String(category.id)" />
          </el-select>
        </label>
        <label>
          <span>默认优先级</span>
          <el-input-number v-model="form.priority" :min="1" :step="10" controls-position="right" />
        </label>
        <label>
          <span>默认超时</span>
          <el-input-number v-model="form.timeoutSeconds" :min="5" :step="5" controls-position="right" />
        </label>
      </div>
    </section>

    <section class="content-grid">
      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>上游商品</h2>
          <span>已选 {{ selectedGoods.length }} 个，配置后可逐项调整本地商品信息</span>
        </div>
        <el-table v-loading="loading" :data="remoteGoods" height="420" style="width: 100%" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="44" />
          <el-table-column prop="supplierGoodsId" label="上游商品ID" min-width="150" show-overflow-tooltip />
          <el-table-column prop="name" label="商品名称" min-width="220" show-overflow-tooltip />
          <el-table-column prop="type" label="类型" width="110" />
          <el-table-column label="价格" width="100">
            <template #default="{ row }">{{ formatMoney(row.price) }}</template>
          </el-table-column>
          <el-table-column label="库存" width="90">
            <template #default="{ row }">{{ row.stock ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120" />
        </el-table>
      </article>

      <article class="panel liquid-admin-panel">
        <div class="panel-head">
          <h2>对接结果</h2>
          <span>{{ cloneResult ? `成功 ${cloneResult.createdCount}，跳过 ${cloneResult.skippedCount}，失败 ${cloneResult.failedCount}` : '等待操作' }}</span>
        </div>
        <div class="result-feed">
          <article v-for="item in cloneResult?.items || []" :key="`${item.supplierGoodsId}-${item.status}`">
            <div>
              <strong>{{ item.supplierGoodsName || item.supplierGoodsId }}</strong>
              <el-tag :type="statusTone(item.status)" effect="plain">{{ item.status }}</el-tag>
            </div>
            <p>{{ item.message }}</p>
            <small>本地商品 {{ item.goodsId || '-' }} · 渠道 {{ item.channelId || '-' }}</small>
          </article>
          <section v-if="!cloneResult" class="empty">
            <PackageCheck :size="18" />
            <span>对接完成后会在这里展示创建、跳过和失败明细。</span>
          </section>
        </div>
      </article>
    </section>

    <section class="panel liquid-admin-panel">
      <div class="panel-head">
        <h2>批量设置</h2>
        <span>对待对接商品批量填充本地分类、充值字段、主图和渠道参数</span>
      </div>
      <div class="batch-form">
        <label>
          <span>本地分类</span>
          <el-select v-model="batchForm.categoryId" filterable placeholder="不修改分类">
            <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="String(category.id)" />
          </el-select>
        </label>
        <label>
          <span>商品状态</span>
          <el-select v-model="batchForm.status">
            <el-option label="上架" value="ON_SALE" />
            <el-option label="下架" value="OFF_SALE" />
          </el-select>
        </label>
        <label>
          <span>主图 URL</span>
          <el-input v-model="batchForm.coverUrl" placeholder="为空时保留逐项设置" />
        </label>
        <label>
          <span>充值字段</span>
          <el-input v-model="batchForm.accountTypesText" placeholder="账号 手机号 区服" />
        </label>
        <label>
          <span>需要充值账号</span>
          <el-switch v-model="batchForm.requireRechargeAccount" />
        </label>
        <label>
          <span>优先级</span>
          <el-input-number v-model="batchForm.priority" :min="1" :step="10" controls-position="right" />
        </label>
        <label>
          <span>超时秒数</span>
          <el-input-number v-model="batchForm.timeoutSeconds" :min="5" :step="5" controls-position="right" />
        </label>
        <el-button :icon="Layers3" :disabled="!draftItems.length" @click="applyBatch">应用到全部</el-button>
      </div>
    </section>

    <section class="panel liquid-admin-panel">
      <div class="panel-head">
        <h2>待对接配置</h2>
        <span>共 {{ draftItems.length }} 个，可在提交前手动修改每个商品的基础信息</span>
      </div>
      <el-table :data="draftItems" height="520" style="width: 100%" row-key="supplierGoodsId">
        <el-table-column prop="supplierGoodsId" label="上游ID" width="150" show-overflow-tooltip />
        <el-table-column label="本地商品名称" min-width="210">
          <template #default="{ row }">
            <el-input v-model="row.name" />
          </template>
        </el-table-column>
        <el-table-column label="分类" width="170">
          <template #default="{ row }">
            <el-select v-model="row.categoryId" filterable placeholder="默认分类">
              <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="String(category.id)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="售价" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.price" :min="0" :precision="2" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="面值" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.originalPrice" :min="0" :precision="2" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="库存" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.stock" :min="0" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-select v-model="row.status">
              <el-option label="上架" value="ON_SALE" />
              <el-option label="下架" value="OFF_SALE" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="充值字段" width="170">
          <template #default="{ row }">
            <el-input v-model="row.accountTypesText" placeholder="账号 手机号" />
          </template>
        </el-table-column>
        <el-table-column label="主图" min-width="190">
          <template #default="{ row }">
            <el-input v-model="row.coverUrl" placeholder="图片 URL" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="danger" @click="removeDraft(row.supplierGoodsId)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.source-connect-shell {
  display: grid;
  gap: 14px;
}

.source-head,
.panel {
  padding: 18px;
  border-radius: 22px;
}

.source-head,
.head-actions,
.panel-head,
.result-feed article div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.head-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.source-head span,
.panel-head span,
.result-feed small {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.source-head h2,
.panel-head h2 {
  margin: 4px 0 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 20px;
}

.connect-form,
.batch-form {
  display: grid;
  grid-template-columns: minmax(180px, 1.1fr) minmax(180px, 1fr) minmax(180px, 1fr) 140px 140px;
  gap: 12px;
  align-items: end;
}

.batch-form {
  grid-template-columns: minmax(160px, 1fr) 130px minmax(200px, 1.2fr) minmax(160px, 0.9fr) 120px 130px 130px auto;
}

.connect-form label,
.batch-form label {
  display: grid;
  gap: 7px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 0.65fr);
  gap: 14px;
}

.result-feed {
  display: grid;
  gap: 10px;
  max-height: 420px;
  overflow: auto;
  padding-right: 4px;
}

.result-feed article,
.empty {
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.result-feed strong {
  color: rgba(255, 255, 255, 0.88);
}

.result-feed p {
  margin: 8px 0 4px;
  color: rgba(255, 255, 255, 0.68);
}

.empty {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.56);
}

@media (max-width: 1280px) {
  .connect-form,
  .batch-form,
  .content-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 760px) {
  .source-head,
  .head-actions,
  .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .connect-form,
  .batch-form,
  .content-grid {
    grid-template-columns: 1fr;
  }
}
</style>
