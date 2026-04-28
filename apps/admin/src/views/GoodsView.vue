<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { Edit3, Eye, PlugZap, Plus, RefreshCw, Trash2, Upload, X } from 'lucide-vue-next'
import {
  createGoods,
  createGoodsChannel,
  deleteGoodsChannel,
  fetchCategories,
  fetchGoods,
  fetchGoodsCards,
  fetchGoodsChannels,
  fetchSuppliers,
  importGoodsCards,
  updateGoods
} from '../api/admin'
import type {
  CardImportItem,
  Category,
  Goods,
  GoodsCard,
  GoodsChannel,
  GoodsChannelCreatePayload,
  GoodsCreatePayload,
  GoodsDetailBlock,
  Supplier
} from '../types/operations'
import { loadPriceTemplates } from '../utils/priceTemplates'

const goods = ref<Goods[]>([])
const cards = ref<GoodsCard[]>([])
const channels = ref<GoodsChannel[]>([])
const categories = ref<Category[]>([])
const suppliers = ref<Supplier[]>([])
const loading = ref(false)
const categoryLoading = ref(false)
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
const goodsFilters = reactive({
  categoryId: '',
  platform: '',
  search: ''
})

const platformOptions = [
  { label: '抖音', value: 'douyin' },
  { label: '淘宝', value: 'taobao' },
  { label: '拼多多', value: 'pdd' },
  { label: '咸鱼', value: 'xianyu' },
  { label: '小红书', value: 'xiaohongshu' },
  { label: '私域', value: 'private' }
]

const statusOptions = [
  { label: '上架', value: 'ON_SALE', type: 'success' },
  { label: '下架', value: 'OFF_SALE', type: 'info' },
  { label: '售罄', value: 'SOLD_OUT', type: 'warning' }
]

const deliveryOptions = [
  { label: '卡密', value: 'CARD' },
  { label: '代充', value: 'MANUAL' },
  { label: '直充', value: 'DIRECT' }
]

const benefitDurationOptions = ['一天', '三天', '周卡', '半月', '月卡', '季卡', '半年', '一年']

const accountTypeOptions = [
  { label: '手机号', value: 'mobile' },
  { label: 'QQ号', value: 'qq' },
  { label: '微信号', value: 'wechat' },
  { label: '邮箱', value: 'email' },
  { label: '游戏 UID', value: 'game_uid' }
]

