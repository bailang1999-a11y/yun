<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { ImagePlus, Layers3, PackageCheck, RefreshCw, Search, Settings2, Upload as UploadIcon, Wand2 } from 'lucide-vue-next'
import { fetchCategories, fetchRechargeFields } from '../api/catalog'
import { cloneSourceGoods, fetchSourceConnectGoods, fetchSuppliers } from '../api/suppliers'
import { fetchPriceTemplates } from '../api/priceTemplates'
import { uploadImage } from '../api/uploads'
import type { Category, RechargeField, RemoteCategory, RemoteGoods, SourceCloneConfig, SourceCloneResult, Supplier } from '../types/operations'
import { formatMoney as formatAmount } from '../utils/formatters'
import { benefitDurationOptions, platformOptions } from '../utils/goodsOptions'
import { type PriceTemplate } from '../utils/priceTemplates'

type SourceCloneDraft = Omit<SourceCloneConfig, 'requireRechargeAccount'> & { requireRechargeAccount?: boolean }
type RequiredBatchNumber = number | undefined
const UNLIMITED_PLATFORM = '__all__'

const suppliers = ref<Supplier[]>([])
const categories = ref<Category[]>([])
const rechargeFields = ref<RechargeField[]>([])
const priceTemplates = ref<PriceTemplate[]>([])
const remoteGoods = ref<RemoteGoods[]>([])
const remoteTotal = ref(0)
const remoteCategories = ref<RemoteCategory[]>([])
const selectedGoods = ref<RemoteGoods[]>([])
const draftItems = ref<SourceCloneDraft[]>([])
const cloneResult = ref<SourceCloneResult>()
const loading = ref(false)
const cloning = ref(false)
const batchApplied = ref(false)
const draftPanelRef = ref<HTMLElement>()
const draftTableRef = ref<{ toggleRowExpansion: (row: SourceCloneDraft, expanded?: boolean) => void }>()
const coverPreviewVisible = ref(false)
const coverPreviewUrl = ref('')
const coverPreviewTitle = ref('商品主图预览')
const imageAccept = 'image/jpeg,image/png,image/webp,image/gif'

const form = reactive({
  supplierId: '',
  keyword: '',
  page: 1,
  limit: 100,
  cateId: 0,
  categoryId: '',
  priority: 10,
  timeoutSeconds: 30
})

const batchForm = reactive({
  categoryId: '',
  status: '',
  coverUrl: '',
  accountTypes: [] as string[],
  requireRechargeAccount: undefined as boolean | undefined,
  priceTemplateId: '',
  priceMode: '',
  priceCoefficient: undefined as RequiredBatchNumber,
  priceFixedAdd: undefined as RequiredBatchNumber,
  availablePlatforms: [UNLIMITED_PLATFORM] as string[],
  forbiddenPlatforms: [] as string[],
  priority: 10 as RequiredBatchNumber,
  timeoutSeconds: 30 as RequiredBatchNumber
})

const enabledSuppliers = computed(() => suppliers.value.filter((item) => item.status === 'ENABLED'))
const canPrepare = computed(() => Boolean(form.supplierId) && selectedGoods.value.length > 0)
const canClone = computed(() => Boolean(form.supplierId) && draftItems.value.length > 0 && !cloning.value)
const remotePageCount = computed(() => Math.max(1, Math.ceil(remoteTotal.value / (Number(form.limit) || 100))))
const accountFieldOptions = computed(() => rechargeFields.value.filter((item) => item.enabled))
const priceTemplateOptions = computed(() => priceTemplates.value)
const categoryTreeOptions = computed(() => buildCategoryTree(categories.value))
const salePlatformOptions = computed(() => [
  { label: '无限制', value: UNLIMITED_PLATFORM, logo: 'all' },
  ...platformOptions
])
const remoteRangeLabel = computed(() => {
  if (!remoteTotal.value || !remoteGoods.value.length) return '未获取上游商品'
  const page = Number(form.page) || 1
  const limit = Number(form.limit) || 100
  const start = (page - 1) * limit + 1
  const end = Math.min(start + remoteGoods.value.length - 1, remoteTotal.value)
  return `第 ${start}-${end} 条，共 ${remoteTotal.value} 条`
})

onMounted(async () => {
  await Promise.all([loadSuppliers(), loadCategories(), loadRechargeFields(), refreshPriceTemplates()])
  if (!form.supplierId && enabledSuppliers.value.length) form.supplierId = String(enabledSuppliers.value[0].id)
})

watch(batchForm, () => {
  batchApplied.value = false
}, { deep: true })

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

async function loadRechargeFields() {
  try {
    rechargeFields.value = await fetchRechargeFields({ enabled: true })
  } catch {
    rechargeFields.value = []
    ElMessage.error('充值字段库加载失败')
  }
}

async function loadRemoteGoods() {
  if (!form.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }

  loading.value = true
  remoteGoods.value = []
  selectedGoods.value = []
  draftItems.value = []
  cloneResult.value = undefined
  try {
    const result = await fetchSourceConnectGoods(form.supplierId, {
      page: Number(form.page) || 1,
      limit: Number(form.limit) || 100,
      cateId: form.cateId || 0,
      keyword: form.keyword.trim()
    })
    remoteGoods.value = result.goods
    remoteTotal.value = result.total
    remoteCategories.value = result.categories
    ElMessage.success(`已获取 ${result.goods.length} 个上游商品，共 ${result.total} 个匹配结果`)
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? `上游商品获取失败：${error.message}` : '上游商品获取失败')
  } finally {
    loading.value = false
  }
}

function searchRemoteGoods() {
  form.page = 1
  loadRemoteGoods()
}

function handleRemotePageChange(page: number) {
  form.page = page
  loadRemoteGoods()
}

function handleRemoteSizeChange(size: number) {
  form.limit = size
  form.page = 1
  loadRemoteGoods()
}

function loadPreviousRemotePage() {
  if (loading.value || form.page <= 1) return
  form.page -= 1
  loadRemoteGoods()
}

function loadNextRemotePage() {
  if (loading.value || form.page >= remotePageCount.value) return
  form.page += 1
  loadRemoteGoods()
}

async function prepareDrafts() {
  if (!canPrepare.value) {
    ElMessage.warning('请先选择需要对接的上游商品')
    return
  }

  draftItems.value = selectedGoods.value.map((item) => ({
    supplierGoodsId: item.supplierGoodsId,
    name: item.name,
    categoryId: batchForm.categoryId,
    price: numeric(item.price),
    originalPrice: numeric(item.faceValue) || numeric(item.price),
    stock: Math.max(0, Math.floor(numeric(item.stock))),
    status: batchForm.status,
    benefitDurations: inferBenefitDurations(item.name),
    coverUrl: batchForm.coverUrl,
    description: `货源对接自动创建，已绑定上游商品 ${item.supplierGoodsId}`,
    accountTypes: [...batchForm.accountTypes],
    requireRechargeAccount: batchForm.requireRechargeAccount,
    priceTemplateId: batchForm.priceTemplateId,
    priceMode: batchForm.priceMode,
    priceCoefficient: Number(batchForm.priceCoefficient),
    priceFixedAdd: Number(batchForm.priceFixedAdd) || 0,
    availablePlatforms: normalizeAvailablePlatforms(batchForm.availablePlatforms),
    forbiddenPlatforms: [...batchForm.forbiddenPlatforms],
    priority: Number(batchForm.priority),
    timeoutSeconds: Number(batchForm.timeoutSeconds)
  }))
  cloneResult.value = undefined
  batchApplied.value = false
  ElMessage.success(`已生成 ${draftItems.value.length} 个待对接配置`)
  await nextTick()
  scrollToDraftPanel()
}

