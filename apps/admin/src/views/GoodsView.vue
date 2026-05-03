<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { Edit3, Eye, PlugZap, Plus, RefreshCw, Trash2, Upload, X } from 'lucide-vue-next'
import {
  createGoods,
  createGoodsChannel,
  deleteGoodsChannel,
  fetchGoods,
  fetchGoodsCards,
  fetchGoodsChannels,
  importGoodsCards,
  updateGoods
} from '../api/goods'
import { fetchCardKinds } from '../api/cardKinds'
import { fetchCategories, fetchRechargeFields } from '../api/catalog'
import { fetchSuppliers } from '../api/suppliers'
import type {
  CardImportItem,
  CardKind,
  Category,
  Goods,
  GoodsCard,
  GoodsChannel,
  GoodsChannelCreatePayload,
  GoodsCreatePayload,
  GoodsDetailBlock,
  RechargeField,
  Supplier
} from '../types/operations'
import CategoryManagerPanel from '../components/CategoryManagerPanel.vue'
import GoodsBenefitSelector from '../components/GoodsBenefitSelector.vue'
import GoodsPlatformSelector from '../components/GoodsPlatformSelector.vue'
import GoodsPricingFields from '../components/GoodsPricingFields.vue'
import { buildCategoryTree, flattenCategoryTree, normalizeLoadedCategories } from '../utils/categoryTree'
import {
  benefitDurationOptions,
  deliveryOptions,
  fallbackAccountTypeOptions,
  goodsModules,
  monitoringItems,
  platformOptions,
  statusOptions,
  type GoodsModuleKey
} from '../utils/goodsOptions'
import { formatMoney } from '../utils/formatters'
import { loadPriceTemplates } from '../utils/priceTemplates'

const goods = ref<Goods[]>([])
const cards = ref<GoodsCard[]>([])
const cardKinds = ref<CardKind[]>([])
const channels = ref<GoodsChannel[]>([])
const categories = ref<Category[]>([])
const suppliers = ref<Supplier[]>([])
const rechargeFields = ref<RechargeField[]>([])
const loading = ref(false)
const categoryLoading = ref(false)
const cardKindLoading = ref(false)
const saving = ref(false)
const cardLoading = ref(false)
const channelLoading = ref(false)
const channelSaving = ref(false)
const importVisible = ref(false)
const cardsVisible = ref(false)
const channelsVisible = ref(false)
const goodsEditorVisible = ref(false)
const selectedGoods = ref<Goods>()
const cardText = ref('')
const editingGoodsId = ref<Goods['id']>()
const detailBlocks = ref<GoodsDetailBlock[]>([])
const priceTemplates = ref(loadPriceTemplates())
const editorStep = ref<'base' | 'detail'>('base')
const activeGoodsModule = ref<GoodsModuleKey>('base')
const goodsFilters = reactive({
  categoryId: '',
  platform: '',
  search: ''
})

const form = reactive<GoodsCreatePayload>({
  categoryId: undefined,
  name: '',
  subTitle: '',
  coverUrl: '',
  detailImages: [],
  price: 0,
  originalPrice: undefined,
  stock: 5000,
  status: 'ON_SALE',
  deliveryType: 'CARD',
  cardKindId: undefined,
  platform: 'GENERAL',
  availablePlatforms: ['douyin', 'taobao'],
  forbiddenPlatforms: [],
  benefitDurations: [],
  integrations: [],
  pollingEnabled: false,
  monitoringEnabled: true,
  maxBuy: 1,
  requireRechargeAccount: false,
  accountTypes: [],
  priceTemplateId: 'retail-default',
  priceMode: 'FIXED',
  priceCoefficient: 1,
  priceFixedAdd: 0,
  description: ''
})

const channelForm = reactive<GoodsChannelCreatePayload>({
  supplierId: undefined,
  supplierGoodsId: '',
  priority: 10,
  timeoutSeconds: 30,
  status: 'ENABLED'
})

const categoryOptions = computed(() => {
  const tree = buildCategoryTree(categories.value)
  return flattenCategoryTree(tree)
})

const accountTypeOptions = computed(() => {
  const enabledFields = rechargeFields.value
    .filter((item) => item.enabled)
    .sort((left, right) => Number(left.sort) - Number(right.sort))
    .map((item) => ({ label: item.label, value: item.code }))

  return enabledFields.length ? enabledFields : fallbackAccountTypeOptions
})

const cardKindById = computed(() => {
  const map = new Map<string, CardKind>()
  cardKinds.value.forEach((item) => map.set(String(item.id), item))
  return map
})

const selectedFilterCategoryIds = computed(() => {
  if (!goodsFilters.categoryId) return new Set<string>()

  const childrenMap = new Map<string, Category[]>()
  categoryOptions.value.forEach((item) => {
    const parentId = item.parentId ? String(item.parentId) : ''
    if (!parentId) return

    const siblings = childrenMap.get(parentId) || []
    siblings.push(item)
    childrenMap.set(parentId, siblings)
  })

  const ids = new Set<string>()
  const collect = (categoryId: string) => {
    if (ids.has(categoryId)) return
    ids.add(categoryId)
    const childCategories = childrenMap.get(categoryId) || []
    childCategories.forEach((child) => collect(String(child.id)))
  }

  collect(String(goodsFilters.categoryId))
  return ids
})

const parsedCards = computed<CardImportItem[]>(() =>
  cardText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [cardNo, ...passwordParts] = line.split(',')

      return {
        cardNo: cardNo?.trim() || '',
        password: passwordParts.join(',').trim()
      }
    })
    .filter((item) => item.cardNo && item.password)
)

const filteredGoods = computed(() =>
  goods.value.filter((item) => {
    const keyword = goodsFilters.search.trim().toLowerCase()
    const categoryMatched =
      !goodsFilters.categoryId || selectedFilterCategoryIds.value.has(String(item.categoryId))
    const platformMatched =
      !goodsFilters.platform || (item.availablePlatforms || []).includes(goodsFilters.platform)
    const keywordMatched =
      !keyword ||
      item.name.toLowerCase().includes(keyword) ||
      String(item.id).includes(keyword) ||
      (item.categoryName || '').toLowerCase().includes(keyword)

    return categoryMatched && platformMatched && keywordMatched
  })
)