const form = reactive<GoodsCreatePayload>({
  categoryId: undefined,
  name: '',
  subTitle: '',
  coverUrl: '',
  detailImages: [],
  price: 0,
  originalPrice: undefined,
  stock: 0,
  status: 'ON_SALE',
  deliveryType: 'CARD',
  platform: 'GENERAL',
  availablePlatforms: ['douyin', 'taobao', 'private'],
  forbiddenPlatforms: [],
  benefitDurations: [],
  integrations: [],
  pollingEnabled: false,
  monitoringEnabled: false,
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
    const categoryMatched = !goodsFilters.categoryId || String(item.categoryId) === String(goodsFilters.categoryId)
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
  form.integrations = [
    ...(form.integrations || []),
    {
      id: `link-${Date.now()}`,
      platformCode: 'taobao',
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
  item.upstreamStatus = item.enabled === false ? '已禁用' : '正常'
  item.upstreamTitle = item.supplierGoodsName || item.upstreamTitle || '上游商品'
  item.upstreamStock = Number(item.upstreamStock || 0) + 1
  item.lastSyncAt = new Date().toLocaleString('zh-CN')
  ElMessage.success('已刷新对接信息')
}

function buildCategoryTree(items: Category[]) {
  const map = new Map<string, Category>()
  const roots: Category[] = []

  items.forEach((item) => {
    map.set(String(item.id), { ...item, children: [] })
  })

  map.forEach((item) => {
    const parentId = item.parentId ? String(item.parentId) : ''
    const parent = parentId ? map.get(parentId) : undefined
    item.level = parent ? (parent.level || 1) + 1 : 1
    if (parent) parent.children?.push(item)
    else roots.push(item)
  })

  const sortNode = (nodes: Category[]) => {
    nodes.sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0))
    nodes.forEach((node) => sortNode(node.children || []))
  }
  sortNode(roots)
  return roots
}

function flattenCategoryTree(nodes: Category[], result: Category[] = []) {
  nodes.forEach((node) => {
    result.push(node)
    flattenCategoryTree(node.children || [], result)
  })
  return result
}

function formatMoney(value: Goods['price']) {
  const numberValue = Number(value)

  if (Number.isNaN(numberValue)) {
    return String(value ?? '-')
  }

  return `¥${numberValue.toFixed(2)}`
}

function stockText(row: Goods) {
  return typeof row.stock === 'number' ? row.stock : '-'
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
  form.stock = 0
  form.status = 'ON_SALE'
  form.deliveryType = 'CARD'
  form.platform = 'GENERAL'
  form.availablePlatforms = ['douyin', 'taobao', 'private']
  form.forbiddenPlatforms = []
  form.benefitDurations = []
  form.integrations = []
  form.pollingEnabled = false
  form.monitoringEnabled = false
  form.maxBuy = 1
  form.requireRechargeAccount = false
  form.accountTypes = []
  form.priceTemplateId = 'retail-default'
  form.priceMode = 'FIXED'
  form.priceCoefficient = 1
  form.priceFixedAdd = 0
  form.description = ''
  editorStep.value = 'base'
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

  <el-dialog v-model="goodsEditorVisible" width="1120px" class="goods-editor-dialog" :show-close="false">
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
      <aside class="editor-rail">
        <button type="button" :class="{ active: editorStep === 'base' }" @click="editorStep = 'base'">
          <strong>01</strong>
          <span>商品基本信息</span>
          <em>类型、分类、价格、库存、平台与上游对接</em>
        </button>
        <button type="button" :class="{ active: editorStep === 'detail' }" @click="editorStep = 'detail'">
          <strong>02</strong>
          <span>商品详情</span>
          <em>长图、多图、文案与使用说明编排</em>
        </button>
        <div class="editor-summary">
          <span>当前类型</span>
          <strong>{{ deliveryLabel(form.deliveryType) }}</strong>
          <span>权益时间</span>
          <strong>{{ form.benefitDurations?.length ? form.benefitDurations.join(' / ') : '未设置' }}</strong>
          <span>对接商品</span>
          <strong>{{ form.integrations?.length || 0 }} 个</strong>
        </div>
      </aside>

      <div class="editor-main">
        <div class="editor-steps">
          <button type="button" :class="{ active: editorStep === 'base' }" @click="editorStep = 'base'">商品基本信息</button>
          <button type="button" :class="{ active: editorStep === 'detail' }" @click="editorStep = 'detail'">商品详情</button>
        </div>

        <el-form :model="form" label-position="top" class="goods-form dialog-goods-form">
      <template v-if="editorStep === 'base'">
      <section class="form-section">
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
        </div>
        <el-form-item label="商品名称">
          <el-input v-model="form.name" placeholder="例如：视频会员月卡" />
        </el-form-item>
        <el-form-item label="商品副标题">
          <el-input v-model="form.subTitle" placeholder="例如：自动发卡，秒级到账" />
        </el-form-item>
        <el-form-item label="权益时间">
          <el-checkbox-group v-model="form.benefitDurations" class="benefit-options">
            <el-checkbox v-for="item in benefitDurationOptions" :key="item" :label="item">{{ item }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </section>

      <section class="form-section">
        <h3>价格与库存</h3>
        <div class="form-grid three">
          <el-form-item label="价格模板">
            <el-select v-model="form.priceTemplateId" placeholder="选择价格模板" @change="applyPriceTemplate">
              <el-option
                v-for="item in enabledPriceTemplates"
                :key="item.id"
                :label="`${item.name} · ${item.groupRates?.length || 0} 个会员等级`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="售价">
            <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="库存">
            <el-input-number v-model="form.stock" :min="0" :step="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="form.status">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="最大购买数量">
            <el-input-number v-model="form.maxBuy" :min="1" :step="1" controls-position="right" />
          </el-form-item>
          <el-form-item label="划线原价">
            <el-input-number v-model="form.originalPrice" :min="0" :precision="2" :step="1" controls-position="right" placeholder="选填" />
          </el-form-item>
        </div>
      </section>

      <section class="form-section">
        <h3>商品主图与销售平台</h3>
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
        <el-form-item label="可售平台">
          <el-checkbox-group v-model="form.availablePlatforms" class="platform-checks">
            <el-checkbox v-for="item in platformOptions" :key="item.value" :label="item.value">
              {{ item.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="不支持平台说明">
          <el-checkbox-group v-model="form.forbiddenPlatforms" class="platform-checks">
            <el-checkbox v-for="item in platformOptions" :key="item.value" :label="item.value">
              {{ item.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </section>

      <section class="form-section">
        <div class="builder-head">
          <h3>商品对接信息设置</h3>
          <el-button size="small" :icon="Plus" @click="addIntegration">添加对接商品</el-button>
        </div>
        <div class="switch-row">
          <el-switch v-model="form.pollingEnabled" active-text="开启轮询" inactive-text="默认首个对接" />
          <el-switch v-model="form.monitoringEnabled" active-text="监控上游商品" inactive-text="关闭监控" />
        </div>
        <div v-for="(item, index) in form.integrations || []" :key="item.id || index" class="integration-card">
          <div class="form-grid three">
            <el-form-item label="对接平台">
              <el-select v-model="item.platformCode">
                <el-option v-for="platform in platformOptions" :key="platform.value" :label="platform.label" :value="platform.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="对接平台商品 ID">
              <el-input v-model="item.supplierGoodsId" placeholder="上游商品 ID" />
            </el-form-item>
            <el-form-item label="对接平台商品名称">
              <el-input v-model="item.supplierGoodsName" placeholder="上游商品名称" />
            </el-form-item>
            <el-form-item label="对接平台售价">
              <el-input-number v-model="item.supplierPrice" :min="0" :precision="2" controls-position="right" />
            </el-form-item>
            <el-form-item label="允许渠道">
              <el-switch v-model="item.enabled" active-text="允许" inactive-text="禁用" />
            </el-form-item>
            <el-form-item label="操作">
              <div class="inline-actions">
                <el-button size="small" :icon="RefreshCw" @click="refreshIntegration(index)">刷新对接信息</el-button>
                <el-button size="small" type="danger" :icon="Trash2" @click="removeIntegration(index)">删除</el-button>
              </div>
            </el-form-item>
          </div>
          <div class="upstream-snapshot">
            <span>商品ID：{{ item.supplierGoodsId || '-' }}</span>
            <span>标题：{{ item.upstreamTitle || item.supplierGoodsName || '-' }}</span>
            <span>售价：{{ item.supplierPrice || 0 }}</span>
            <span>库存：{{ item.upstreamStock || 0 }}</span>
            <span>状态：{{ item.upstreamStatus || '待刷新' }}</span>
            <span>同步：{{ item.lastSyncAt || '未同步' }}</span>
          </div>
        </div>
      </section>

      <section class="form-section">
        <h3>账号与补充规则</h3>
        <el-form-item v-if="form.deliveryType !== 'CARD'" label="充值账号要求">
          <el-checkbox v-model="form.requireRechargeAccount">要求用户填写充值账号</el-checkbox>
          <el-checkbox-group v-if="form.requireRechargeAccount" v-model="form.accountTypes" class="platform-checks account-checks">
            <el-checkbox v-for="item in accountTypeOptions" :key="item.value" :label="item.value">
              {{ item.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </section>
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

  <el-dialog v-model="importVisible" title="导入卡密" width="560px">
    <p class="dialog-hint">{{ selectedGoods?.name }}，每行一条：卡号,密码</p>
    <el-input v-model="cardText" type="textarea" :rows="12" placeholder="CARD001,PASS001&#10;CARD002,PASS002" />
    <template #footer>
      <span class="dialog-count">有效 {{ parsedCards.length }} 条</span>
      <el-button @click="importVisible = false">取消</el-button>
      <el-button type="primary" :loading="cardLoading" @click="submitCards">提交导入</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="cardsVisible" title="卡密库存" width="760px">
    <el-table v-loading="cardLoading" :data="cards" height="420" style="width: 100%">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="cardNo" label="卡号" min-width="180" show-overflow-tooltip />
      <el-table-column prop="password" label="密码" min-width="180" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
    </el-table>
  </el-dialog>

  <el-dialog v-model="channelsVisible" title="直充渠道配置" width="920px">
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
.table-panel :deep(.el-table) {
  position: relative;
  z-index: 1;
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
  gap: 14px;
  max-height: 66vh;
  overflow: auto;
  padding: 2px 8px 2px 2px;
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
  padding: 20px 22px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.035);
}

.editor-kicker {
  display: inline-flex;
  height: 24px;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  color: #071410;
  font-size: 12px;
  font-weight: 700;
  background: #00ffc3;
}

.editor-header h2 {
  margin: 10px 0 4px;
  color: rgba(255, 255, 255, 0.94);
  font-size: 22px;
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
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: 560px;
}

.editor-rail {
  padding: 18px;
  border-right: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(2, 8, 16, 0.28);
}

.editor-rail button {
  width: 100%;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 4px 10px;
  padding: 14px;
  margin-bottom: 10px;
  text-align: left;
  border-radius: 16px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.7);
  background: rgba(255, 255, 255, 0.035);
  cursor: pointer;
  transition: transform 160ms ease, background 160ms ease, border-color 160ms ease;
}

.editor-rail button:active {
  transform: scale(0.99);
}

.editor-rail button.active {
  color: rgba(255, 255, 255, 0.94);
  border-color: rgba(0, 255, 195, 0.32);
  background: rgba(0, 255, 195, 0.09);
}

.editor-rail strong {
  grid-row: 1 / 3;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  color: #04100d;
  background: rgba(0, 255, 195, 0.88);
}

.editor-rail span {
  font-weight: 700;
}

.editor-rail em {
  color: rgba(255, 255, 255, 0.42);
  font-size: 12px;
  font-style: normal;
  line-height: 1.45;
}

.editor-summary {
  display: grid;
  gap: 6px;
  margin-top: 18px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.editor-summary span {
  color: rgba(255, 255, 255, 0.44);
  font-size: 12px;
}

.editor-summary strong {
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.82);
  font-weight: 650;
}

.editor-main {
  min-width: 0;
  padding: 18px;
}

.editor-steps {
  display: flex;
  gap: 6px;
  padding: 4px;
  margin-bottom: 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.editor-steps button {
  flex: 1;
  height: 38px;
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
  padding: 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.038);
  border: 0.5px solid rgba(255, 255, 255, 0.095);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.form-section h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 650;
  color: rgba(255, 255, 255, 0.86);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.cover-uploader {
  display: grid;
  grid-template-columns: 156px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  margin-bottom: 14px;
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
  width: 156px;
  height: 156px;
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
  gap: 6px;
  color: rgba(255, 255, 255, 0.52);
  line-height: 1.6;
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
  margin-bottom: 10px;
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
  gap: 8px;
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

.platform-checks :deep(.el-checkbox) {
  height: 30px;
  margin-right: 0;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.benefit-options :deep(.el-checkbox) {
  margin-right: 0;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.switch-row,
.inline-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.switch-row {
  margin-bottom: 12px;
}

.integration-card {
  padding: 14px;
  margin-top: 10px;
  border-radius: 16px;
  background: rgba(4, 13, 22, 0.42);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.upstream-snapshot {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-top: 8px;
  color: rgba(255, 255, 255, 0.56);
  font-size: 12px;
}

.editor-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 14px 18px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(2, 8, 16, 0.34);
}

.editor-footer > div:last-child {
  display: flex;
  gap: 10px;
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