function scrollToDraftPanel() {
  draftPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function toggleDraftRow(row: SourceCloneDraft) {
  draftTableRef.value?.toggleRowExpansion(row)
}

function applyBatch() {
  if (!draftItems.value.length) {
    ElMessage.warning('请先生成待对接配置')
    return
  }
  syncBatchSettingsToDrafts()
  batchApplied.value = true
  ElMessage.success('已批量应用设置')
}

async function runClone() {
  if (!canClone.value) return
  if (!batchApplied.value) {
    ElMessage.warning('请先点击“应用到全部”，把批量设置同步到待对接配置')
    return
  }
  syncBatchSettingsToDrafts()
  if (!validateDraftSettings()) return

  cloning.value = true
  try {
    cloneResult.value = await cloneSourceGoods(form.supplierId, {
      items: draftItems.value.map(toCloneConfig)
    })
    ElMessage.success(`创建 ${cloneResult.value.createdCount} 个，跳过 ${cloneResult.value.skippedCount} 个`)
    selectedGoods.value = []
    draftItems.value = []
    batchApplied.value = false
  } catch {
    ElMessage.error('一键对接失败')
  } finally {
    cloning.value = false
  }
}

function syncBatchSettingsToDrafts() {
  if (!draftItems.value.length) return
  draftItems.value = draftItems.value.map(applyBatchSettingsToDraft)
}

function applyBatchSettingsToDraft(item: SourceCloneDraft): SourceCloneDraft {
  return {
    ...item,
    categoryId: batchForm.categoryId || item.categoryId,
    status: batchForm.status || item.status,
    coverUrl: batchForm.coverUrl.trim() || item.coverUrl,
    accountTypes: batchForm.accountTypes.length ? [...batchForm.accountTypes] : item.accountTypes,
    requireRechargeAccount: typeof batchForm.requireRechargeAccount === 'boolean'
      ? batchForm.requireRechargeAccount
      : item.requireRechargeAccount,
    priceTemplateId: batchForm.priceTemplateId || item.priceTemplateId,
    priceMode: batchForm.priceMode || item.priceMode,
    priceCoefficient: hasPositiveNumber(batchForm.priceCoefficient) ? Number(batchForm.priceCoefficient) : item.priceCoefficient,
    priceFixedAdd: typeof batchForm.priceFixedAdd === 'number' ? Number(batchForm.priceFixedAdd) : item.priceFixedAdd,
    availablePlatforms: batchForm.availablePlatforms.length ? normalizeAvailablePlatforms(batchForm.availablePlatforms) : item.availablePlatforms,
    forbiddenPlatforms: [...batchForm.forbiddenPlatforms],
    priority: hasPositiveNumber(batchForm.priority) ? Number(batchForm.priority) : item.priority,
    timeoutSeconds: hasPositiveNumber(batchForm.timeoutSeconds) ? Number(batchForm.timeoutSeconds) : item.timeoutSeconds
  }
}

function handleSelectionChange(selection: RemoteGoods[]) {
  selectedGoods.value = selection
}

function removeDraft(id: string) {
  draftItems.value = draftItems.value.filter((item) => item.supplierGoodsId !== id)
}

function validateDraftSettings() {
  const missing = new Set<string>()
  draftItems.value.forEach((item) => {
    if (!item.categoryId) missing.add('本地分类')
    if (!item.status) missing.add('商品状态')
    if (!item.accountTypes?.length) missing.add('充值字段')
    if (typeof item.requireRechargeAccount !== 'boolean') missing.add('是否需要充值账号')
    if (!item.priceTemplateId) missing.add('价格模板')
    if (!item.availablePlatforms?.length) missing.add('可售平台')
  })
  if (missing.size) {
    ElMessage.warning(`请先设置待对接配置：${Array.from(missing).join('、')}`)
    return false
  }
  return true
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
    benefitDurations: item.benefitDurations || [],
    coverUrl: item.coverUrl?.trim(),
    description: item.description?.trim(),
    accountTypes: item.accountTypes,
    requireRechargeAccount: item.requireRechargeAccount === true,
    priceTemplateId: item.priceTemplateId,
    priceMode: item.priceMode,
    priceCoefficient: item.priceCoefficient,
    priceFixedAdd: item.priceFixedAdd,
    availablePlatforms: item.availablePlatforms || [],
    forbiddenPlatforms: item.forbiddenPlatforms || [],
    priority: Number(item.priority) || 10,
    timeoutSeconds: Number(item.timeoutSeconds) || 30
  }
}

async function handleBatchCoverUpload(file: UploadRawFile) {
  if (!validateImageFile(file)) return false
  try {
    const result = await uploadImage(file)
    batchForm.coverUrl = result.url
    ElMessage.success('主图已上传')
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '主图上传失败')
  }
  return false
}

async function handleDraftCoverUpload(row: SourceCloneDraft, file: UploadRawFile) {
  if (!validateImageFile(file)) return false
  try {
    const result = await uploadImage(file)
    row.coverUrl = result.url
    ElMessage.success('主图已上传')
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '主图上传失败')
  }
  return false
}