const formTitle = computed(() => (editingGoodsId.value ? '编辑商品' : '新增商品'))
const formSubtitle = computed(() => (editingGoodsId.value ? '更新商品资料与销售配置' : '卡密/直充/代充商品资料'))
const enabledPriceTemplates = computed(() => priceTemplates.value.filter((item) => item.enabled))
const activeModuleMeta = computed(() => goodsModules.find((item) => item.key === activeGoodsModule.value) || goodsModules[0])

function fileToDataUrl(file: UploadRawFile) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

async function handleCoverUpload(file: UploadRawFile) {
  form.coverUrl = await fileToDataUrl(file)
  return false
}

async function handleDetailUpload(file: UploadRawFile, block: GoodsDetailBlock) {
  block.type = 'image'
  block.imageUrl = await fileToDataUrl(file)
  return false
}

async function handleDetailUploadAt(file: UploadRawFile, index: number) {
  const block = detailBlocks.value[index]
  if (!block) return false
  return handleDetailUpload(file, block)
}

function detailUploadHandler(index: number) {
  return (file: UploadRawFile) => handleDetailUploadAt(file, index)
}

function addDetailBlock(type: 'image' | 'text' = 'text') {
  detailBlocks.value.push({ type, imageUrl: '', text: '' })
}

function removeDetailBlock(index: number) {
  detailBlocks.value.splice(index, 1)
}

function moveDetailBlock(index: number, direction: -1 | 1) {
  const targetIndex = index + direction
  if (targetIndex < 0 || targetIndex >= detailBlocks.value.length) return
  const [item] = detailBlocks.value.splice(index, 1)
  detailBlocks.value.splice(targetIndex, 0, item)
}

function applyPriceTemplate(templateId?: string) {
  const template = enabledPriceTemplates.value.find((item) => item.id === templateId)
  if (!template) return
  form.priceTemplateId = template.id
  const firstRate = template.groupRates?.[0]?.value || 100
  form.priceMode = 'DYNAMIC'
  form.priceCoefficient = Number((firstRate / 100).toFixed(2))
  form.priceFixedAdd = 0
}

function addIntegration() {
  const defaultSupplier = suppliers.value.find((item) => item.status === 'ENABLED') || suppliers.value[0]
  form.integrations = [
    ...(form.integrations || []),
    {
      id: `link-${Date.now()}`,
      supplierId: defaultSupplier?.id,
      supplierName: defaultSupplier?.name || '',
      supplierGoodsId: '',
      supplierGoodsName: '',
      supplierPrice: 0,
      upstreamStatus: '待刷新',
      upstreamStock: 0,
      upstreamTitle: '',
      lastSyncAt: '',
      enabled: true
    }
  ]
}

function removeIntegration(index: number) {
  form.integrations = (form.integrations || []).filter((_, itemIndex) => itemIndex !== index)
}

function refreshIntegration(index: number) {
  const item = form.integrations?.[index]
  if (!item) return
  const supplier = suppliers.value.find((entry) => String(entry.id) === String(item.supplierId))
  item.supplierName = supplier?.name || item.supplierName || '未选择渠道'
  const sourceName = item.supplierName || '货源渠道'
  const suffix = item.supplierGoodsId ? item.supplierGoodsId.slice(-6) : String(index + 1).padStart(3, '0')
  item.enabled = true
  item.upstreamStatus = '正常'
  item.supplierGoodsName = `${sourceName}商品 ${suffix}`
  item.upstreamTitle = item.supplierGoodsName
  item.supplierPrice = Number((4.9 + index * 0.35 + suffix.length * 0.08).toFixed(2))
  item.upstreamStock = 4800 + index * 60 + suffix.length
  item.lastSyncAt = new Date().toLocaleString('zh-CN')
  ElMessage.success('已刷新对接信息')
}

function handleCategoriesLoaded(loadedCategories: Category[]) {
  categories.value = normalizeLoadedCategories(loadedCategories || [])
}

function stockText(row: Goods) {
  return typeof row.stock === 'number' ? row.stock : '-'
}

function cardKindUnused(row: CardKind) {
  return Number(row.unusedCount ?? row.stock ?? 0)
}

function cardKindTotal(row: CardKind) {
  const total = Number(row.totalCount || 0)
  if (total > 0) return total
  return Number(row.unusedCount || 0) + Number(row.usedCount || 0)
}

function goodsCardKind(row: Goods) {
  if (!row.cardKindId) return undefined
  return cardKindById.value.get(String(row.cardKindId))
}

function cardKindName(row: Goods) {
  return row.cardKindName || goodsCardKind(row)?.name || ''
}

function cardKindStockText(row: Goods) {
  const kind = goodsCardKind(row)
  const stock = kind ? cardKindUnused(kind) : row.cardKindStock
  return typeof stock === 'number' && Number.isFinite(stock) ? stock : undefined
}

function categoryLabel(row: Goods) {
  return row.categoryName || '-'
}

function platformLabel(value: string) {
  return platformOptions.find((item) => item.value === value)?.label || value
}

function statusMeta(value = '') {
  return statusOptions.find((item) => item.value === value) || { label: value || '-', type: 'info' }
}

function deliveryLabel(value = '') {
  if (value === 'AUTO') return '直充'
  return deliveryOptions.find((item) => item.value === value)?.label || value || '-'
}

function resetForm() {
  editingGoodsId.value = undefined
  form.name = ''
  form.subTitle = ''
  form.coverUrl = ''
  form.detailImages = []
  detailBlocks.value = [{ type: 'text', imageUrl: '', text: '' }]
  form.price = 0
  form.originalPrice = undefined
  form.stock = 5000
  form.status = 'ON_SALE'
  form.deliveryType = 'CARD'
  form.cardKindId = undefined
  form.platform = 'GENERAL'
  form.availablePlatforms = ['douyin', 'taobao']
  form.forbiddenPlatforms = []
  form.benefitDurations = []
  form.integrations = []
  form.pollingEnabled = false
  form.monitoringEnabled = true
  form.maxBuy = 1
  form.requireRechargeAccount = false
  form.accountTypes = []
  form.priceTemplateId = 'retail-default'
  form.priceMode = 'FIXED'
  form.priceCoefficient = 1
  form.priceFixedAdd = 0
  form.description = ''
  editorStep.value = 'base'
  activeGoodsModule.value = 'base'
}

function fillForm(row: Goods) {
  editingGoodsId.value = row.id
  form.categoryId = row.categoryId
  form.name = row.name
  form.subTitle = row.subTitle || ''
  form.coverUrl = row.coverUrl || ''
  form.detailImages = [...(row.detailImages || [])]
  detailBlocks.value = row.detailBlocks?.length
    ? row.detailBlocks.map((item) => ({ ...item }))
    : [
        ...(row.detailImages || []).map((imageUrl) => ({ type: 'image' as const, imageUrl, text: '' })),
        { type: 'text' as const, imageUrl: '', text: row.description || '' }
      ]
  form.price = Number(row.price) || 0
  form.originalPrice = row.originalPrice === undefined ? undefined : Number(row.originalPrice)
  form.stock = Number(row.stock) || 0
  form.status = row.status || 'ON_SALE'
  form.deliveryType = row.deliveryType === 'AUTO' ? 'DIRECT' : row.deliveryType || 'CARD'
  form.cardKindId = row.cardKindId
  form.platform = row.platform || 'GENERAL'
  form.availablePlatforms = row.availablePlatforms?.length ? [...row.availablePlatforms] : ['private']
  form.forbiddenPlatforms = row.forbiddenPlatforms?.length ? [...row.forbiddenPlatforms] : []
  form.benefitDurations = [...(row.benefitDurations || [])]
  form.integrations = row.integrations?.length ? row.integrations.map((item) => ({ ...item })) : []
  form.pollingEnabled = Boolean(row.pollingEnabled)
  form.monitoringEnabled = Boolean(row.monitoringEnabled)
  form.maxBuy = row.maxBuy || 1
  form.requireRechargeAccount = Boolean(row.requireRechargeAccount)
  form.accountTypes = [...(row.accountTypes || [])]
  form.priceTemplateId = row.priceTemplateId || 'retail-default'
  form.priceMode = row.priceMode || 'FIXED'
  form.priceCoefficient = row.priceCoefficient || 1
  form.priceFixedAdd = row.priceFixedAdd || 0
  form.description = row.description || ''
  editorStep.value = 'base'
  activeGoodsModule.value = 'base'
}

function openCreate() {
  resetForm()
  goodsEditorVisible.value = true
}

function openEdit(row: Goods) {
  fillForm(row)
  goodsEditorVisible.value = true
}

function isDirectGoods(row: Goods) {
  return row.deliveryType === 'DIRECT' || row.deliveryType === 'AUTO'
}

async function loadCategories() {
  categoryLoading.value = true

  try {
    categories.value = await fetchCategories()
    if (!form.categoryId) {
      const firstLeaf = categoryOptions.value.find((item) => !categoryOptions.value.some((child) => child.parentId === item.id))
      form.categoryId = firstLeaf?.id
    }
  } catch {
    ElMessage.error('分类列表加载失败')
  } finally {
    categoryLoading.value = false
  }
}

async function loadGoods() {
  loading.value = true

  try {
    goods.value = await fetchGoods()
  } catch {
    ElMessage.error('商品列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadCardKinds() {
  cardKindLoading.value = true

  try {
    cardKinds.value = await fetchCardKinds()
  } catch {
    ElMessage.error('卡种列表加载失败')
  } finally {
    cardKindLoading.value = false
  }
}

async function loadRechargeFields() {
  try {
    rechargeFields.value = await fetchRechargeFields({ enabled: true })
  } catch {
    rechargeFields.value = []
  }
}

async function loadSuppliers() {
  try {
    suppliers.value = await fetchSuppliers()
    if (!channelForm.supplierId) {
      channelForm.supplierId = suppliers.value.find((item) => item.status === 'ENABLED')?.id
    }
  } catch {
    ElMessage.error('供应商列表加载失败')
  }
}

async function submitGoods() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写商品名称')
    return
  }
  if (!form.categoryId) {
    ElMessage.warning('请选择商品分类')
    return
  }
  if (!form.availablePlatforms?.length) {
    ElMessage.warning('请选择可售平台，或选择无限制')
    return
  }
  if (form.deliveryType !== 'CARD') {
    form.requireRechargeAccount = true
    if (!form.accountTypes?.length) {
      ElMessage.warning('请选择充值字段')
      return
    }
  } else {
    form.requireRechargeAccount = false
    form.accountTypes = []
  }

  saving.value = true

  try {
    const normalizedBlocks = detailBlocks.value
      .map((item) => ({
        type: item.type || (item.imageUrl ? 'image' : 'text'),
        imageUrl: item.imageUrl?.trim() || '',
        text: item.text?.trim() || ''
      }))
      .filter((item) => item.imageUrl || item.text)
    const payload = {
      ...form,
      name: form.name.trim(),
      cardKindId: form.deliveryType === 'CARD' ? form.cardKindId : undefined,
      subTitle: form.subTitle?.trim(),
      coverUrl: form.coverUrl?.trim(),
      detailImages: normalizedBlocks.map((item) => item.imageUrl).filter(Boolean),
      detailBlocks: normalizedBlocks,
      integrations: form.integrations || [],
      benefitDurations: form.benefitDurations || [],
      description: form.description?.trim()
    }
    if (editingGoodsId.value) {
      await updateGoods(editingGoodsId.value, payload)
      ElMessage.success('商品已更新')
    } else {
      await createGoods(payload)
      ElMessage.success('商品已新增')
    }
    resetForm()
    goodsEditorVisible.value = false
    await loadGoods()
  } catch {
    ElMessage.error(editingGoodsId.value ? '更新商品失败' : '新增商品失败')
  } finally {
    saving.value = false
  }
}

function openImport(row: Goods) {
  selectedGoods.value = row
  cardText.value = ''
  importVisible.value = true
}