function validateImageFile(file: UploadRawFile) {
  if (!['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(file.type)) {
    ElMessage.warning('仅支持 JPG、PNG、WEBP、GIF 图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return false
  }
  return true
}

function openCoverPreview(url?: string, title = '商品主图预览') {
  if (!url) return
  coverPreviewUrl.value = url
  coverPreviewTitle.value = title
  coverPreviewVisible.value = true
}

function coverImageSrc(url?: string) {
  const value = url?.trim()
  if (!value) return ''
  if (/^(https?:|data:|blob:)/i.test(value)) return value
  if (value.startsWith('/')) {
    const configuredBaseUrl = import.meta.env.VITE_ADMIN_API_BASE_URL
    const baseUrl = import.meta.env.PROD
      ? window.location.origin
      : (configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : 'http://localhost:8080')
    return `${baseUrl.replace(/\/$/, '')}${value}`
  }
  return value
}

function draftCoverUploadHandler(row: SourceCloneDraft) {
  return (file: UploadRawFile) => handleDraftCoverUpload(row, file)
}

async function refreshPriceTemplates() {
  priceTemplates.value = await fetchPriceTemplates()
}

function handlePriceTemplateVisible(visible: boolean) {
  if (visible) void refreshPriceTemplates()
}

async function applyBatchPriceTemplate(templateId?: string) {
  await refreshPriceTemplates()
  const template = priceTemplateOptions.value.find((item) => item.id === templateId && item.enabled !== false)
  if (!template) return
  batchForm.priceTemplateId = template.id
  if (template.adjustMode === 'percent') {
    const firstRate = template.groupRates?.[0]?.value || 100
    batchForm.priceMode = 'DYNAMIC'
    batchForm.priceCoefficient = Number((firstRate / 100).toFixed(2))
    batchForm.priceFixedAdd = 0
  } else {
    batchForm.priceMode = 'FIXED'
    batchForm.priceCoefficient = 1
    batchForm.priceFixedAdd = Number(template.referencePrice) || 0
  }
}

function handleAvailablePlatformsChange(values: string[]) {
  if (values.includes(UNLIMITED_PLATFORM)) {
    batchForm.availablePlatforms = [UNLIMITED_PLATFORM]
  }
}

function normalizeAvailablePlatforms(values?: string[]) {
  if (!values?.length) return []
  if (values.includes(UNLIMITED_PLATFORM)) return platformOptions.map((item) => item.value)
  return values.filter((item) => item !== UNLIMITED_PLATFORM)
}

function inferBenefitDurations(title: string) {
  const normalizedTitle = title.toLowerCase().replace(/\s+/g, '')
  if (/15天|十五天|半月|半个月/.test(normalizedTitle)) return ['半月']
  if (/12个月|十二个月|年卡|一年/.test(normalizedTitle)) return ['一年']
  if (/半年|6个月|六个月/.test(normalizedTitle)) return ['半年']
  if (/3个月|三个月|季卡/.test(normalizedTitle)) return ['季卡']
  if (/1个月|一个月|月卡/.test(normalizedTitle)) return ['月卡']
  if (/7天|七天|周卡|一周/.test(normalizedTitle)) return ['周卡']
  if (/3天|三天/.test(normalizedTitle)) return ['三天']
  if (/1天|一天|日卡/.test(normalizedTitle)) return ['一天']
  return []
}

function numeric(value?: number | string) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function hasPositiveNumber(value?: number | string) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0
}

function formatMoney(value?: number | string) {
  return formatAmount(value, { currency: false })
}

function remoteGoodsCategoryLabel(item: RemoteGoods) {
  return item.categoryName || '分类未返回'
}

function localGoodsLabel(item: RemoteGoods) {
  if (!item.connected) return '-'
  return [item.localGoodsName, item.localGoodsId ? `ID ${item.localGoodsId}` : '', item.channelId ? `渠道 ${item.channelId}` : '']
    .filter(Boolean)
    .join(' · ')
}

function draftCategoryLabel(categoryId?: number | string) {
  if (!categoryId) return '未设置分类'
  const targetId = String(categoryId)
  const queue = [...categories.value]
  while (queue.length) {
    const item = queue.shift()
    if (!item) continue
    if (String(item.id) === targetId) return item.name
    queue.push(...(item.children || []))
  }
  return '已选分类'
}

function draftStatusLabel(status?: string) {
  if (status === 'ON_SALE') return '上架'
  if (status === 'OFF_SALE') return '下架'
  return '未设置状态'
}

function draftTemplateLabel(templateId?: string) {
  if (!templateId) return '未设置模板'
  return priceTemplateOptions.value.find((item) => item.id === templateId)?.name || '已选模板'
}

function optionLabels(values: string[] | undefined, options: Array<{ label: string, value: string }>, emptyLabel: string) {
  if (!values?.length) return emptyLabel
  return values
    .map((value) => options.find((item) => item.value === value)?.label || value)
    .slice(0, 2)
    .join('、') + (values.length > 2 ? `等${values.length}项` : '')
}

function accountFieldLabels(values?: string[]) {
  if (!values?.length) return '未设置字段'
  return values
    .map((value) => accountFieldOptions.value.find((item) => item.code === value)?.label || value)
    .slice(0, 2)
    .join('、') + (values.length > 2 ? `等${values.length}项` : '')
}

function remoteTypeClass(typeLabel?: string) {
  if (typeLabel === '直充') return 'remote-badge--recharge'
  if (typeLabel === '卡密') return 'remote-badge--card'
  return 'remote-badge--unknown'
}

function connectStatusClass(connected: boolean) {
  return connected ? 'remote-badge--connected' : 'remote-badge--unconnected'
}

function upstreamStatusLabel(status?: string | number) {
  const value = String(status ?? '').trim()
  if (value === '1' || value.toUpperCase() === 'ON_SALE' || value.toUpperCase() === 'ENABLED') return '上架'
  if (value === '0' || value === '2' || value.toUpperCase() === 'OFF_SALE' || value.toUpperCase() === 'DISABLED') return '下架'
  return value || '未知'
}

function upstreamStatusClass(status?: string | number) {
  const label = upstreamStatusLabel(status)
  if (label === '上架') return 'remote-badge--on-sale'
  if (label === '下架') return 'remote-badge--off-sale'
  return 'remote-badge--unknown'
}

function statusTone(status: string) {
  if (status === 'CREATED') return 'success'
  if (status === 'SKIPPED') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function buildCategoryTree(items: Category[]) {
  const byId = new Map<string, Category & { children: Category[] }>()
  items.forEach((item) => {
    byId.set(String(item.id), { ...item, children: [...(item.children || [])] })
  })

  const roots: Array<Category & { children: Category[] }> = []
  byId.forEach((item) => {
    const parentId = item.parentId ? String(item.parentId) : ''
    const parent = parentId ? byId.get(parentId) : undefined
    if (parent) parent.children.push(item)
    else roots.push(item)
  })
  return roots.length ? roots : items
}
</script>

<template>
  <section class="source-connect-shell">
    <div class="source-command liquid-admin-panel">
      <div class="command-title">
        <span>Source Connect</span>
        <h2>货源对接</h2>
        <p>按顺序完成拉取、选择、配置和创建，本地商品与上游渠道会同步生成。</p>
      </div>
      <div class="workflow-strip" aria-label="货源对接流程">
        <article>
          <strong>01</strong>
          <span>获取商品</span>
        </article>
        <article>
          <strong>02</strong>
          <span>选择生成</span>
        </article>
        <article>
          <strong>03</strong>
          <span>配置对接</span>
        </article>
      </div>
      <div class="command-metrics" aria-label="货源对接当前状态">
        <article>
          <span>上游商品</span>
          <strong>{{ remoteTotal }}</strong>
        </article>
        <article>
          <span>已选择</span>
          <strong>{{ selectedGoods.length }}</strong>
        </article>
        <article>
          <span>待对接</span>
          <strong>{{ draftItems.length }}</strong>
        </article>
      </div>
    </div>

    <section class="source-filter panel liquid-admin-panel">
      <div class="section-title">
        <div>
          <small class="stage-kicker">步骤 01</small>
          <h2>获取上游商品</h2>
          <span>{{ remoteRangeLabel }}</span>
        </div>
      </div>
      <div class="connect-form">
        <label class="field supplier-field">
          <span>供应商</span>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商">
            <el-option v-for="supplier in enabledSuppliers" :key="supplier.id" :label="supplier.name" :value="String(supplier.id)" />
          </el-select>
        </label>
        <label class="field search-field">
          <span>搜索关键词</span>
          <el-input v-model="form.keyword" :prefix-icon="Search" placeholder="按上游商品名搜索" @keyup.enter="searchRemoteGoods" />
        </label>
        <div class="fetch-action-cell">
          <el-button class="flow-action flow-action--fetch" type="primary" :icon="RefreshCw" :loading="loading" @click="searchRemoteGoods">获取上游商品</el-button>
        </div>
        <label class="field compact-field">
          <span>默认优先级</span>
          <el-input-number v-model="form.priority" :min="1" :step="10" :controls="false" />
        </label>
        <label class="field compact-field">
          <span>默认下单超时</span>
          <el-input-number v-model="form.timeoutSeconds" :min="5" :step="5" :controls="false" />
        </label>
      </div>
    </section>

    <section class="content-grid">
      <article class="remote-panel panel liquid-admin-panel">
        <div class="section-title remote-head">
          <div class="remote-title-actions">
            <div class="remote-head-main">
              <small class="stage-kicker">步骤 02</small>
              <h2>上游商品</h2>
              <span>{{ remoteRangeLabel }}，已选 {{ selectedGoods.length }} 个</span>
            </div>
          </div>
          <div class="remote-page-actions">
            <div class="remote-generate-actions">
              <el-button class="flow-action flow-action--generate" type="primary" :icon="Settings2" :disabled="!canPrepare" @click="prepareDrafts">批量创建对接</el-button>
            </div>
            <div class="remote-pagination-actions">
              <el-button size="small" :disabled="loading || form.page <= 1" @click="loadPreviousRemotePage">上一页</el-button>
              <span>第 {{ form.page }} / {{ remotePageCount }} 页</span>
              <el-button size="small" :disabled="loading || form.page >= remotePageCount || !remoteTotal" @click="loadNextRemotePage">下一页</el-button>
            </div>
          </div>
        </div>
        <el-table
          class="remote-table"
          v-loading="loading"
          :data="remoteGoods"
          height="560"
          style="width: 100%"
          row-key="supplierGoodsId"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="44" align="center" header-align="center" />
          <el-table-column label="上游商品" min-width="560" align="left" header-align="left">
            <template #default="{ row }">
              <div class="goods-cell">
                <span class="remote-goods-name">{{ row.name }}</span>
                <small>
                  ID {{ row.supplierGoodsId }}
                  <em>/</em>
                  {{ remoteGoodsCategoryLabel(row) }}
                </small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="价格 / 库存" width="150" align="left" header-align="left">
            <template #default="{ row }">
              <div class="number-stack">
                <strong>{{ formatMoney(row.price) }}</strong>
                <span>库存 {{ row.stock ?? '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="商品类型" width="108" class-name="remote-center-cell" align="center" header-align="center">
            <template #default="{ row }">
              <el-tag class="remote-badge" :class="remoteTypeClass(row.typeLabel)" effect="plain">{{ row.typeLabel || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对接状态" width="96" class-name="remote-center-cell" align="center" header-align="center">
            <template #default="{ row }">
              <el-tag class="remote-badge" :class="connectStatusClass(row.connected)" effect="plain">{{ row.connected ? '已对接' : '未对接' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="本地商品" min-width="220" align="left" header-align="left">
            <template #default="{ row }">
              <span class="muted-line">{{ localGoodsLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" class-name="remote-center-cell" align="center" header-align="center">
            <template #default="{ row }">
              <el-tag class="remote-badge" :class="upstreamStatusClass(row.status)" effect="plain">{{ upstreamStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="remote-pagination">
          <el-pagination
            v-model:current-page="form.page"
            v-model:page-size="form.limit"
            :page-sizes="[20, 50, 100]"
            :total="remoteTotal"
            layout="total, sizes, prev, pager, next, jumper"
            background
            :disabled="loading || !remoteTotal"
            @size-change="handleRemoteSizeChange"
            @current-change="handleRemotePageChange"
          />
        </div>
      </article>
    </section>

    <section class="batch-panel panel liquid-admin-panel">
      <div class="section-title">
        <div>
          <small class="stage-kicker">步骤 03</small>
          <h2>批量设置</h2>
          <span>先统一默认值，再生成待对接配置；主图可空，其余关键项需要设置。</span>
        </div>
        <el-button :icon="Layers3" :disabled="!draftItems.length" @click="applyBatch">应用到全部</el-button>
      </div>
      <div class="batch-compact">
        <article class="batch-cover-compact">
          <button
            type="button"
            class="source-cover-tile source-cover-tile--compact source-cover-preview-button"
            :class="{ empty: !batchForm.coverUrl }"
            :disabled="!batchForm.coverUrl"
            @click="openCoverPreview(batchForm.coverUrl, '批量设置主图预览')"
          >
            <img v-if="batchForm.coverUrl" :src="coverImageSrc(batchForm.coverUrl)" alt="商品主图" />
            <span v-else class="cover-empty-state">
              <ImagePlus :size="18" />
              <small>暂无主图</small>
            </span>
          </button>
          <div>
            <strong>商品主图</strong>
            <span>{{ batchForm.coverUrl ? '点击预览' : '可不设置' }}</span>
          </div>
          <el-upload :before-upload="handleBatchCoverUpload" :show-file-list="false" :accept="imageAccept">
            <el-button size="small" :icon="batchForm.coverUrl ? ImagePlus : UploadIcon">{{ batchForm.coverUrl ? '更换' : '上传' }}</el-button>
          </el-upload>
        </article>

        <article class="batch-group batch-group--basis">
          <div class="batch-fields two-col">
              <label class="field">
                <span>本地分类</span>
                <el-tree-select
                  class="category-tree-select"
                  v-model="batchForm.categoryId"
                  :data="categoryTreeOptions"
                  node-key="id"
                  check-strictly
                  filterable
                  clearable
                  default-expand-all
                  :render-after-expand="false"
                  popper-class="xiyiyun-tree-select-popper"
                  placeholder="选择本地分类"
                  :props="{ label: 'name', children: 'children', value: 'id' }"
                />
              </label>
              <label class="field">
                <span>商品状态</span>
                <el-select v-model="batchForm.status">
                  <el-option label="上架" value="ON_SALE" />
                  <el-option label="下架" value="OFF_SALE" />
                </el-select>
              </label>
            </div>
        </article>

          <article class="batch-group batch-group--fulfillment">
            <div class="batch-fields">
              <label class="field">
                <span>充值字段</span>
                <el-select
                  v-model="batchForm.accountTypes"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  filterable
                  placeholder="选择本地字段库字段"
                >
                  <el-option v-for="field in accountFieldOptions" :key="field.code" :label="field.label" :value="field.code" />
                </el-select>
              </label>
              <label class="switch-line">
                <span>需要充值账号</span>
                <el-select v-model="batchForm.requireRechargeAccount" placeholder="请选择">
                  <el-option label="需要" :value="true" />
                  <el-option label="不需要" :value="false" />
                </el-select>
              </label>
            </div>
          </article>

          <article class="batch-group batch-group--channel">
            <div class="batch-fields three-col">
              <label class="field">
                <span>价格模板</span>
                <el-select
                  v-model="batchForm.priceTemplateId"
                  filterable
                  placeholder="选择价格模板"
                  @visible-change="handlePriceTemplateVisible"
                  @change="applyBatchPriceTemplate"
                >
                  <el-option
                    v-for="template in priceTemplateOptions"
                    :key="template.id"
                    :label="template.enabled === false ? `${template.name}（停用）` : template.name"
                    :value="template.id"
                    :disabled="template.enabled === false"
                  />
                </el-select>
              </label>
              <label class="field">
                <span>可售平台</span>
                <el-select
                  v-model="batchForm.availablePlatforms"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  filterable
                  placeholder="选择可售平台"
                  @change="handleAvailablePlatformsChange"
                >
                  <el-option v-for="platform in salePlatformOptions" :key="platform.value" :label="platform.label" :value="platform.value" />
                </el-select>
              </label>
              <label class="field">
                <span>不可售平台</span>
                <el-select
                  v-model="batchForm.forbiddenPlatforms"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  filterable
                  placeholder="选择不可售平台"
                >
                  <el-option v-for="platform in platformOptions" :key="platform.value" :label="platform.label" :value="platform.value" />
                </el-select>
              </label>
              <label class="field compact-field">
                <span>优先级</span>
                <el-input-number v-model="batchForm.priority" :min="1" :step="10" :controls="false" />
              </label>
              <label class="field compact-field">
                <span>下单超时(秒)</span>
                <el-input-number v-model="batchForm.timeoutSeconds" :min="5" :step="5" :controls="false" />
              </label>
            </div>
          </article>
      </div>
    </section>

    <section class="dock-grid">
      <article ref="draftPanelRef" class="draft-panel panel liquid-admin-panel">
        <div class="section-title">
          <div>
            <small class="stage-kicker">提交前确认</small>
            <h2>待对接配置</h2>
            <span>共 {{ draftItems.length }} 个，可在提交前手动修改每个商品的基础信息。</span>
          </div>
          <el-button type="primary" :icon="Wand2" :disabled="!canClone" :loading="cloning" @click="runClone">一键对接</el-button>
        </div>
        <el-table ref="draftTableRef" class="draft-table draft-table--compact" :data="draftItems" height="560" style="width: 100%" row-key="supplierGoodsId">
          <el-table-column type="expand" width="42" fixed="left">
            <template #default="{ row }">
              <div class="draft-expand-panel">
                <section>
                  <strong>商品与价格</strong>
                  <div class="draft-expand-grid draft-expand-grid--product">
                    <label class="draft-mini-field draft-mini-field--wide">
                      <span>商品名称</span>
                      <el-input v-model="row.name" type="textarea" :autosize="{ minRows: 2, maxRows: 3 }" />
                    </label>
                    <label class="draft-mini-field">
                      <span>售价</span>
                      <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" />
                    </label>
                    <label class="draft-mini-field">
                      <span>面值</span>
                      <el-input-number v-model="row.originalPrice" :min="0" :precision="2" :controls="false" />
                    </label>
                    <label class="draft-mini-field">
                      <span>库存</span>
                      <el-input-number v-model="row.stock" :min="0" :controls="false" />
                    </label>
                  </div>
                </section>

                <section>
                  <strong>基础配置</strong>
                  <div class="draft-expand-grid">
                    <label class="draft-mini-field">
                      <span>本地分类</span>
                      <el-tree-select
                        class="category-tree-select"
                        v-model="row.categoryId"
                        :data="categoryTreeOptions"
                        node-key="id"
                        check-strictly
                        filterable
                        clearable
                        default-expand-all
                        :render-after-expand="false"
                        popper-class="xiyiyun-tree-select-popper"
                        placeholder="默认分类"
                        :props="{ label: 'name', children: 'children', value: 'id' }"
                      />
                    </label>
                    <label class="draft-mini-field">
                      <span>商品状态</span>
                      <el-select v-model="row.status" placeholder="选择状态">
                        <el-option label="上架" value="ON_SALE" />
                        <el-option label="下架" value="OFF_SALE" />
                      </el-select>
                    </label>
                    <label class="draft-mini-field">
                      <span>价格模板</span>
                      <el-select
                        v-model="row.priceTemplateId"
                        filterable
                        placeholder="选择价格模板"
                        @visible-change="handlePriceTemplateVisible"
                      >
                        <el-option
                          v-for="template in priceTemplateOptions"
                          :key="template.id"
                          :label="template.enabled === false ? `${template.name}（停用）` : template.name"
                          :value="template.id"
                          :disabled="template.enabled === false"
                        />
                      </el-select>
                    </label>
                  </div>
                </section>

                <section>
                  <strong>履约与平台</strong>
                  <div class="draft-expand-grid">
                    <label class="draft-mini-field">
                      <span>权益时间</span>
                      <el-select
                        v-model="row.benefitDurations"
                        multiple
                        collapse-tags
                        collapse-tags-tooltip
                        filterable
                        placeholder="选择权益时间"
                      >
                        <el-option v-for="item in benefitDurationOptions" :key="item" :label="item" :value="item" />
                      </el-select>
                    </label>
                    <label class="draft-mini-field">
                      <span>充值字段</span>
                      <el-select
                        v-model="row.accountTypes"
                        multiple
                        collapse-tags
                        collapse-tags-tooltip
                        filterable
                        placeholder="选择字段"
                      >
                        <el-option v-for="field in accountFieldOptions" :key="field.code" :label="field.label" :value="field.code" />
                      </el-select>
                    </label>
                    <label class="draft-mini-field">
                      <span>充值账号</span>
                      <el-select v-model="row.requireRechargeAccount" placeholder="请选择">
                        <el-option label="需要" :value="true" />
                        <el-option label="不需要" :value="false" />
                      </el-select>
                    </label>
                    <label class="draft-mini-field">
                      <span>可售平台</span>
                      <el-select
                        v-model="row.availablePlatforms"
                        multiple
                        collapse-tags
                        collapse-tags-tooltip
                        filterable
                        placeholder="可售平台"
                      >
                        <el-option v-for="platform in platformOptions" :key="platform.value" :label="platform.label" :value="platform.value" />
                      </el-select>
                    </label>
                    <label class="draft-mini-field">
                      <span>不可售平台</span>
                      <el-select
                        v-model="row.forbiddenPlatforms"
                        multiple
                        collapse-tags
                        collapse-tags-tooltip
                        filterable
                        placeholder="不可售平台"
                      >
                        <el-option v-for="platform in platformOptions" :key="platform.value" :label="platform.label" :value="platform.value" />
                      </el-select>
                    </label>
                    <div class="source-cover-upload source-cover-upload--row">
                      <button
                        v-if="row.coverUrl"
                        type="button"
                        class="source-cover-tile source-cover-tile--small source-cover-preview-button"
                        @click="openCoverPreview(row.coverUrl, row.name)"
                      >
                        <img :src="coverImageSrc(row.coverUrl)" alt="商品主图" />
                      </button>
                      <div v-else class="source-cover-tile source-cover-tile--small source-cover-tile--empty">
                        <ImagePlus :size="15" />
                      </div>
                      <el-upload :before-upload="draftCoverUploadHandler(row)" :show-file-list="false" :accept="imageAccept">
                        <el-button size="small" :icon="row.coverUrl ? ImagePlus : UploadIcon">{{ row.coverUrl ? '更换主图' : '上传主图' }}</el-button>
                      </el-upload>
                    </div>
                  </div>
                </section>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="商品信息" min-width="360" fixed="left">
            <template #default="{ row }">
              <div class="draft-summary-name">
                <small>上游 ID {{ row.supplierGoodsId }}</small>
                <strong>{{ row.name }}</strong>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="价格 / 库存" width="150">
            <template #default="{ row }">
              <div class="draft-price-summary">
                <strong>{{ formatMoney(row.price) }}</strong>
                <span>面值 {{ formatMoney(row.originalPrice) }} · 库存 {{ row.stock ?? 0 }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="基础配置" min-width="260">
            <template #default="{ row }">
              <div class="draft-chip-line">
                <span :class="{ muted: !row.categoryId }">{{ draftCategoryLabel(row.categoryId) }}</span>
                <span :class="{ muted: !row.status }">{{ draftStatusLabel(row.status) }}</span>
                <span :class="{ muted: !row.priceTemplateId }">{{ draftTemplateLabel(row.priceTemplateId) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="履约配置" min-width="260">
            <template #default="{ row }">
              <div class="draft-chip-line">
                <span :class="{ muted: !row.benefitDurations?.length }">{{ row.benefitDurations?.join('、') || '未设置权益' }}</span>
                <span :class="{ muted: !row.accountTypes?.length }">{{ accountFieldLabels(row.accountTypes) }}</span>
                <span :class="{ muted: typeof row.requireRechargeAccount !== 'boolean' }">
                  {{ typeof row.requireRechargeAccount === 'boolean' ? (row.requireRechargeAccount ? '需要账号' : '不需要账号') : '未设置账号' }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="平台 / 主图" min-width="230">
            <template #default="{ row }">
              <div class="draft-cover-summary">
                <div class="draft-chip-line">
                  <span :class="{ muted: !row.availablePlatforms?.length }">{{ optionLabels(row.availablePlatforms, platformOptions, '未设置平台') }}</span>
                  <span>{{ row.forbiddenPlatforms?.length ? `禁售 ${row.forbiddenPlatforms.length} 项` : '无禁售限制' }}</span>
                </div>
                <button
                  v-if="row.coverUrl"
                  type="button"
                  class="source-cover-tile source-cover-tile--preview source-cover-preview-button"
                  @click="openCoverPreview(row.coverUrl, row.name)"
                >
                  <img :src="coverImageSrc(row.coverUrl)" alt="商品主图" />
                </button>
                <div v-else class="source-cover-tile source-cover-tile--preview source-cover-tile--empty">
                  <ImagePlus :size="16" />
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="130" fixed="right" align="center" header-align="center">
            <template #default="{ row }">
              <div class="draft-row-actions">
                <el-button size="small" @click="toggleDraftRow(row)">编辑</el-button>
                <el-button class="draft-remove-btn" text type="danger" @click="removeDraft(row.supplierGoodsId)">移除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </article>

      <aside class="result-panel panel liquid-admin-panel">
        <div class="section-title">
          <div>
            <small class="stage-kicker">执行反馈</small>
            <h2>对接结果</h2>
            <span>{{ cloneResult ? `成功 ${cloneResult.createdCount}，跳过 ${cloneResult.skippedCount}，失败 ${cloneResult.failedCount}` : '等待操作' }}</span>
          </div>
        </div>
        <div v-if="cloneResult" class="result-summary">
          <article>
            <span>创建</span>
            <strong>{{ cloneResult.createdCount }}</strong>
          </article>
          <article>
            <span>跳过</span>
            <strong>{{ cloneResult.skippedCount }}</strong>
          </article>
          <article>
            <span>失败</span>
            <strong>{{ cloneResult.failedCount }}</strong>
          </article>
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
      </aside>
    </section>

    <el-dialog
      v-model="coverPreviewVisible"
      :title="coverPreviewTitle"
      width="min(720px, calc(100vw - 48px))"
      class="xiyiyun-glass-dialog cover-preview-dialog"
      align-center
    >
      <div class="cover-preview-dialog-body">
        <img :src="coverImageSrc(coverPreviewUrl)" alt="商品主图预览" />
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.source-connect-shell {
  display: grid;
  gap: 12px;
  color: rgba(255, 255, 255, 0.82);
}

.panel {
  padding: 16px;
  border-radius: 18px;
  overflow: hidden;
}

.source-command {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(360px, auto) auto;
  gap: 14px;
  align-items: center;
  padding: 16px 18px;
  overflow: hidden;
  border-radius: 22px;
}

.source-command::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(0, 255, 195, 0.12), transparent 34%),
    linear-gradient(270deg, rgba(88, 166, 255, 0.16), transparent 40%);
}

.command-title,
.command-metrics,
.workflow-strip,
.section-title,
.remote-page-actions,
.result-feed article div {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.command-title {
  display: grid;
  justify-content: stretch;
  gap: 4px;
}

.command-title span,
.section-title span,
.remote-page-actions span,
.result-feed small,
.goods-cell small,
.draft-name-cell small {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.command-title h2,
.section-title h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.94);
  font-size: 18px;
  line-height: 1.2;
}

.command-title p {
  max-width: 58ch;
  margin: 2px 0 0;
  color: rgba(255, 255, 255, 0.58);
  font-size: 13px;
  line-height: 1.6;
}

.workflow-strip {
  justify-content: center;
}

.workflow-strip article {
  display: grid;
  grid-template-columns: auto auto;
  gap: 8px;
  align-items: center;
  min-width: 108px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 0.5px solid rgba(189, 248, 220, 0.12);
  background: rgba(255, 255, 255, 0.045);
}

.workflow-strip strong {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 8px;
  color: rgba(189, 248, 220, 0.94);
  background: rgba(0, 255, 195, 0.1);
  font-size: 11px;
}

.workflow-strip span {
  color: rgba(255, 255, 255, 0.68);
  font-size: 12px;
  white-space: nowrap;
}

.command-metrics {
  justify-content: flex-end;
}

.command-metrics article,
.result-summary article {
  min-width: 82px;
  padding: 9px 11px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.055);
}

.command-metrics article {
  display: grid;
  gap: 5px;
}

.command-metrics span,
.result-summary span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.command-metrics strong,
.result-summary strong {
  color: rgba(255, 255, 255, 0.94);
  font-size: 18px;
  line-height: 1;
}

.source-filter {
  padding: 16px;
}

.section-title {
  margin-bottom: 12px;
}

.section-title--action-left,
.remote-head {
  justify-content: space-between;
}

.section-title > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.stage-kicker {
  color: rgba(189, 248, 220, 0.72);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

.connect-form {
  display: grid;
  grid-template-columns: 184px minmax(320px, 1fr) auto 132px 148px;
  gap: 12px;
  align-items: end;
}

.field,
.switch-line,
.batch-cover-compact {
  display: grid;
  gap: 7px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  min-width: 0;
}

.compact-field :deep(.el-input-number) {
  width: 100%;
}

.fetch-action-cell {
  display: flex;
  align-items: end;
}

.flow-action.el-button {
  min-height: 38px;
  padding: 0 16px;
  border: 0.5px solid rgba(189, 248, 220, 0.55);
  color: rgba(4, 18, 18, 0.94);
  font-weight: 800;
  background: linear-gradient(135deg, rgba(189, 248, 220, 0.98), rgba(62, 207, 142, 0.94));
  box-shadow: 0 12px 28px rgba(0, 255, 195, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.42);
}

.flow-action.el-button:hover {
  border-color: rgba(220, 255, 237, 0.76);
  color: rgba(4, 18, 18, 0.98);
  background: linear-gradient(135deg, rgba(220, 255, 237, 0.98), rgba(79, 224, 157, 0.96));
}

.flow-action.el-button.is-disabled,
.flow-action.el-button.is-disabled:hover {
  opacity: 0.62;
  color: rgba(4, 18, 18, 0.78);
  background: linear-gradient(135deg, rgba(189, 248, 220, 0.78), rgba(62, 207, 142, 0.7));
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.dock-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 12px;
  align-items: start;
}

.remote-panel,
.result-panel,
.batch-panel,
.draft-panel {
  min-width: 0;
}

.draft-panel {
  scroll-margin-top: 18px;
}

.remote-page-actions {
  display: flex;
  align-items: center;
  gap: 36px;
  justify-content: flex-end;
  margin-left: auto;
  width: 286px;
  min-width: 0;
  white-space: nowrap;
}

.remote-generate-actions,
.remote-pagination-actions {
  display: grid;
  grid-auto-flow: column;
  align-items: center;
  padding: 5px;
  border-radius: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
}

.remote-generate-actions {
  grid-template-columns: minmax(0, 1fr);
  width: 100%;
}

.remote-pagination-actions {
  grid-template-columns: 72px 90px 72px;
  gap: 6px;
}

.remote-title-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.remote-head-main {
  display: grid;
  grid-template-columns: auto;
  column-gap: 12px;
  row-gap: 3px;
  align-items: center;
}

.remote-generate-actions .flow-action {
  width: 100%;
  min-height: 28px;
  padding: 0 12px;
}

.goods-cell {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.goods-cell small {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  line-height: 1.4;
}

.goods-cell em {
  color: rgba(255, 255, 255, 0.22);
  font-style: normal;
}

.remote-goods-name {
  display: block;
  max-width: 100%;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.55;
  white-space: normal;
  word-break: break-word;
}

.remote-panel :deep(.el-table__header-wrapper th),
.draft-panel :deep(.el-table__header-wrapper th) {
  font-size: 12px;
  font-weight: 700;
}

.remote-panel :deep(.el-table__header-wrapper th) {
  font-size: 13px;
  font-weight: 750;
  line-height: 1.2;
  color: rgba(225, 243, 255, 0.72);
}

.muted-line {
  display: block;
  color: rgba(255, 255, 255, 0.62);
  line-height: 1.5;
  white-space: normal;
  word-break: break-word;
}

.number-stack {
  display: grid;
  gap: 6px;
}

.number-stack strong {
  color: rgba(194, 255, 226, 0.98);
  font-size: 16px;
  font-weight: 850;
  line-height: 1;
  text-shadow: 0 0 16px rgba(0, 255, 195, 0.18);
}

.number-stack span {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
}

.remote-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
  overflow-x: auto;
}

.result-panel {
  min-height: 560px;
}

.result-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.result-summary article {
  min-width: 0;
  padding: 10px;
}

.result-summary strong {
  font-size: 18px;
}

.result-feed {
  display: grid;
  gap: 10px;
  max-height: 476px;
  overflow: auto;
  padding-right: 4px;
}

.result-feed article,
.empty {
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.result-feed article div {
  align-items: flex-start;
}

.result-feed strong {
  min-width: 0;
  color: rgba(255, 255, 255, 0.88);
  line-height: 1.45;
  word-break: break-word;
}

.result-feed p {
  margin: 8px 0 4px;
  color: rgba(255, 255, 255, 0.68);
  line-height: 1.55;
}

.empty {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 64px;
  color: rgba(255, 255, 255, 0.56);
}

.batch-compact {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  align-items: stretch;
}

.batch-cover-compact {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  grid-template-rows: auto auto;
  gap: 8px 10px;
  align-items: center;
  padding: 10px;
  border-radius: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.04);
}

.batch-cover-compact strong,
.batch-group header {
  color: rgba(255, 255, 255, 0.74);
  font-size: 13px;
  font-weight: 700;
}

.batch-cover-compact span {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
}

.batch-cover-compact .el-upload {
  grid-column: 1 / -1;
}

.batch-cover-compact :deep(.el-button) {
  width: 100%;
}

.batch-group {
  display: grid;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.035);
}

.batch-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.batch-fields.two-col {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.batch-group--fulfillment {
  grid-column: 3;
}

.batch-group--channel {
  grid-column: 1 / -1;
}

.batch-fields.three-col {
  grid-template-columns: minmax(180px, 0.85fr) minmax(220px, 1fr) minmax(220px, 1fr) 118px 126px;
}

.switch-line {
  align-items: stretch;
}

.source-cover-upload {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
}

.source-cover-upload--row {
  min-height: 34px;
}

.source-cover-tile {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  place-items: center;
  overflow: hidden;
  border-radius: 8px;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
}

.source-cover-tile--compact {
  display: grid;
  width: 96px;
  height: 72px;
  place-items: center;
  color: rgba(189, 248, 220, 0.55);
  border-radius: 12px;
}

.source-cover-tile--compact.empty {
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.1), rgba(88, 166, 255, 0.12)),
    rgba(255, 255, 255, 0.045);
}

.source-cover-tile--small {
  width: 34px;
  height: 34px;
}

.source-cover-tile--preview {
  width: 48px;
  height: 48px;
  border-radius: 10px;
}

.source-cover-tile--empty {
  color: rgba(189, 248, 220, 0.46);
  border-style: dashed;
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.07), rgba(88, 166, 255, 0.08)),
    rgba(255, 255, 255, 0.035);
}

.source-cover-preview-button {
  padding: 0;
  cursor: pointer;
  appearance: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.source-cover-preview-button:disabled {
  cursor: default;
}

.source-cover-preview-button:not(:disabled):hover {
  border-color: rgba(189, 248, 220, 0.48);
  box-shadow: 0 0 0 3px rgba(0, 255, 195, 0.08), 0 12px 26px rgba(0, 0, 0, 0.24);
  transform: translateY(-1px);
}

.cover-empty-state {
  display: grid;
  gap: 5px;
  place-items: center;
  color: rgba(189, 248, 220, 0.58);
}

.cover-empty-state small {
  font-size: 11px;
}

.source-cover-tile img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.source-connect-shell :deep(.category-tree-select .el-select__wrapper) {
  min-height: 32px;
}

.draft-name-cell {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.draft-name-cell small {
  display: inline-flex;
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  color: rgba(189, 248, 220, 0.72);
  background: rgba(0, 255, 195, 0.075);
  line-height: 1;
}

.draft-inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.draft-inline-grid--prices {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.draft-mini-field--wide {
  grid-column: 1 / -1;
}

.draft-stack,
.draft-mini-field {
  display: grid;
  gap: 8px;
}

.draft-mini-field {
  min-width: 0;
}

.draft-mini-field > span {
  color: rgba(214, 236, 255, 0.56);
  font-size: 11px;
  font-weight: 750;
  line-height: 1;
}

.draft-summary-name {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.draft-summary-name small {
  width: fit-content;
  padding: 3px 8px;
  border-radius: 999px;
  color: rgba(189, 248, 220, 0.72);
  background: rgba(0, 255, 195, 0.075);
  font-size: 11px;
  line-height: 1;
}

.draft-summary-name strong {
  overflow: hidden;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  font-weight: 760;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-price-summary {
  display: grid;
  gap: 5px;
}

.draft-price-summary strong {
  color: rgba(194, 255, 226, 0.98);
  font-size: 16px;
  font-weight: 850;
  line-height: 1;
}

.draft-price-summary span,
.draft-chip-line span {
  color: rgba(255, 255, 255, 0.56);
  font-size: 12px;
}

.draft-chip-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.draft-chip-line span {
  max-width: 150px;
  overflow: hidden;
  padding: 4px 8px;
  border: 0.5px solid rgba(189, 248, 220, 0.14);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-chip-line span.muted {
  color: rgba(255, 255, 255, 0.34);
  border-color: rgba(255, 255, 255, 0.07);
  background: rgba(255, 255, 255, 0.025);
}

.draft-cover-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.draft-cover-summary .draft-chip-line {
  flex: 1 1 auto;
  min-width: 0;
}

.draft-cover-summary .source-cover-tile {
  flex: 0 0 48px;
}

.draft-row-actions {
  display: flex;
  justify-content: center;
  gap: 6px;
}

.draft-expand-panel {
  display: grid;
  gap: 12px;
  padding: 14px 18px 16px;
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.045), rgba(88, 166, 255, 0.06)),
    rgba(6, 15, 28, 0.62);
}

.draft-expand-panel section {
  display: grid;
  gap: 9px;
}

.draft-expand-panel section > strong {
  color: rgba(225, 243, 255, 0.74);
  font-size: 12px;
  font-weight: 850;
}

.draft-expand-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(150px, 1fr));
  gap: 10px;
}

.draft-expand-grid--product {
  grid-template-columns: minmax(260px, 2fr) repeat(3, minmax(120px, 0.7fr));
}

.remote-table :deep(.el-table__row),
.draft-table :deep(.el-table__row) {
  --el-table-row-hover-bg-color: rgba(0, 255, 195, 0.075);
}

.remote-table :deep(.el-table__inner-wrapper),
.remote-table :deep(.el-table__body-wrapper),
.remote-table :deep(.el-scrollbar),
.remote-table :deep(.el-scrollbar__wrap),
.remote-table :deep(.el-scrollbar__view),
.draft-table :deep(.el-table__inner-wrapper),
.draft-table :deep(.el-table__body-wrapper),
.draft-table :deep(.el-scrollbar),
.draft-table :deep(.el-scrollbar__wrap),
.draft-table :deep(.el-scrollbar__view) {
  background:
    radial-gradient(circle at 18% 0%, rgba(0, 255, 195, 0.08), transparent 30%),
    radial-gradient(circle at 82% 8%, rgba(88, 166, 255, 0.1), transparent 32%),
    rgba(8, 17, 31, 0.34) !important;
}

.remote-table :deep(.el-table__empty-block),
.draft-table :deep(.el-table__empty-block) {
  min-height: 420px;
  background:
    radial-gradient(circle at 50% 58%, rgba(255, 171, 0, 0.06), transparent 24%),
    rgba(8, 17, 31, 0.48);
}

.remote-table :deep(.el-table__empty-text),
.draft-table :deep(.el-table__empty-text) {
  color: rgba(255, 255, 255, 0.42);
  font-weight: 650;
}

.remote-table :deep(.el-loading-mask),
.draft-table :deep(.el-loading-mask) {
  background:
    radial-gradient(circle at center, rgba(88, 166, 255, 0.12), transparent 28%),
    rgba(8, 17, 31, 0.74) !important;
  backdrop-filter: blur(10px);
}

.remote-table :deep(.el-loading-spinner .path),
.draft-table :deep(.el-loading-spinner .path) {
  stroke: rgba(189, 248, 220, 0.96);
}

.remote-table :deep(.el-loading-text),
.draft-table :deep(.el-loading-text) {
  color: rgba(189, 248, 220, 0.86);
}

.remote-table :deep(.el-table__cell),
.draft-table :deep(.el-table__cell) {
  vertical-align: top;
}

.remote-table :deep(td.el-table__cell) {
  vertical-align: middle;
}

.remote-table :deep(th.el-table__cell .cell) {
  display: flex;
  align-items: center;
  min-height: 38px;
}

.remote-table :deep(th.is-left .cell) {
  justify-content: flex-start;
}

.remote-table :deep(th.is-center .cell) {
  justify-content: center;
}

.remote-table :deep(td.is-left .cell) {
  text-align: left;
}

.remote-table :deep(td.is-center .cell) {
  text-align: center;
}

.remote-table :deep(.el-table-column--selection .cell) {
  display: flex;
  justify-content: center;
  align-items: center;
}

.remote-table :deep(.el-table-column--selection .el-checkbox) {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.035);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.remote-table :deep(.el-table-column--selection .el-checkbox__input) {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
}

.remote-table :deep(.el-table-column--selection .el-checkbox__inner) {
  width: 18px;
  height: 18px;
  border: 0.5px solid rgba(189, 248, 220, 0.36);
  border-radius: 6px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.09), rgba(255, 255, 255, 0.035)),
    rgba(8, 17, 31, 0.7);
  box-shadow: 0 8px 18px rgba(0, 0, 0, 0.18), inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.remote-table :deep(.el-table-column--selection .el-checkbox__inner:hover) {
  border-color: rgba(189, 248, 220, 0.72);
  background: rgba(189, 248, 220, 0.12);
}

.remote-table :deep(.el-table-column--selection .el-checkbox__input.is-checked .el-checkbox__inner),
.remote-table :deep(.el-table-column--selection .el-checkbox__input.is-indeterminate .el-checkbox__inner) {
  border-color: rgba(189, 248, 220, 0.82);
  background: linear-gradient(135deg, rgba(29, 208, 157, 0.96), rgba(51, 145, 255, 0.9));
  box-shadow: 0 10px 24px rgba(0, 255, 195, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.36);
}

.remote-table :deep(.el-table-column--selection .el-checkbox__input.is-checked .el-checkbox__inner::after) {
  top: 50%;
  left: 50%;
  width: 4px;
  height: 9px;
  border-width: 2px;
  border-color: rgba(3, 18, 22, 0.96);
  transform: translate(-50%, -58%) rotate(45deg);
  transform-origin: center;
}

.remote-table :deep(.el-table-column--selection .el-checkbox__input.is-indeterminate .el-checkbox__inner::before) {
  top: 50%;
  left: 50%;
  right: auto;
  width: 10px;
  height: 2px;
  background: rgba(3, 18, 22, 0.96);
  transform: translate(-50%, -50%);
}

.remote-table :deep(.remote-center-cell .cell) {
  min-height: 44px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.remote-table :deep(td.remote-center-cell.el-table__cell) {
  vertical-align: middle;
}

.remote-table :deep(td.remote-center-cell.el-table__cell .cell) {
  height: 100%;
}

.draft-table :deep(.el-table__row) {
  height: 66px;
}

.draft-table :deep(.el-textarea__inner) {
  min-height: 58px !important;
  line-height: 1.45;
}

.draft-table :deep(.el-table__header-wrapper th) {
  color: rgba(225, 243, 255, 0.7);
  font-size: 12.5px;
  font-weight: 800;
  background: rgba(255, 255, 255, 0.03) !important;
}

.draft-table :deep(td.el-table__cell) {
  vertical-align: middle;
  padding: 8px 0;
}

.draft-table :deep(.el-table__cell .cell) {
  padding: 0 10px;
}

.draft-table :deep(.el-table__expanded-cell) {
  padding: 0 !important;
  background: transparent !important;
}

.draft-table :deep(.el-table__expand-icon) {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.055);
}

.draft-table :deep(.el-table__expand-icon .el-icon) {
  color: rgba(189, 248, 220, 0.78);
}

.draft-table :deep(.el-table__fixed-right),
.draft-table :deep(.el-table__fixed) {
  background: rgba(8, 17, 31, 0.9);
  box-shadow: -14px 0 28px rgba(5, 12, 24, 0.2);
}

.draft-table :deep(.el-input-number),
.draft-table :deep(.el-select),
.draft-table :deep(.el-tree-select) {
  width: 100%;
}

.draft-table :deep(.el-input__wrapper),
.draft-table :deep(.el-select__wrapper),
.draft-table :deep(.el-textarea__inner) {
  min-height: 32px;
  border-color: rgba(189, 248, 220, 0.12);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.025)),
    rgba(9, 22, 38, 0.64) !important;
}

.draft-table :deep(.el-input-number .el-input__inner) {
  text-align: right;
}

.draft-table :deep(.el-textarea__inner) {
  padding: 9px 10px;
  color: rgba(255, 255, 255, 0.9);
  font-weight: 650;
}

.draft-table :deep(.el-tag.el-tag--info) {
  max-width: 124px;
}

.draft-remove-btn.el-button {
  width: 54px;
  min-height: 32px;
  border-radius: 10px;
  background: rgba(255, 92, 118, 0.1);
}

.cover-preview-dialog-body {
  display: grid;
  min-height: 360px;
  place-items: center;
  overflow: hidden;
  border: 0.5px solid rgba(189, 248, 220, 0.14);
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.045), rgba(88, 166, 255, 0.07)),
    rgba(4, 12, 23, 0.72);
}

.cover-preview-dialog-body img {
  display: block;
  max-width: 100%;
  max-height: min(68vh, 620px);
  object-fit: contain;
}

:global(.cover-preview-dialog.el-dialog) {
  --el-dialog-bg-color: rgba(8, 17, 31, 0.94);
}

:global(.cover-preview-dialog .el-dialog__body) {
  padding-top: 4px;
}

.source-connect-shell :deep(.el-button) {
  border-radius: 10px;
}

.source-connect-shell :deep(.el-button--primary) {
  border-color: rgba(93, 171, 255, 0.6);
  background: linear-gradient(135deg, rgba(93, 171, 255, 0.95), rgba(47, 125, 246, 0.9));
}

.source-connect-shell :deep(.el-tag) {
  border-radius: 999px;
}

.source-connect-shell :deep(.remote-badge) {
  min-width: 48px;
  justify-content: center;
  border-width: 0.5px;
  font-weight: 800;
  letter-spacing: 0;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18), 0 8px 18px rgba(0, 0, 0, 0.16);
}

.source-connect-shell :deep(.remote-badge--recharge) {
  border-color: rgba(69, 209, 255, 0.42);
  background: linear-gradient(135deg, rgba(21, 95, 139, 0.92), rgba(42, 168, 222, 0.84));
  color: rgba(226, 248, 255, 0.96);
}

.source-connect-shell :deep(.remote-badge--card) {
  border-color: rgba(183, 137, 255, 0.48);
  background: linear-gradient(135deg, rgba(77, 48, 143, 0.94), rgba(137, 91, 224, 0.84));
  color: rgba(243, 235, 255, 0.96);
}

.source-connect-shell :deep(.remote-badge--connected) {
  border-color: rgba(93, 224, 153, 0.48);
  background: linear-gradient(135deg, rgba(23, 118, 72, 0.94), rgba(67, 191, 126, 0.86));
  color: rgba(226, 255, 239, 0.96);
}

.source-connect-shell :deep(.remote-badge--unconnected) {
  border-color: rgba(255, 132, 151, 0.5);
  background: linear-gradient(135deg, rgba(132, 45, 69, 0.94), rgba(218, 76, 112, 0.82));
  color: rgba(255, 234, 239, 0.96);
}

.source-connect-shell :deep(.remote-badge--on-sale) {
  border-color: rgba(94, 225, 168, 0.48);
  background: linear-gradient(135deg, rgba(21, 112, 83, 0.94), rgba(50, 190, 137, 0.84));
  color: rgba(225, 255, 243, 0.96);
}

.source-connect-shell :deep(.remote-badge--off-sale) {
  border-color: rgba(255, 118, 118, 0.5);
  background: linear-gradient(135deg, rgba(128, 38, 50, 0.94), rgba(214, 76, 88, 0.82));
  color: rgba(255, 232, 234, 0.96);
}

.source-connect-shell :deep(.remote-badge--unknown) {
  border-color: rgba(151, 164, 184, 0.42);
  background: linear-gradient(135deg, rgba(58, 72, 96, 0.92), rgba(92, 110, 139, 0.82));
  color: rgba(235, 241, 250, 0.92);
}

.source-connect-shell :deep(.el-input-number__decrease),
.source-connect-shell :deep(.el-input-number__increase) {
  display: none;
}

.source-connect-shell :deep(.el-input-number .el-input__wrapper) {
  padding-left: 11px;
  padding-right: 11px;
}

.source-connect-shell :deep(.el-tree-select) {
  width: 100%;
}

.source-connect-shell :deep(.el-select__selected-item),
.source-connect-shell :deep(.el-select__placeholder),
.source-connect-shell :deep(.el-input__inner),
.source-connect-shell :deep(.el-input-number .el-input__inner) {
  color: rgba(255, 255, 255, 0.92) !important;
  font-weight: 650;
}

.source-connect-shell :deep(.el-select__placeholder.is-transparent) {
  color: rgba(255, 255, 255, 0.42) !important;
  font-weight: 500;
}

.source-connect-shell :deep(.el-select__tags-text) {
  color: rgba(7, 22, 25, 0.92);
  font-weight: 750;
}

.source-connect-shell :deep(.el-tag.el-tag--info) {
  border-color: rgba(189, 248, 220, 0.44);
  background: rgba(189, 248, 220, 0.88);
  color: rgba(7, 22, 25, 0.92);
}

.source-connect-shell :deep(.el-input__wrapper:hover),
.source-connect-shell :deep(.el-select__wrapper:hover),
.source-connect-shell :deep(.el-input__wrapper.is-focus),
.source-connect-shell :deep(.el-select__wrapper.is-focused) {
  border-color: rgba(189, 248, 220, 0.44);
  background: rgba(255, 255, 255, 0.105) !important;
}

.source-connect-shell :deep(.el-pagination.is-background .el-pager li),
.source-connect-shell :deep(.el-pagination.is-background button) {
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.72);
}

.remote-pagination-actions :deep(.el-button) {
  width: 72px;
  min-height: 28px;
  margin-left: 0;
  padding: 0;
}

.remote-generate-actions :deep(.flow-action) {
  width: 100%;
  padding: 0 12px;
}

.remote-pagination-actions > span {
  width: 90px;
  min-width: 90px;
  text-align: center;
  color: rgba(255, 255, 255, 0.68);
}

:global(.xiyiyun-tree-select-popper) {
  border: 0.5px solid rgba(189, 248, 220, 0.18) !important;
  border-radius: 12px !important;
  background: rgba(10, 25, 43, 0.98) !important;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.36) !important;
}

:global(.xiyiyun-tree-select-popper .el-tree) {
  color: rgba(255, 255, 255, 0.82);
  background: transparent;
}

:global(.xiyiyun-tree-select-popper .el-tree-node__content:hover),
:global(.xiyiyun-tree-select-popper .el-tree-node.is-current > .el-tree-node__content) {
  color: rgba(189, 248, 220, 0.96);
  background: rgba(0, 255, 195, 0.1);
}

@media (max-width: 1280px) {
  .source-command,
  .connect-form,
  .dock-grid {
    grid-template-columns: 1fr 1fr;
  }

  .workflow-strip,
  .command-metrics {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .batch-compact,
  .batch-fields.three-col {
    grid-template-columns: 1fr 1fr;
  }

  .batch-group--fulfillment,
  .batch-group--channel {
    grid-column: auto;
  }

  .batch-cover-compact {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .source-command,
  .section-title {
    align-items: flex-start;
    flex-direction: column;
  }

  .source-command,
  .workflow-strip,
  .connect-form,
  .content-grid,
  .dock-grid,
  .batch-compact,
  .batch-fields.two-col,
  .batch-fields.three-col {
    grid-template-columns: 1fr;
  }

  .remote-head-main {
    grid-template-columns: 1fr;
  }

  .remote-title-actions,
  .remote-page-actions {
    width: 100%;
  }

  .remote-title-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .remote-page-actions {
    justify-content: flex-start;
    overflow-x: auto;
  }

  .command-metrics,
  .command-metrics article {
    flex: 1;
  }

  .workflow-strip article {
    width: 100%;
  }
}
</style>