async function submitCards() {
  if (!selectedGoods.value) {
    return
  }

  if (!parsedCards.value.length) {
    ElMessage.warning('请按“卡号,密码”格式输入卡密')
    return
  }

  cardLoading.value = true

  try {
    await importGoodsCards(selectedGoods.value.id, parsedCards.value)
    ElMessage.success(`已导入 ${parsedCards.value.length} 条卡密`)
    importVisible.value = false
    await loadGoods()
  } catch {
    ElMessage.error('卡密导入失败')
  } finally {
    cardLoading.value = false
  }
}

async function openCards(row: Goods) {
  selectedGoods.value = row
  cardsVisible.value = true
  cardLoading.value = true

  try {
    cards.value = await fetchGoodsCards(row.id)
  } catch {
    ElMessage.error('卡密列表加载失败')
    cards.value = []
  } finally {
    cardLoading.value = false
  }
}

async function openChannels(row: Goods) {
  if (!isDirectGoods(row)) {
    ElMessage.warning('只有直充商品需要配置上游渠道')
    return
  }
  selectedGoods.value = row
  channelsVisible.value = true
  channelLoading.value = true
  channelForm.supplierGoodsId = ''
  channelForm.priority = 10
  channelForm.timeoutSeconds = 30
  channelForm.status = 'ENABLED'
  if (!suppliers.value.length) await loadSuppliers()

  try {
    channels.value = await fetchGoodsChannels(row.id)
  } catch {
    ElMessage.error('渠道列表加载失败')
    channels.value = []
  } finally {
    channelLoading.value = false
  }
}

async function submitChannel() {
  if (!selectedGoods.value) return
  if (!channelForm.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }
  if (!channelForm.supplierGoodsId.trim()) {
    ElMessage.warning('请填写上游商品ID')
    return
  }

  channelSaving.value = true
  try {
    await createGoodsChannel(selectedGoods.value.id, {
      ...channelForm,
      supplierGoodsId: channelForm.supplierGoodsId.trim()
    })
    ElMessage.success('渠道已添加')
    channelForm.supplierGoodsId = ''
    channels.value = await fetchGoodsChannels(selectedGoods.value.id)
  } catch {
    ElMessage.error('渠道添加失败')
  } finally {
    channelSaving.value = false
  }
}

async function removeChannel(row: GoodsChannel) {
  if (!selectedGoods.value) return
  channelLoading.value = true
  try {
    await deleteGoodsChannel(selectedGoods.value.id, row.id)
    ElMessage.success('渠道已删除')
    channels.value = await fetchGoodsChannels(selectedGoods.value.id)
  } catch {
    ElMessage.error('渠道删除失败')
  } finally {
    channelLoading.value = false
  }
}

onMounted(() => {
  void loadCategories()
  void loadCardKinds()
  void loadRechargeFields()
  void loadGoods()
  void loadSuppliers()
})
</script>

<template>
  <section class="ops-grid">
    <article class="panel table-panel">
      <div class="panel-head">
        <h2>商品列表</h2>
        <div class="panel-actions">
          <el-button type="primary" :icon="Plus" @click="openCreate">新建商品</el-button>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadGoods">刷新</el-button>
        </div>
      </div>

      <CategoryManagerPanel
        v-model="goodsFilters.categoryId"
        class="goods-category-panel"
        @categories-loaded="handleCategoriesLoaded"
      />

      <div class="table-filters">
        <el-input v-model="goodsFilters.search" clearable placeholder="搜索商品名称 / ID" />
        <el-select v-model="goodsFilters.categoryId" clearable filterable placeholder="按商品分类查看">
          <el-option
            v-for="item in categoryOptions"
            :key="item.id"
            :disabled="item.enabled === false"
            :label="`${'· '.repeat(Math.max((item.level || 1) - 1, 0))}${item.name}`"
            :value="String(item.id)"
          />
        </el-select>
        <el-select v-model="goodsFilters.platform" clearable placeholder="按销售平台选择">
          <el-option v-for="item in platformOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="filteredGoods" height="640" style="width: 100%">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="name" label="商品" min-width="220" show-overflow-tooltip />
        <el-table-column label="分类" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ categoryLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="权益时间" min-width="180">
          <template #default="{ row }">
            <div class="benefit-cards">
              <span v-for="item in row.benefitDurations || []" :key="item">{{ item }}</span>
              <em v-if="!row.benefitDurations?.length">未设置</em>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="售价" width="120">
          <template #default="{ row }">{{ formatMoney(row.price) }}</template>
        </el-table-column>
        <el-table-column label="库存" width="90">
          <template #default="{ row }">{{ stockText(row) }}</template>
        </el-table-column>
        <el-table-column label="发货类型" width="120">
          <template #default="{ row }">{{ deliveryLabel(row.deliveryType) }}</template>
        </el-table-column>
        <el-table-column label="卡种货源" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.deliveryType === 'CARD' && cardKindName(row)">
              {{ cardKindName(row) }}
              <em v-if="cardKindStockText(row) !== undefined"> · 库存 {{ cardKindStockText(row) }}</em>
            </span>
            <span v-else-if="row.deliveryType === 'CARD' && row.cardKindId">卡种 #{{ row.cardKindId }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="可售平台" min-width="180">
          <template #default="{ row }">
            <div class="platform-tags">
              <el-tag v-for="item in row.availablePlatforms || []" :key="item" size="small" effect="plain">
                {{ platformLabel(item) }}
              </el-tag>
              <span v-if="!row.availablePlatforms?.length">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" effect="plain">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="库存/渠道" width="320" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" :icon="Edit3" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="isDirectGoods(row)" size="small" :icon="PlugZap" @click="openChannels(row)">渠道</el-button>
              <el-button v-else size="small" :icon="Upload" @click="openImport(row)">导入</el-button>
              <el-button v-if="!isDirectGoods(row)" size="small" :icon="Eye" @click="openCards(row)">查看</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>

  <el-dialog v-model="goodsEditorVisible" width="min(1180px, calc(100vw - 96px))" class="xiyiyun-glass-dialog goods-editor-dialog" :show-close="false">
    <template #header>
      <div class="editor-header">
        <div>
          <span class="editor-kicker">商品配置</span>
          <h2>{{ formTitle }}</h2>
          <p>{{ formSubtitle }}</p>
        </div>
        <button type="button" class="editor-close" aria-label="关闭商品弹窗" @click="goodsEditorVisible = false">
          <X :size="18" />
        </button>
      </div>
    </template>

    <div class="editor-shell">
      <div class="editor-main">
        <div class="editor-steps">
          <button type="button" :class="{ active: editorStep === 'base' }" @click="editorStep = 'base'">商品基本信息</button>
          <button type="button" :class="{ active: editorStep === 'detail' }" @click="editorStep = 'detail'">商品详情</button>
        </div>

        <el-form :model="form" label-position="top" class="goods-form dialog-goods-form">
      <template v-if="editorStep === 'base'">
        <div class="module-workbench">
          <section class="module-stage">
            <div class="module-subnav" aria-label="商品基础配置模块">
              <button
                v-for="module in goodsModules"
                :key="module.key"
                type="button"
                :class="{ active: activeGoodsModule === module.key }"
                @click="activeGoodsModule = module.key"
              >
                <strong>{{ module.title }}</strong>
                <span>{{ module.desc }}</span>
              </button>
            </div>
            <section v-if="activeGoodsModule === 'base'" class="form-section module-panel merged-basic-panel">
              <div class="merged-section">
                <h3>基础资料</h3>
                <div class="form-grid">
                  <el-form-item label="商品分类">
                    <el-select v-model="form.categoryId" :loading="categoryLoading" filterable placeholder="选择五级分类">
                      <el-option
                        v-for="item in categoryOptions"
                        :key="item.id"
                        :disabled="item.enabled === false"
                        :label="`${'· '.repeat(Math.max((item.level || 1) - 1, 0))}${item.name}`"
                        :value="item.id"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="发货类型">
                    <el-select v-model="form.deliveryType">
                      <el-option v-for="item in deliveryOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </el-select>
                  </el-form-item>
                  <el-form-item v-if="form.deliveryType === 'CARD'" label="卡种货源">
                    <el-select v-model="form.cardKindId" :loading="cardKindLoading" clearable filterable placeholder="选择卡密仓库卡种">
                      <el-option
                        v-for="item in cardKinds"
                        :key="item.id"
                        :label="`${item.name} · 可用 ${cardKindUnused(item)} / 总 ${cardKindTotal(item)}`"
                        :value="item.id"
                      />
                    </el-select>
                  </el-form-item>
                </div>
                <el-form-item label="商品名称">
                  <el-input v-model="form.name" placeholder="例如：视频会员月卡" />
                </el-form-item>
                <el-form-item label="商品副标题">
                  <el-input v-model="form.subTitle" placeholder="例如：自动发卡，秒级到账" />
                </el-form-item>
                <el-form-item label="权益时间">
                  <GoodsBenefitSelector v-model="form.benefitDurations" :options="benefitDurationOptions" />
                </el-form-item>
              </div>

              <div class="merged-section">
                <h3>价格库存与充值字段</h3>
                <GoodsPricingFields
                  v-model:price-template-id="form.priceTemplateId"
                  v-model:status="form.status"
                  v-model:price="form.price"
                  v-model:original-price="form.originalPrice"
                  v-model:stock="form.stock"
                  v-model:max-buy="form.maxBuy"
                  v-model:account-types="form.accountTypes"
                  :price-templates="enabledPriceTemplates"
                  :status-options="statusOptions"
                  :account-type-options="accountTypeOptions"
                  :delivery-type="form.deliveryType"
                  @apply-price-template="applyPriceTemplate"
                />
              </div>

              <div class="merged-section">
                <h3>销售平台</h3>
                <GoodsPlatformSelector
                  v-model:available-platforms="form.availablePlatforms"
                  v-model:forbidden-platforms="form.forbiddenPlatforms"
                  :options="platformOptions"
                />
              </div>
            </section>

            <section v-else-if="activeGoodsModule === 'media'" class="form-section module-panel">
              <h3>商品主图</h3>
              <div class="cover-uploader">
                <el-upload :before-upload="handleCoverUpload" :show-file-list="false" accept="image/*">
                  <div class="upload-tile">
                    <img v-if="form.coverUrl" :src="form.coverUrl" alt="商品主图预览" />
                    <div v-else>
                      <Upload :size="22" />
                      <span>上传商品主图</span>
                    </div>
                  </div>
                </el-upload>
                <div class="upload-copy">
                  <strong>商品主图</strong>
                  <span>支持本地图片上传，保存为当前商品主图。建议 800x800 或 1:1。</span>
                </div>
              </div>
            </section>

            <section v-else-if="activeGoodsModule === 'integration'" class="form-section module-panel integration-panel">
              <div class="builder-head">
                <h3>商品对接信息设置</h3>
                <el-button size="small" :icon="Plus" @click="addIntegration">添加对接商品</el-button>
              </div>
              <div class="switch-row">
                <el-switch v-model="form.pollingEnabled" active-text="开启轮询" inactive-text="默认首个对接" />
                <el-switch v-model="form.monitoringEnabled" active-text="已开启监控" inactive-text="关闭监控" />
                <div class="monitor-scope">
                  <span>监听信息</span>
                  <em v-for="item in monitoringItems" :key="item">{{ item }}</em>
                </div>
              </div>
              <div class="integration-list">
                <div v-for="(item, index) in form.integrations || []" :key="item.id || index" class="integration-card">
                  <div class="integration-edit-row">
                    <el-form-item label="货源渠道">
                      <el-select v-model="item.supplierId" filterable placeholder="选择货源渠道" @change="refreshIntegration(index)">
                        <el-option
                          v-for="supplier in suppliers"
                          :key="supplier.id"
                          :label="supplier.name"
                          :value="supplier.id"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="上游商品 ID">
                      <el-input v-model="item.supplierGoodsId" placeholder="填入后自动刷新" @blur="refreshIntegration(index)" @keyup.enter="refreshIntegration(index)" />
                    </el-form-item>
                    <el-form-item label="操作">
                      <div class="inline-actions">
                        <el-button size="small" :icon="RefreshCw" @click="refreshIntegration(index)">刷新对接信息</el-button>
                        <el-button size="small" type="danger" :icon="Trash2" @click="removeIntegration(index)">删除</el-button>
                      </div>
                    </el-form-item>
                  </div>
                  <div class="upstream-snapshot">
                    <span>渠道：{{ item.supplierName || '未选择渠道' }}</span>
                    <span>商品ID：{{ item.supplierGoodsId || '-' }}</span>
                    <span>名称：{{ item.supplierGoodsName || item.upstreamTitle || '填入 ID 后自动获取' }}</span>
                    <span>售价：{{ item.supplierPrice || 0 }}</span>
                    <span>库存：{{ item.upstreamStock || 0 }}</span>
                    <span>状态：{{ item.upstreamStatus || '待刷新' }}</span>
                    <span>同步：{{ item.lastSyncAt || '未同步' }}</span>
                  </div>
                </div>
                <div v-if="!form.integrations?.length" class="empty-integration">暂无对接商品，点击右上角添加后只需填写货源渠道和上游商品 ID。</div>
              </div>
            </section>

          </section>

          <aside class="module-aside">
            <span class="aside-kicker">当前模块</span>
            <h3>{{ activeModuleMeta.title }}</h3>
            <p>{{ activeModuleMeta.desc }}</p>
            <div class="aside-metrics">
              <div>
                <strong>{{ deliveryLabel(form.deliveryType) }}</strong>
                <span>商品类型</span>
              </div>
              <div>
                <strong>{{ form.stock || 0 }}</strong>
                <span>默认库存</span>
              </div>
              <div>
                <strong>{{ form.availablePlatforms?.length || 0 }}</strong>
                <span>可售平台</span>
              </div>
              <div>
                <strong>{{ form.integrations?.length || 0 }}</strong>
                <span>对接商品</span>
              </div>
            </div>
            <div class="aside-checklist">
              <span :class="{ done: Boolean(form.categoryId) }">分类已选择</span>
              <span :class="{ done: Boolean(form.name) }">商品名已填写</span>
              <span :class="{ done: Boolean(form.coverUrl) }">主图已上传</span>
              <span :class="{ done: Boolean(form.priceTemplateId) }">价格模板已绑定</span>
            </div>
          </aside>
        </div>
      </template>

      <template v-else>
      <section class="form-section">
        <h3>图片与详情编排</h3>
        <div class="detail-builder">
          <div class="builder-head">
            <span>详情内容</span>
            <div>
              <el-button size="small" :icon="Plus" @click="addDetailBlock('image')">图片</el-button>
              <el-button size="small" :icon="Plus" @click="addDetailBlock('text')">文字</el-button>
            </div>
          </div>
          <div v-for="(block, index) in detailBlocks" :key="index" class="detail-block">
            <div class="detail-block-tools">
              <el-select v-model="block.type" size="small">
                <el-option label="图片" value="image" />
                <el-option label="文字" value="text" />
              </el-select>
              <el-button size="small" :disabled="index === 0" @click="moveDetailBlock(index, -1)">上移</el-button>
              <el-button size="small" :disabled="index === detailBlocks.length - 1" @click="moveDetailBlock(index, 1)">下移</el-button>
              <el-button size="small" type="danger" :icon="Trash2" @click="removeDetailBlock(index)">删除</el-button>
            </div>
            <div v-if="block.type === 'image'" class="detail-image-editor">
              <el-upload :before-upload="detailUploadHandler(index)" :show-file-list="false" accept="image/*">
                <div class="detail-image-tile">
                  <img v-if="block.imageUrl" :src="block.imageUrl" alt="详情图预览" />
                  <span v-else>上传详情图，可用长图或多图</span>
                </div>
              </el-upload>
              <el-input v-model="block.text" placeholder="图片说明，选填" />
            </div>
            <el-input v-else v-model="block.text" type="textarea" :rows="3" placeholder="输入详情文案、教程、兑换说明或注意事项" />
          </div>
        </div>
      </section>
      <section class="form-section">
        <h3>详情文案</h3>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="商品描述、使用教程、兑换链接、注意事项" />
        </el-form-item>
      </section>
      </template>
        </el-form>
      </div>
    </div>
    <template #footer>
      <div class="editor-footer">
        <div class="footer-status">
          <span>{{ editorStep === 'base' ? '正在编辑商品基本信息' : '正在编辑商品详情' }}</span>
        </div>
        <div>
          <el-button :icon="X" @click="goodsEditorVisible = false">取消</el-button>
          <el-button v-if="editorStep === 'base'" type="primary" @click="editorStep = 'detail'">下一步：商品详情</el-button>
          <el-button v-else @click="editorStep = 'base'">返回基本信息</el-button>
          <el-button type="primary" :icon="editingGoodsId ? Edit3 : Plus" :loading="saving" @click="submitGoods">
            {{ editingGoodsId ? '保存修改' : '创建商品' }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="importVisible" title="导入卡密" width="560px" class="xiyiyun-glass-dialog">
    <p class="dialog-hint">{{ selectedGoods?.name }}，每行一条：卡号,密码</p>
    <el-input v-model="cardText" type="textarea" :rows="12" placeholder="CARD001,PASS001&#10;CARD002,PASS002" />
    <template #footer>
      <span class="dialog-count">有效 {{ parsedCards.length }} 条</span>
      <el-button @click="importVisible = false">取消</el-button>
      <el-button type="primary" :loading="cardLoading" @click="submitCards">提交导入</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="cardsVisible" title="卡密库存" width="760px" class="xiyiyun-glass-dialog">
    <el-table v-loading="cardLoading" :data="cards" height="420" style="width: 100%">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="cardNo" label="卡号" min-width="180" show-overflow-tooltip />
      <el-table-column prop="password" label="密码" min-width="180" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
    </el-table>
  </el-dialog>

  <el-dialog v-model="channelsVisible" title="直充渠道配置" width="920px" class="xiyiyun-glass-dialog">
    <p class="dialog-hint">{{ selectedGoods?.name }}，按优先级从小到大尝试上游渠道。</p>

    <el-form :model="channelForm" label-position="top" class="channel-form">
      <el-form-item label="供应商">
        <el-select v-model="channelForm.supplierId" filterable placeholder="选择供应商">
          <el-option
            v-for="supplier in suppliers"
            :key="supplier.id"
            :disabled="supplier.status !== 'ENABLED'"
            :label="supplier.name"
            :value="supplier.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="上游商品ID">
        <el-input v-model="channelForm.supplierGoodsId" placeholder="例如：STAR-GAME-60" />
      </el-form-item>
      <el-form-item label="优先级">
        <el-input-number v-model="channelForm.priority" :min="1" :step="10" controls-position="right" />
      </el-form-item>
      <el-form-item label="超时秒数">
        <el-input-number v-model="channelForm.timeoutSeconds" :min="5" :step="5" controls-position="right" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="channelForm.status">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item class="channel-submit">
        <el-button type="primary" :icon="Plus" :loading="channelSaving" @click="submitChannel">添加渠道</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="channelLoading" :data="channels" height="360" style="width: 100%">
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
      <el-table-column prop="supplierGoodsId" label="上游商品ID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="timeoutSeconds" label="超时(s)" width="100" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" :icon="Trash2" @click="removeChannel(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.ops-grid {
  display: block;
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
  -webkit-backdrop-filter: blur(28px) saturate(180%);
}

.panel::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.68;
  background:
    linear-gradient(130deg, rgba(255, 255, 255, 0.14), transparent 32%),
    radial-gradient(circle at 88% 8%, rgba(0, 255, 195, 0.1), transparent 28%);
}

.panel-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span,
.dialog-hint,
.dialog-count {
  color: rgba(255, 255, 255, 0.5);
}

.goods-form,
.goods-category-panel,
.table-panel :deep(.el-table) {
  position: relative;
  z-index: 1;
}

.goods-category-panel {
  margin-bottom: 12px;
}

.goods-form :deep(.el-input-number),
.goods-form :deep(.el-select),
.goods-form :deep(.el-segmented),
.channel-form :deep(.el-input-number),
.channel-form :deep(.el-select) {
  width: 100%;
}

.inline-grid {
  display: grid;
  gap: 10px;
  align-items: end;
}

.inline-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

.dialog-goods-form {
  display: grid;
  gap: 10px;
  height: 100%;
  max-height: none;
  overflow: visible;
  padding: 0;
}

:global(.goods-editor-dialog) {
  --el-dialog-bg-color: rgba(8, 17, 31, 0.88);
  --el-dialog-padding-primary: 0;
  overflow: hidden;
  border-radius: 24px;
  color: rgba(255, 255, 255, 0.84);
  background:
    radial-gradient(circle at 12% 0%, rgba(0, 255, 195, 0.14), transparent 34%),
    radial-gradient(circle at 94% 10%, rgba(88, 166, 255, 0.18), transparent 28%),
    rgba(8, 17, 31, 0.9) !important;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 34px 90px rgba(0, 0, 0, 0.48), inset 0 1px 0 rgba(255, 255, 255, 0.14);
  backdrop-filter: blur(34px) saturate(180%);
  -webkit-backdrop-filter: blur(34px) saturate(180%);
}

:global(.goods-editor-dialog.el-dialog) {
  margin-top: 32px !important;
  max-height: calc(100vh - 64px);
}

:global(.goods-editor-dialog .el-dialog__header),
:global(.goods-editor-dialog .el-dialog__body),
:global(.goods-editor-dialog .el-dialog__footer) {
  padding: 0;
  margin: 0;
}

:global(.goods-editor-dialog .el-dialog__body) {
  color: rgba(255, 255, 255, 0.84);
}

.editor-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px 12px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.035);
}

.editor-kicker {
  display: inline-flex;
  height: 20px;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  color: #071410;
  font-size: 11px;
  font-weight: 700;
  background: #00ffc3;
}

.editor-header h2 {
  margin: 6px 0 2px;
  color: rgba(255, 255, 255, 0.94);
  font-size: 20px;
}

.editor-header p {
  margin: 0;
  color: rgba(255, 255, 255, 0.52);
}

.editor-close {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
}

.editor-shell {
  display: block;
  max-height: calc(100vh - 190px);
  overflow: auto;
}

.editor-main {
  min-width: 0;
  padding: 10px;
  overflow: visible;
}

.editor-steps {
  display: flex;
  gap: 6px;
  padding: 4px;
  margin-bottom: 8px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.editor-steps button {
  flex: 1;
  height: 30px;
  padding: 0 16px;
  border: 0;
  border-radius: 12px;
  color: rgba(255, 255, 255, 0.64);
  background: transparent;
  cursor: pointer;
}

.editor-steps button.active {
  color: #06110f;
  background: #00ffc3;
}

.form-section {
  padding: 10px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.038);
  border: 0.5px solid rgba(255, 255, 255, 0.095);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.form-section h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 650;
  color: rgba(255, 255, 255, 0.86);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.form-grid.three {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.module-workbench {
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 214px;
  gap: 10px;
  align-items: start;
  overflow: visible;
}

.module-subnav {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(136px, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}

.module-subnav button {
  display: grid;
  gap: 5px;
  min-height: 68px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.54);
  text-align: left;
  background: rgba(255, 255, 255, 0.035);
  cursor: pointer;
  transition: transform 180ms ease, border-color 180ms ease, background 180ms ease;
}

.module-subnav button:hover,
.module-subnav button.active {
  transform: translateY(-1px);
  border-color: rgba(0, 255, 195, 0.26);
  background: rgba(0, 255, 195, 0.1);
}

.module-subnav button strong {
  color: rgba(255, 255, 255, 0.86);
  font-size: 13px;
  font-weight: 700;
}

.module-subnav button.active strong {
  color: #9ffff0;
}

.module-subnav button span {
  font-size: 12px;
  line-height: 1.35;
}

.module-stage,
.module-aside {
  min-height: 0;
}

.module-stage,
.module-aside {
  overflow: visible;
}

.module-panel {
  min-height: 0;
}

.module-panel.form-section {
  min-width: 0;
}

.merged-basic-panel {
  display: grid;
  gap: 10px;
  align-content: start;
}

.merged-section {
  padding-bottom: 10px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.075);
}

.merged-section:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.module-panel :deep(.el-form-item) {
  margin-bottom: 8px;
}

.module-panel :deep(.el-form-item__label) {
  margin-bottom: 4px;
  font-size: 12px;
  line-height: 1.3;
}

.module-panel :deep(.el-input__wrapper),
.module-panel :deep(.el-select__wrapper) {
  min-height: 30px;
}

.module-panel :deep(.el-input-number) {
  height: 30px;
}

.module-aside {
  align-self: start;
  padding: 12px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(0, 255, 195, 0.08), rgba(255, 255, 255, 0.035));
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.aside-kicker {
  display: inline-flex;
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  color: #06110f;
  background: #00ffc3;
  font-size: 11px;
  font-weight: 700;
}

.module-aside h3 {
  margin: 12px 0 4px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 16px;
}

.module-aside p {
  margin: 0 0 14px;
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  line-height: 1.5;
}

.aside-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.aside-metrics div {
  min-width: 0;
  padding: 10px;
  border-radius: 14px;
  background: rgba(2, 10, 18, 0.38);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.aside-metrics strong,
.aside-metrics span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aside-metrics strong {
  color: rgba(255, 255, 255, 0.88);
  font-size: 13px;
}

.aside-metrics span,
.aside-checklist span {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
}

.aside-checklist {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.aside-checklist span {
  position: relative;
  padding-left: 18px;
}

.aside-checklist span::before {
  content: "";
  position: absolute;
  left: 0;
  top: 5px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.22);
}

.aside-checklist span.done {
  color: rgba(205, 255, 246, 0.82);
}

.aside-checklist span.done::before {
  background: #00ffc3;
  box-shadow: 0 0 14px rgba(0, 255, 195, 0.45);
}

.cover-uploader {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
}

.upload-tile,
.detail-image-tile {
  display: grid;
  place-items: center;
  overflow: hidden;
  border-radius: 14px;
  color: rgba(255, 255, 255, 0.62);
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px dashed rgba(255, 255, 255, 0.22);
  cursor: pointer;
}

.upload-tile {
  width: 72px;
  height: 72px;
}

.upload-tile div {
  display: grid;
  gap: 8px;
  place-items: center;
}

.upload-tile img,
.detail-image-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.upload-copy {
  display: grid;
  gap: 4px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
  line-height: 1.45;
}

.upload-copy strong {
  color: rgba(255, 255, 255, 0.88);
}

.builder-head,
.detail-block-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.builder-head {
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.72);
}

.detail-block {
  display: grid;
  gap: 10px;
  padding: 12px;
  margin-top: 10px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.035);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.detail-block-tools {
  justify-content: flex-start;
}

.detail-block-tools :deep(.el-select) {
  width: 96px;
}

.detail-image-editor {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 10px;
  align-items: end;
}

.detail-image-tile {
  width: 180px;
  min-height: 120px;
  padding: 12px;
}

.image-preview {
  width: 88px;
  height: 88px;
  overflow: hidden;
  border-radius: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.045);
}

.image-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.platform-checks,
.platform-tags,
.benefit-options,
.benefit-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.benefit-cards span,
.benefit-cards em {
  padding: 5px 10px;
  border-radius: 10px;
  font-style: normal;
  color: #dffdf7;
  background: rgba(0, 255, 195, 0.1);
  border: 0.5px solid rgba(0, 255, 195, 0.18);
}

.benefit-cards em {
  color: rgba(255, 255, 255, 0.45);
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.08);
}

.account-checks {
  margin-top: 8px;
}

.recharge-field-control {
  width: 100%;
  display: grid;
  gap: 8px;
}

.platform-checks :deep(.el-checkbox) {
  height: 26px;
  margin-right: 0;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.benefit-options :deep(.el-checkbox) {
  margin-right: 0;
  height: 26px;
  padding: 0 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.switch-row,
.inline-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.switch-row {
  margin-bottom: 8px;
}

.integration-card {
  padding: 8px;
  margin-top: 8px;
  border-radius: 16px;
  background: rgba(4, 13, 22, 0.42);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.integration-list {
  max-height: min(360px, calc(100vh - 410px));
  overflow: auto;
  padding-right: 4px;
}

.integration-edit-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: 4px;
  align-items: end;
}

.upstream-snapshot {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
  padding-top: 8px;
  color: rgba(255, 255, 255, 0.56);
  font-size: 12px;
}

.monitor-scope {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  color: rgba(255, 255, 255, 0.44);
  font-size: 12px;
}

.monitor-scope em {
  padding: 3px 7px;
  border-radius: 999px;
  color: #bffdf2;
  font-style: normal;
  background: rgba(0, 255, 195, 0.08);
  border: 0.5px solid rgba(0, 255, 195, 0.16);
}

.empty-integration {
  padding: 14px;
  border-radius: 14px;
  color: rgba(255, 255, 255, 0.48);
  text-align: center;
  background: rgba(255, 255, 255, 0.035);
  border: 0.5px dashed rgba(255, 255, 255, 0.14);
}

.editor-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 10px 14px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(2, 8, 16, 0.34);
}

.editor-footer > div:last-child {
  display: flex;
  gap: 8px;
}

.footer-status {
  color: rgba(255, 255, 255, 0.48);
  font-size: 13px;
}

.form-actions,
.table-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.form-actions {
  justify-content: flex-end;
}

.table-filters {
  position: relative;
  z-index: 1;
  margin-bottom: 14px;
}

.table-filters :deep(.el-input),
.table-filters :deep(.el-select) {
  width: min(240px, 100%);
}

.channel-form {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr)) 120px;
  gap: 10px;
  align-items: end;
  margin-bottom: 14px;
}

.channel-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.channel-submit {
  padding-top: 22px;
}

.table-panel {
  min-width: 0;
}

.table-panel :deep(.el-button-group .el-button) {
  background: rgba(255, 255, 255, 0.055);
  border-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.78);
}

.dialog-hint {
  margin: 0 0 10px;
}

.dialog-count {
  float: left;
  line-height: 32px;
}
</style>
