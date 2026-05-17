<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRawFile } from 'element-plus'
import { Edit3, Eye, PlugZap, Plus, RefreshCw, Trash2, Upload, X } from 'lucide-vue-next'
import {
  createGoods,
  createGoodsChannel,
  deleteGoods,
  deleteGoodsChannel,
  fetchGoods,
  fetchGoodsCards,
  fetchGoodsChannels,
  fetchRemoteGoodsSnapshot,
  importGoodsCards,
  updateGoods
} from '../api/goods'
import { fetchCardKinds } from '../api/cardKinds'
import { fetchCategories, fetchRechargeFields } from '../api/catalog'
import { fetchPriceTemplates } from '../api/priceTemplates'
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
  GoodsIntegration,
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
  goodsSalePlatformOptions,
  goodsModules,
  monitoringItems,
  platformOptions,
  statusOptions,
  type GoodsModuleKey
} from '../utils/goodsOptions'
import { formatMoney } from '../utils/formatters'
import type { PriceTemplate } from '../utils/priceTemplates'

const DEFAULT_SALE_PLATFORMS = goodsSalePlatformOptions.map((item) => item.value)
const GOODS_SALE_PLATFORM_VALUES = new Set(DEFAULT_SALE_PLATFORMS)

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
const selectedTableGoods = ref<Goods[]>([])
const goodsTableRef = ref<{
  clearSelection: () => void
  toggleAllSelection: () => void
  toggleRowSelection: (row: Goods, selected?: boolean) => void
}>()
const cardText = ref('')
const editingGoodsId = ref<Goods['id']>()
const detailBlocks = ref<GoodsDetailBlock[]>([])
const tagsInput = ref('')
const priceTemplates = ref<PriceTemplate[]>([])
const editorStep = ref<'base' | 'detail'>('base')
const activeGoodsModule = ref<GoodsModuleKey>('base')
const goodsFilters = reactive({
  categoryId: '',
  platform: '',
  search: ''
})

const batchEditVisible = ref(false)
const batchSaving = ref(false)
const batchEdit = reactive({
  categoryEnabled: false,
  categoryId: undefined as GoodsCreatePayload['categoryId'],
  statusEnabled: false,
  status: 'ON_SALE',
  benefitDurationsEnabled: false,
  benefitDurations: [] as string[],
  benefitTypeEnabled: false,
  benefitType: '',
  benefitBrandEnabled: false,
  benefitBrand: '',
  priceEnabled: false,
  price: 0,
  stockEnabled: false,
  stock: 0,
  deliveryTypeEnabled: false,
  deliveryType: 'CARD',
  availablePlatformsEnabled: false,
  availablePlatforms: [] as string[],
  forbiddenPlatformsEnabled: false,
  forbiddenPlatforms: [] as string[]
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
  availablePlatforms: [...DEFAULT_SALE_PLATFORMS],
  forbiddenPlatforms: [],
  benefitDurations: [],
  benefitType: '',
  benefitBrand: '',
  tags: [],
  priceLimitText: '',
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

const categoryTreeOptions = computed(() => toCategoryTreeSelectOptions(buildCategoryTree(categories.value)))

const categoryPathById = computed(() => {
  const categoryMap = new Map<string, Category>()
  categoryOptions.value.forEach((item) => categoryMap.set(String(item.id), item))

  const pathMap = new Map<string, string>()
  categoryOptions.value.forEach((item) => {
    const segments: string[] = []
    const visited = new Set<string>()
    let current: Category | undefined = item

    while (current) {
      const currentId = String(current.id)
      if (visited.has(currentId)) break
      visited.add(currentId)
      segments.unshift(current.name)
      current = current.parentId ? categoryMap.get(String(current.parentId)) : undefined
    }

    pathMap.set(String(item.id), segments.join('-'))
  })

  return pathMap
})

const accountTypeOptions = computed(() => {
  const enabledFields = rechargeFields.value
    .filter((item) => item.enabled)
    .sort((left, right) => Number(left.sort) - Number(right.sort))
    .map((item) => ({ label: item.label, value: item.code }))

  return enabledFields.length ? enabledFields : fallbackAccountTypeOptions
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
      (item.categoryName || '').toLowerCase().includes(keyword) ||
      (item.tags || []).some((tag) => tag.toLowerCase().includes(keyword))

    return categoryMatched && platformMatched && keywordMatched
  })
)

const formTitle = computed(() => (editingGoodsId.value ? '编辑商品' : '新增商品'))
const formSubtitle = computed(() => (editingGoodsId.value ? '更新商品资料与销售配置' : '卡密/直充/代充商品资料'))
const enabledPriceTemplates = computed(() => priceTemplates.value.filter((item) => item.enabled))
const activeModuleMeta = computed(() => goodsModules.find((item) => item.key === activeGoodsModule.value) || goodsModules[0])
const imageAccept = 'image/jpeg,image/png,image/webp,image/gif'

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

function fileToDataUrl(file: UploadRawFile) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

async function handleCoverUpload(file: UploadRawFile) {
  if (!validateImageFile(file)) return false
  form.coverUrl = await fileToDataUrl(file)
  return false
}

function mediaSrc(url?: string) {
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

async function handleDetailUpload(file: UploadRawFile, block: GoodsDetailBlock) {
  if (!validateImageFile(file)) return false
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

async function loadPriceTemplates() {
  try {
    priceTemplates.value = await fetchPriceTemplates()
  } catch {
    ElMessage.error('价格模板加载失败')
  }
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

async function refreshIntegration(index: number) {
  const item = form.integrations?.[index]
  if (!item) return
  const supplier = suppliers.value.find((entry) => String(entry.id) === String(item.supplierId))
  if (!item.supplierId) {
    ElMessage.warning('请选择货源渠道')
    return
  }
  if (!item.supplierGoodsId?.trim()) {
    ElMessage.warning('请填写上游商品 ID')
    return
  }

  try {
    const snapshot = await fetchRemoteGoodsSnapshot(item.supplierId, item.supplierGoodsId.trim())
    Object.assign(item, snapshot, {
      supplierId: snapshot.supplierId || item.supplierId,
      supplierName: snapshot.supplierName || supplier?.name || item.supplierName,
      enabled: true
    })
    syncGoodsFormFromIntegration(snapshot)
    ElMessage.success('已同步真实上游信息')
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '真实上游信息同步失败')
  }
}

function handleCategoriesLoaded(loadedCategories: Category[]) {
  categories.value = normalizeLoadedCategories(loadedCategories || [])
}

function stockText(row: Goods) {
  return typeof row.stock === 'number' ? row.stock : '-'
}

function stockCardClass(row: Goods) {
  const stock = Number(row.stock)
  if (!Number.isFinite(stock) || stock <= 0) return 'is-empty'
  if (stock <= 50) return 'is-low'
  return 'is-ready'
}

function cardKindUnused(row: CardKind) {
  return Number(row.unusedCount ?? row.stock ?? 0)
}

function cardKindTotal(row: CardKind) {
  const total = Number(row.totalCount || 0)
  if (total > 0) return total
  return Number(row.unusedCount || 0) + Number(row.usedCount || 0)
}

function goodsSourceLabels(row: Goods) {
  const labels = (row.integrations || [])
    .map((item) => {
      const name = integrationSupplierName(item)
      if (!name) return ''
      const status = normalizeUpstreamStatus(item.upstreamStatus)
      const notes = [status === 'OFF_SALE' ? '上游下架' : ''].filter(Boolean)
      return notes.length ? `${name}（${notes.join('，')}）` : name
    })
    .filter(Boolean)

  return labels.length ? Array.from(new Set(labels)) : ['本地']
}

function integrationSupplierName(item: GoodsIntegration) {
  const supplier = suppliers.value.find((entry) => String(entry.id) === String(item.supplierId))
  if (supplier?.name) return supplier.name
  const supplierName = item.supplierName?.trim()
  if (supplierName) return supplierName
  return ''
}

function goodsSubtitle(row: Goods) {
  const subTitle = row.subTitle?.trim()
  if (!subTitle) return ''
  const sourceName = primarySupplierName(row)
  if (sourceName && /^由\s*.+?\s*一键对接创建$/.test(subTitle)) {
    return `由 ${sourceName} 一键对接创建`
  }
  return subTitle
}

function primarySupplierName(row: Goods) {
  return (row.integrations || [])
    .map(integrationSupplierName)
    .find(Boolean) || ''
}

function normalizeUpstreamStatus(status?: string) {
  const normalized = (status || '').trim().toUpperCase()
  if (['ON_SALE', 'NORMAL', 'ENABLED', 'ONLINE', '1'].includes(normalized)) return 'ON_SALE'
  if (['OFF_SALE', 'DISABLED', 'OFFLINE', 'SOLD_OUT', '0', '2'].includes(normalized)) return 'OFF_SALE'
  if (['正常', '上架', '在售', '可售', '可购买'].includes(status || '')) return 'ON_SALE'
  if (['下架', '停售', '不可售', '不可购买', '售罄'].includes(status || '')) return 'OFF_SALE'
  return ''
}

function syncGoodsFormFromIntegration(snapshot: GoodsIntegration) {
  const name = snapshot.supplierGoodsName?.trim() || snapshot.upstreamTitle?.trim()
  const price = Number(snapshot.supplierPrice)
  const stock = Number(snapshot.upstreamStock)
  const status = normalizeUpstreamStatus(snapshot.upstreamStatus)

  if (name) form.name = name
  if (Number.isFinite(price) && price >= 0) form.price = price
  if (Number.isFinite(stock) && stock >= 0) form.stock = Math.floor(stock)
  if (status) form.status = status
}

function categoryLabel(row: Goods) {
  const categoryId = row.categoryId ? String(row.categoryId) : ''
  return (categoryId && categoryPathById.value.get(categoryId)) || row.categoryName || '-'
}

function toCategoryTreeSelectOptions(items: Category[]): Array<Category & { disabled?: boolean }> {
  return items.map((item) => ({
    ...item,
    disabled: item.enabled === false,
    children: toCategoryTreeSelectOptions(item.children || [])
  }))
}

function platformLabel(value: string) {
  return platformOptions.find((item) => item.value === value)?.label || value
}

function normalizeGoodsSalePlatforms(values?: string[]) {
  const selected = values?.filter((item) => GOODS_SALE_PLATFORM_VALUES.has(item)) || []
  return selected.length ? selected : [...DEFAULT_SALE_PLATFORMS]
}

function normalizeForbiddenSalePlatforms(values?: string[]) {
  return values?.filter((item) => GOODS_SALE_PLATFORM_VALUES.has(item)) || []
}

function statusMeta(value = '') {
  return statusOptions.find((item) => item.value === value) || { label: value || '-', type: 'info' }
}

function deliveryLabel(value = '') {
  if (value === 'AUTO') return '直充'
  return deliveryOptions.find((item) => item.value === value)?.label || value || '-'
}

function deliveryCardClass(value = '') {
  if (value === 'AUTO') return 'is-direct'
  if (value === 'CARD') return 'is-card'
  return 'is-other'
}

function benefitTypeClass(value = '') {
  const normalized = value.toLowerCase()
  if (normalized.includes('svip') || value.includes('超级')) return 'is-svip'
  if (normalized.includes('vip')) return 'is-vip'
  if (value.includes('大会员')) return 'is-premium'
  if (value.includes('会员')) return 'is-member'
  return tokenClass(value, 'type')
}

function benefitBrandClass(value = '') {
  if (value.includes('腾讯')) return 'is-tencent'
  if (value.includes('芒果')) return 'is-mango'
  if (value.includes('爱奇艺')) return 'is-iqiyi'
  if (value.includes('优酷')) return 'is-youku'
  if (value.includes('哔哩') || value.includes('B站')) return 'is-bilibili'
  return tokenClass(value, 'brand')
}

function tokenClass(value: string, prefix: string) {
  const variants = ['a', 'b', 'c', 'd', 'e']
  const index = Array.from(value || prefix).reduce((total, char) => total + char.charCodeAt(0), 0) % variants.length
  return `is-${prefix}-${variants[index]}`
}

function inferPriceLimitText(value = '') {
  const cleanValue = value.trim()
  const matched = cleanValue.match(/(?:限\s*价|限制售价|限定价格|控价)\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?\s*(?:元|块|rmb|RMB|¥)?)/i)
  if (matched?.[1]) return formatPriceLimitText(matched[1])
  return /限\s*价|限制售价|限定价格|控价/i.test(cleanValue) ? '限价' : ''
}

function formatPriceLimitText(value = '') {
  let text = value.trim().replace(/\s+/g, '')
  if (!text) return ''
  text = text.replace(/块/g, '元').replace(/RMB/gi, '元').replace(/[￥¥]/g, '')
  if (text.includes('元')) return text
  return /\d/.test(text) ? `${text}元` : text
}

function parseGoodsTags(value = '') {
  const seen = new Set<string>()
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item)) return false
      seen.add(item)
      return true
    })
}

function syncTagsFromInput() {
  form.tags = parseGoodsTags(tagsInput.value)
  tagsInput.value = form.tags.join('，')
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
  form.availablePlatforms = [...DEFAULT_SALE_PLATFORMS]
  form.forbiddenPlatforms = []
  form.benefitDurations = []
  form.benefitType = ''
  form.benefitBrand = ''
  form.tags = []
  tagsInput.value = ''
  form.priceLimitText = ''
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
  form.availablePlatforms = normalizeGoodsSalePlatforms(row.availablePlatforms)
  form.forbiddenPlatforms = normalizeForbiddenSalePlatforms(row.forbiddenPlatforms)
  form.benefitDurations = [...(row.benefitDurations || [])]
  form.benefitType = row.benefitType || ''
  form.benefitBrand = row.benefitBrand || ''
  form.tags = [...(row.tags || [])]
  tagsInput.value = form.tags.join('，')
  form.priceLimitText = row.priceLimitText || (row.priceLimited ? '限价' : '')
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

function goodsToPayload(row: Goods): GoodsCreatePayload {
  return {
    categoryId: row.categoryId,
    name: row.name,
    subTitle: row.subTitle || '',
    coverUrl: row.coverUrl || '',
    detailImages: [...(row.detailImages || [])],
    detailBlocks: row.detailBlocks?.length
      ? row.detailBlocks.map((item) => ({ ...item }))
      : [
          ...(row.detailImages || []).map((imageUrl) => ({ type: 'image' as const, imageUrl, text: '' })),
          { type: 'text' as const, imageUrl: '', text: row.description || '' }
        ],
    price: Number(row.price) || 0,
    originalPrice: row.originalPrice === undefined ? undefined : Number(row.originalPrice),
    stock: Number(row.stock) || 0,
    status: row.status || 'ON_SALE',
    deliveryType: row.deliveryType === 'AUTO' ? 'DIRECT' : row.deliveryType || 'CARD',
    cardKindId: row.cardKindId,
    platform: row.platform || 'GENERAL',
    availablePlatforms: normalizeGoodsSalePlatforms(row.availablePlatforms),
    forbiddenPlatforms: normalizeForbiddenSalePlatforms(row.forbiddenPlatforms),
    benefitDurations: [...(row.benefitDurations || [])],
    benefitType: row.benefitType || '',
    benefitBrand: row.benefitBrand || '',
    tags: [...(row.tags || [])],
    priceLimitText: row.priceLimitText || (row.priceLimited ? '限价' : ''),
    integrations: row.integrations?.length ? row.integrations.map((item) => ({ ...item })) : [],
    pollingEnabled: Boolean(row.pollingEnabled),
    monitoringEnabled: Boolean(row.monitoringEnabled),
    maxBuy: row.maxBuy || 1,
    requireRechargeAccount: Boolean(row.requireRechargeAccount),
    accountTypes: [...(row.accountTypes || [])],
    priceTemplateId: row.priceTemplateId || 'retail-default',
    priceMode: row.priceMode || 'FIXED',
    priceCoefficient: row.priceCoefficient || 1,
    priceFixedAdd: row.priceFixedAdd || 0,
    description: row.description || ''
  }
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
    selectedTableGoods.value = []
    goodsTableRef.value?.clearSelection()
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
    syncTagsFromInput()
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
      availablePlatforms: normalizeGoodsSalePlatforms(form.availablePlatforms),
      forbiddenPlatforms: normalizeForbiddenSalePlatforms(form.forbiddenPlatforms),
      benefitDurations: form.benefitDurations || [],
      benefitType: form.benefitType?.trim(),
      benefitBrand: form.benefitBrand?.trim(),
      tags: form.tags || [],
      priceLimitText: form.priceLimitText?.trim(),
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

function handleGoodsSelectionChange(selection: Goods[]) {
  selectedTableGoods.value = selection
}

function selectAllGoods() {
  if (!filteredGoods.value.length) return
  goodsTableRef.value?.clearSelection()
  filteredGoods.value.forEach((item) => goodsTableRef.value?.toggleRowSelection(item, true))
  selectedTableGoods.value = [...filteredGoods.value]
}

function clearGoodsSelection() {
  goodsTableRef.value?.clearSelection()
  selectedTableGoods.value = []
}

function resetBatchEdit(source?: Goods) {
  batchEdit.categoryEnabled = false
  batchEdit.categoryId = source?.categoryId
  batchEdit.statusEnabled = false
  batchEdit.status = source?.status || 'ON_SALE'
  batchEdit.benefitDurationsEnabled = false
  batchEdit.benefitDurations = [...(source?.benefitDurations || [])]
  batchEdit.benefitTypeEnabled = false
  batchEdit.benefitType = source?.benefitType || ''
  batchEdit.benefitBrandEnabled = false
  batchEdit.benefitBrand = source?.benefitBrand || ''
  batchEdit.priceEnabled = false
  batchEdit.price = Number(source?.price) || 0
  batchEdit.stockEnabled = false
  batchEdit.stock = Number(source?.stock) || 0
  batchEdit.deliveryTypeEnabled = false
  batchEdit.deliveryType = source?.deliveryType === 'AUTO' ? 'DIRECT' : source?.deliveryType || 'CARD'
  batchEdit.availablePlatformsEnabled = false
  batchEdit.availablePlatforms = normalizeGoodsSalePlatforms(source?.availablePlatforms)
  batchEdit.forbiddenPlatformsEnabled = false
  batchEdit.forbiddenPlatforms = normalizeForbiddenSalePlatforms(source?.forbiddenPlatforms)
}

function openBatchEdit() {
  if (!selectedTableGoods.value.length) {
    ElMessage.warning('请先选择要批量修改的商品')
    return
  }

  resetBatchEdit(selectedTableGoods.value[0])
  batchEditVisible.value = true
}

function hasBatchPatch() {
  return [
    batchEdit.categoryEnabled,
    batchEdit.statusEnabled,
    batchEdit.benefitDurationsEnabled,
    batchEdit.benefitTypeEnabled,
    batchEdit.benefitBrandEnabled,
    batchEdit.priceEnabled,
    batchEdit.stockEnabled,
    batchEdit.deliveryTypeEnabled,
    batchEdit.availablePlatformsEnabled,
    batchEdit.forbiddenPlatformsEnabled
  ].some(Boolean)
}

async function submitBatchEdit() {
  if (!selectedTableGoods.value.length) {
    ElMessage.warning('请先选择要批量修改的商品')
    return
  }
  if (!hasBatchPatch()) {
    ElMessage.warning('请选择至少一个要批量修改的字段')
    return
  }
  if (batchEdit.categoryEnabled && !batchEdit.categoryId) {
    ElMessage.warning('请选择商品分类')
    return
  }
  if (batchEdit.availablePlatformsEnabled && !batchEdit.availablePlatforms.length) {
    ElMessage.warning('请选择可售平台，或选择无限制')
    return
  }

  const rows = [...selectedTableGoods.value]
  batchSaving.value = true
  try {
    await Promise.all(
      rows.map((row) => {
        const payload = goodsToPayload(row)

        if (batchEdit.categoryEnabled) payload.categoryId = batchEdit.categoryId
        if (batchEdit.statusEnabled) payload.status = batchEdit.status
        if (batchEdit.benefitDurationsEnabled) payload.benefitDurations = [...batchEdit.benefitDurations]
        if (batchEdit.benefitTypeEnabled) payload.benefitType = batchEdit.benefitType.trim()
        if (batchEdit.benefitBrandEnabled) payload.benefitBrand = batchEdit.benefitBrand.trim()
        if (batchEdit.priceEnabled) payload.price = Number(batchEdit.price) || 0
        if (batchEdit.stockEnabled) payload.stock = Number(batchEdit.stock) || 0
        if (batchEdit.deliveryTypeEnabled) {
          payload.deliveryType = batchEdit.deliveryType
          payload.cardKindId = batchEdit.deliveryType === 'CARD' ? payload.cardKindId : undefined
          payload.requireRechargeAccount = batchEdit.deliveryType !== 'CARD' ? true : false
          payload.accountTypes = batchEdit.deliveryType === 'CARD' ? [] : payload.accountTypes
        }
        if (batchEdit.availablePlatformsEnabled) payload.availablePlatforms = normalizeGoodsSalePlatforms(batchEdit.availablePlatforms)
        if (batchEdit.forbiddenPlatformsEnabled) payload.forbiddenPlatforms = normalizeForbiddenSalePlatforms(batchEdit.forbiddenPlatforms)

        return updateGoods(row.id, payload)
      })
    )

    ElMessage.success(`已批量修改 ${rows.length} 个商品`)
    batchEditVisible.value = false
    await loadGoods()
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '批量修改失败')
  } finally {
    batchSaving.value = false
  }
}

async function deleteSelectedGoods() {
  if (!selectedTableGoods.value.length) {
    ElMessage.warning('请先选择要删除的商品')
    return
  }

  const count = selectedTableGoods.value.length
  try {
    await ElMessageBox.confirm(
      `确认删除已选 ${count} 个商品吗？删除后商品列表将不再展示，对接渠道也会一并移除。`,
      '删除商品确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        customClass: 'xiyiyun-glass-dialog goods-delete-confirm'
      }
    )
  } catch {
    return
  }

  loading.value = true
  try {
    await Promise.all(selectedTableGoods.value.map((item) => deleteGoods(item.id)))
    ElMessage.success(`已删除 ${count} 个商品`)
    clearGoodsSelection()
    await loadGoods()
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '商品删除失败')
  } finally {
    loading.value = false
  }
}

async function deleteSingleGoods(row: Goods) {
  try {
    await ElMessageBox.confirm(
      `确认删除商品「${row.name}」吗？删除后商品本身、对接渠道、库存卡密和监控数据都会一并清理。`,
      '删除商品确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        customClass: 'xiyiyun-glass-dialog goods-delete-confirm'
      }
    )
  } catch {
    return
  }

  loading.value = true
  try {
    await deleteGoods(row.id)
    ElMessage.success('商品已删除')
    clearGoodsSelection()
    await loadGoods()
  } catch (error) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '商品删除失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => form.name,
  (value) => {
    const inferredText = inferPriceLimitText(value)
    if (inferredText && !form.priceLimitText?.trim()) {
      form.priceLimitText = inferredText
    }
  }
)

onMounted(() => {
  void loadCategories()
  void loadCardKinds()
  void loadRechargeFields()
  void loadPriceTemplates()
  void loadGoods()
  void loadSuppliers()
})
</script>

<template>
  <section class="ops-grid">
    <article class="panel table-panel">
      <div class="panel-head">
        <div>
          <span class="panel-kicker">商品运营</span>
          <h2>商品列表</h2>
        </div>
        <div class="goods-table-stats">
          <span>全部 {{ goods.length }}</span>
          <span>当前 {{ filteredGoods.length }}</span>
          <span :class="{ active: selectedTableGoods.length }">已选 {{ selectedTableGoods.length }}</span>
        </div>
      </div>

      <CategoryManagerPanel
        v-model="goodsFilters.categoryId"
        class="goods-category-panel"
        @categories-loaded="handleCategoriesLoaded"
      />

      <div class="table-toolbar">
        <div class="table-filters">
          <el-input v-model="goodsFilters.search" clearable placeholder="搜索商品名称 / ID" />
          <el-tree-select
            v-model="goodsFilters.categoryId"
            :data="categoryTreeOptions"
            node-key="id"
            check-strictly
            filterable
            clearable
            :render-after-expand="false"
            popper-class="xiyiyun-tree-select-popper"
            placeholder="按商品分类查看"
            :props="{ label: 'name', children: 'children', value: 'id', disabled: 'disabled' }"
          />
          <el-select v-model="goodsFilters.platform" clearable placeholder="按销售平台选择">
            <el-option v-for="item in goodsSalePlatformOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </div>
        <div class="primary-actions">
          <el-button type="primary" :icon="Plus" @click="openCreate">新建商品</el-button>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadGoods">刷新</el-button>
        </div>
      </div>

      <div class="table-bulk-row">
        <div class="selection-actions" :class="{ active: selectedTableGoods.length }">
          <div class="selection-summary">
            <strong>批量操作</strong>
            <span>已选 {{ selectedTableGoods.length }} 个商品</span>
          </div>
          <el-button :disabled="!filteredGoods.length" @click="selectAllGoods">全选</el-button>
          <el-button :disabled="!selectedTableGoods.length" @click="clearGoodsSelection">清空</el-button>
          <el-button
            type="primary"
            :icon="Edit3"
            :disabled="!selectedTableGoods.length"
            @click="openBatchEdit"
          >
            批量修改
          </el-button>
          <el-button
            type="danger"
            :icon="Trash2"
            :disabled="!selectedTableGoods.length"
            :loading="loading"
            @click="deleteSelectedGoods"
          >
            删除已选
          </el-button>
        </div>
      </div>

      <div class="goods-table-shell">
      <el-table
        ref="goodsTableRef"
        v-loading="loading"
        :data="filteredGoods"
        class="goods-data-table"
        height="620"
        style="width: 100%"
        row-key="id"
        @selection-change="handleGoodsSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column label="商品信息" min-width="270">
          <template #default="{ row }">
            <div class="goods-info-cell">
              <strong>{{ row.name }}</strong>
              <span v-if="goodsSubtitle(row)">{{ goodsSubtitle(row) }}</span>
              <div class="goods-info-meta">
                <em>ID {{ row.id }}</em>
              </div>
              <div class="category-path-cell">{{ categoryLabel(row) }}</div>
              <div v-if="row.tags?.length || row.priceLimitText" class="goods-info-tags">
                <span v-for="tag in row.tags || []" :key="tag" class="goods-tag-chip">{{ tag }}</span>
                <span v-if="row.priceLimitText" class="price-limit-chip">限价 {{ formatPriceLimitText(row.priceLimitText) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="权益信息" min-width="190">
          <template #default="{ row }">
            <div class="stacked-field-cell">
              <div>
                <small>时间</small>
                <div class="field-card-list field-card-list--duration">
                  <span v-for="item in row.benefitDurations || []" :key="item">{{ item }}</span>
                  <em v-if="!row.benefitDurations?.length">未设置</em>
                </div>
              </div>
              <div>
                <small>类型</small>
                <div class="field-card-list field-card-list--type">
                  <span v-if="row.benefitType" :class="benefitTypeClass(row.benefitType)">{{ row.benefitType }}</span>
                  <em v-else>未设置</em>
                </div>
              </div>
              <div>
                <small>品牌</small>
                <div class="field-card-list field-card-list--brand">
                  <span v-if="row.benefitBrand" :class="benefitBrandClass(row.benefitBrand)">{{ row.benefitBrand }}</span>
                  <em v-else>未设置</em>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="销售信息" min-width="160">
          <template #default="{ row }">
            <div class="metric-stack">
              <div>
                <small>售价</small>
                <div class="field-metric-card field-metric-card--price">{{ formatMoney(row.price) }}</div>
              </div>
              <div>
                <small>库存</small>
                <div class="field-metric-card field-metric-card--stock" :class="stockCardClass(row)">
                  {{ stockText(row) }}
                </div>
              </div>
              <div>
                <small>发货</small>
                <div class="field-metric-card field-metric-card--delivery" :class="deliveryCardClass(row.deliveryType)">
                  {{ deliveryLabel(row.deliveryType) }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="渠道平台" min-width="260">
          <template #default="{ row }">
            <div class="stacked-field-cell">
              <div>
                <small>货源</small>
                <div class="field-card-list field-card-list--source">
                  <span v-for="item in goodsSourceLabels(row)" :key="item" :class="{ local: item === '本地' }">{{ item }}</span>
                </div>
              </div>
              <div>
                <small>可售</small>
                <div class="field-card-list field-card-list--saleable">
                  <span v-for="item in row.availablePlatforms || []" :key="item">{{ platformLabel(item) }}</span>
                  <em v-if="!row.availablePlatforms?.length">未设置</em>
                </div>
              </div>
              <div>
                <small>不可售</small>
                <div class="field-card-list field-card-list--forbidden">
                  <span v-for="item in row.forbiddenPlatforms || []" :key="item">{{ platformLabel(item) }}</span>
                  <em v-if="!row.forbiddenPlatforms?.length">无限制</em>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="84">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" effect="plain">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96">
          <template #default="{ row }">
            <el-button-group class="goods-row-actions">
              <el-button size="small" :icon="Edit3" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="isDirectGoods(row)" size="small" :icon="PlugZap" @click="openChannels(row)">渠道</el-button>
              <el-button v-else size="small" :icon="Upload" @click="openImport(row)">导入</el-button>
              <el-button v-if="!isDirectGoods(row)" size="small" :icon="Eye" @click="openCards(row)">查看</el-button>
              <el-button size="small" type="danger" :icon="Trash2" @click="deleteSingleGoods(row)">删除</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      </div>
    </article>
  </section>

  <el-dialog v-model="batchEditVisible" width="min(920px, calc(100vw - 72px))" class="xiyiyun-glass-dialog goods-batch-dialog" :show-close="false">
    <template #header>
      <div class="editor-header">
        <div>
          <span class="editor-kicker">批量修改</span>
          <h2>已选择 {{ selectedTableGoods.length }} 个商品</h2>
          <p>勾选要覆盖的字段，未勾选的字段会保持原商品数据不变。</p>
        </div>
        <button type="button" class="editor-close" aria-label="关闭批量修改弹窗" @click="batchEditVisible = false">
          <X :size="18" />
        </button>
      </div>
    </template>

    <div class="batch-edit-body">
      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.categoryEnabled">商品分类</el-checkbox>
        <el-tree-select
          v-model="batchEdit.categoryId"
          :disabled="!batchEdit.categoryEnabled"
          :loading="categoryLoading"
          :data="categoryTreeOptions"
          node-key="id"
          check-strictly
          filterable
          clearable
          :render-after-expand="false"
          popper-class="xiyiyun-tree-select-popper"
          placeholder="选择商品分类"
          :props="{ label: 'name', children: 'children', value: 'id', disabled: 'disabled' }"
        />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.statusEnabled">商品状态</el-checkbox>
        <el-select v-model="batchEdit.status" :disabled="!batchEdit.statusEnabled" placeholder="选择商品状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </section>

      <section class="batch-edit-card batch-edit-card--wide">
        <el-checkbox v-model="batchEdit.benefitDurationsEnabled">权益时间</el-checkbox>
        <GoodsBenefitSelector
          v-model="batchEdit.benefitDurations"
          :options="benefitDurationOptions"
          :class="{ disabled: !batchEdit.benefitDurationsEnabled }"
        />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.benefitTypeEnabled">权益类型</el-checkbox>
        <el-input v-model="batchEdit.benefitType" :disabled="!batchEdit.benefitTypeEnabled" placeholder="例如：VIP/SVIP/大会员" />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.benefitBrandEnabled">权益品牌</el-checkbox>
        <el-input v-model="batchEdit.benefitBrand" :disabled="!batchEdit.benefitBrandEnabled" placeholder="例如：腾讯视频 / 芒果TV" />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.priceEnabled">售价</el-checkbox>
        <el-input-number v-model="batchEdit.price" :disabled="!batchEdit.priceEnabled" :min="0" :precision="2" :step="0.1" controls-position="right" />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.stockEnabled">库存</el-checkbox>
        <el-input-number v-model="batchEdit.stock" :disabled="!batchEdit.stockEnabled" :min="0" :precision="0" :step="1" controls-position="right" />
      </section>

      <section class="batch-edit-card">
        <el-checkbox v-model="batchEdit.deliveryTypeEnabled">发货类型</el-checkbox>
        <el-select v-model="batchEdit.deliveryType" :disabled="!batchEdit.deliveryTypeEnabled" placeholder="选择发货类型">
          <el-option v-for="item in deliveryOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </section>

      <section class="batch-edit-card batch-edit-card--wide">
        <el-checkbox v-model="batchEdit.availablePlatformsEnabled">可售平台</el-checkbox>
        <GoodsPlatformSelector
          v-model:available-platforms="batchEdit.availablePlatforms"
          v-model:forbidden-platforms="batchEdit.forbiddenPlatforms"
          :options="goodsSalePlatformOptions"
          :class="{ disabled: !batchEdit.availablePlatformsEnabled && !batchEdit.forbiddenPlatformsEnabled }"
        />
        <div class="batch-platform-toggles">
          <el-checkbox v-model="batchEdit.forbiddenPlatformsEnabled">同时修改不可售平台</el-checkbox>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="editor-footer">
        <div class="footer-status">
          <span>只覆盖已勾选字段，支持单个或多个商品一起修改</span>
        </div>
        <div>
          <el-button :icon="X" @click="batchEditVisible = false">取消</el-button>
          <el-button type="primary" :icon="Edit3" :loading="batchSaving" @click="submitBatchEdit">保存批量修改</el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="goodsEditorVisible" width="min(1320px, calc(100vw - 96px))" class="xiyiyun-glass-dialog goods-editor-dialog" :show-close="false">
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

    <div class="editor-shell" :class="{ 'editor-shell--base': editorStep === 'base' }">
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
                    <el-tree-select
                      v-model="form.categoryId"
                      :loading="categoryLoading"
                      :data="categoryTreeOptions"
                      node-key="id"
                      check-strictly
                      filterable
                      clearable
                      :render-after-expand="false"
                      popper-class="xiyiyun-tree-select-popper"
                      placeholder="选择商品分类"
                      :props="{ label: 'name', children: 'children', value: 'id', disabled: 'disabled' }"
                    />
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
                <el-form-item label="商品标签">
                  <el-input
                    v-model="tagsInput"
                    clearable
                    placeholder="例如：热销, 官方, 自动充值"
                    @blur="syncTagsFromInput"
                  />
                  <p class="field-hint">多个标签用逗号或中文逗号分隔，重复标签会自动合并。</p>
                </el-form-item>
                <el-form-item label="权益时间">
                  <GoodsBenefitSelector v-model="form.benefitDurations" :options="benefitDurationOptions" />
                </el-form-item>
                <div class="form-grid">
                  <el-form-item label="权益类型">
                    <el-input v-model="form.benefitType" placeholder="例如：VIP/SVIP/大会员" />
                  </el-form-item>
                  <el-form-item label="权益品牌">
                    <el-input v-model="form.benefitBrand" placeholder="例如：腾讯视频 / 芒果TV / 爱奇艺" />
                  </el-form-item>
                </div>
                <el-form-item label="限价标签">
                  <el-input
                    v-model="form.priceLimitText"
                    clearable
                    placeholder="例如：10 / 10.5 / 不低于8.8，单位：元"
                  />
                  <p class="field-hint">单位为元。标题包含限价金额时会自动预填；无论是否识别到，都可以手动填写，留空则列表不展示。</p>
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
                  :options="goodsSalePlatformOptions"
                />
              </div>
            </section>

            <section v-else-if="activeGoodsModule === 'media'" class="form-section module-panel">
              <h3>商品主图</h3>
              <div class="cover-uploader">
                <el-upload :before-upload="handleCoverUpload" :show-file-list="false" :accept="imageAccept">
                  <div class="upload-tile">
                    <img v-if="form.coverUrl" :src="mediaSrc(form.coverUrl)" alt="商品主图预览" />
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
              <el-upload :before-upload="detailUploadHandler(index)" :show-file-list="false" :accept="imageAccept">
                <div class="detail-image-tile">
                  <img v-if="block.imageUrl" :src="mediaSrc(block.imageUrl)" alt="详情图预览" />
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
  margin-bottom: 12px;
}

.panel-kicker {
  display: inline-flex;
  height: 22px;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  color: #041814;
  font-size: 12px;
  font-weight: 800;
  background: linear-gradient(135deg, #85ffe6, #46b8ff);
  box-shadow: 0 10px 24px rgba(0, 255, 195, 0.12);
}

.goods-table-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.goods-table-stats span {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  color: rgba(218, 231, 244, 0.68);
  font-size: 12px;
  font-weight: 700;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.goods-table-stats span.active {
  color: #dffdf7;
  background: rgba(0, 255, 195, 0.11);
  border-color: rgba(0, 255, 195, 0.28);
}

.primary-actions,
.selection-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.selection-actions {
  width: 100%;
  min-height: 46px;
  justify-content: flex-end;
  padding: 7px 8px 7px 12px;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.025)),
    rgba(8, 18, 32, 0.44);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.selection-actions.active {
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.12), rgba(88, 166, 255, 0.08)),
    rgba(8, 18, 32, 0.5);
  border-color: rgba(0, 255, 195, 0.24);
}

.selection-summary {
  min-width: 150px;
  margin-right: auto;
  display: grid;
  gap: 2px;
}

.selection-summary strong {
  color: rgba(244, 249, 255, 0.88);
  font-size: 13px;
}

.selection-summary span {
  color: rgba(214, 226, 240, 0.48);
  font-size: 12px;
}

.selection-actions.active .selection-summary span {
  color: #baffee;
}

.panel-head h2 {
  margin: 7px 0 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 20px;
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
.goods-form :deep(.el-tree-select),
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
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 64px);
}

:global(.goods-editor-dialog .el-dialog__header),
:global(.goods-editor-dialog .el-dialog__body),
:global(.goods-editor-dialog .el-dialog__footer) {
  padding: 0;
  margin: 0;
}

:global(.goods-editor-dialog .el-dialog__body) {
  min-height: 0;
  flex: 1;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.84);
}

:global(.goods-editor-dialog .el-dialog__footer) {
  flex: 0 0 auto;
}

:global(.goods-batch-dialog.el-dialog) {
  margin-top: 48px !important;
}

:global(.goods-batch-dialog .el-dialog__header),
:global(.goods-batch-dialog .el-dialog__body),
:global(.goods-batch-dialog .el-dialog__footer) {
  padding: 0;
  margin: 0;
}

.batch-edit-body {
  max-height: min(640px, calc(100vh - 230px));
  overflow: auto;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 16px 18px 18px;
}

.batch-edit-card {
  min-height: 92px;
  display: grid;
  align-content: start;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.028)),
    rgba(10, 22, 38, 0.58);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.batch-edit-card--wide {
  grid-column: 1 / -1;
}

.batch-edit-card :deep(.el-checkbox) {
  height: 24px;
  margin-right: 0;
}

.batch-edit-card :deep(.el-checkbox__label) {
  color: rgba(244, 249, 255, 0.84);
  font-weight: 700;
}

.batch-edit-card :deep(.el-input-number),
.batch-edit-card :deep(.el-select),
.batch-edit-card :deep(.el-tree-select),
.batch-edit-card :deep(.el-input) {
  width: 100%;
}

.batch-edit-card .disabled {
  pointer-events: none;
  opacity: 0.42;
  filter: saturate(0.55);
}

.batch-platform-toggles {
  display: flex;
  align-items: center;
  padding-top: 2px;
}

@media (max-width: 860px) {
  .batch-edit-body {
    grid-template-columns: 1fr;
  }
}

.editor-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px 9px;
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
  margin: 4px 0 1px;
  color: rgba(255, 255, 255, 0.94);
  font-size: 18px;
}

.editor-header p {
  margin: 0;
  color: rgba(255, 255, 255, 0.52);
}

.editor-close {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
}

.editor-shell {
  height: min(720px, calc(100vh - 210px));
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}

.editor-shell--base {
  height: auto;
  overflow: visible;
}

.editor-main {
  min-width: 0;
  padding: 10px;
  overflow: visible;
}

.editor-shell--base .editor-main {
  padding: 8px 10px 8px;
}

.editor-steps {
  display: flex;
  gap: 6px;
  padding: 4px;
  margin-bottom: 7px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.editor-steps button {
  flex: 1;
  height: 28px;
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
  padding: 8px;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.038);
  border: 0.5px solid rgba(255, 255, 255, 0.095);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.form-section h3 {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 650;
  color: rgba(255, 255, 255, 0.86);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.form-grid.three {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.module-workbench {
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 198px;
  gap: 8px;
  align-items: start;
  overflow: visible;
}

.module-subnav {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(136px, 1fr));
  gap: 7px;
  margin-bottom: 7px;
}

.module-subnav button {
  display: grid;
  gap: 3px;
  min-height: 54px;
  padding: 8px 10px;
  border-radius: 12px;
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
  font-size: 12px;
  font-weight: 700;
}

.module-subnav button.active strong {
  color: #9ffff0;
}

.module-subnav button span {
  font-size: 11px;
  line-height: 1.25;
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
  grid-template-columns: minmax(0, 1.05fr) minmax(330px, 0.95fr);
  gap: 8px;
  align-content: start;
}

.merged-section {
  min-width: 0;
  padding-bottom: 8px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.075);
}

.merged-section:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.merged-basic-panel .merged-section:first-child {
  grid-row: span 2;
}

.merged-basic-panel .merged-section:nth-child(2) {
  padding-bottom: 8px;
}

.merged-basic-panel .merged-section:nth-child(3) {
  align-self: start;
}

.merged-basic-panel :deep(.pricing-compact),
.merged-basic-panel :deep(.pricing-compact.withRecharge) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.merged-basic-panel :deep(.pricing-compact .el-form-item__label) {
  min-height: 16px;
}

.merged-basic-panel :deep(.rule-empty.compact) {
  margin-top: 6px;
  padding: 6px 8px;
}

.merged-basic-panel :deep(.platform-inline-grid) {
  grid-template-columns: 1fr;
  gap: 7px;
}

.merged-basic-panel :deep(.platform-card-grid) {
  gap: 6px;
}

.merged-basic-panel :deep(.platform-card) {
  min-width: 76px;
  height: 34px;
  gap: 6px;
  padding: 0 8px;
  border-radius: 9px;
}

.merged-basic-panel :deep(.platform-card.unlimited) {
  min-width: 86px;
}

.merged-basic-panel :deep(.platform-card strong) {
  font-size: 12px;
}

.module-panel :deep(.el-form-item) {
  margin-bottom: 6px;
}

.module-panel :deep(.el-form-item__label) {
  margin-bottom: 3px;
  font-size: 12px;
  line-height: 1.2;
}

.module-panel :deep(.el-input__wrapper),
.module-panel :deep(.el-select__wrapper) {
  min-height: 28px;
}

.module-panel :deep(.el-input-number) {
  height: 28px;
}

.module-aside {
  align-self: start;
  padding: 10px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(0, 255, 195, 0.08), rgba(255, 255, 255, 0.035));
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.aside-kicker {
  display: inline-flex;
  width: fit-content;
  padding: 3px 7px;
  border-radius: 999px;
  color: #06110f;
  background: #00ffc3;
  font-size: 11px;
  font-weight: 700;
}

.module-aside h3 {
  margin: 9px 0 3px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
}

.module-aside p {
  margin: 0 0 10px;
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  line-height: 1.35;
}

.aside-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.aside-metrics div {
  min-width: 0;
  padding: 8px;
  border-radius: 12px;
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
  font-size: 12px;
}

.aside-metrics span,
.aside-checklist span {
  color: rgba(255, 255, 255, 0.46);
  font-size: 12px;
}

.aside-checklist {
  display: grid;
  gap: 6px;
  margin-top: 10px;
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
.benefit-options,
.field-card-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.field-card-list {
  align-items: center;
  gap: 4px;
}

.field-card-list span,
.field-card-list em,
.field-metric-card {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 8px;
  border-radius: 8px;
  font-style: normal;
  line-height: 1.1;
  font-weight: 650;
  font-size: 12px;
  white-space: nowrap;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.07);
}

.field-card-list em {
  color: rgba(217, 227, 238, 0.58);
  background: rgba(142, 156, 174, 0.12);
  border: 0.5px solid rgba(166, 181, 199, 0.18);
}

.field-card-list--duration span {
  color: #c9fff4;
  background: rgba(0, 214, 178, 0.14);
  border: 0.5px solid rgba(0, 229, 190, 0.28);
}

.goods-info-cell {
  width: 100%;
  min-width: 0;
  display: grid;
  gap: 4px;
  align-content: center;
  padding: 0;
}

.goods-info-cell strong {
  overflow: hidden;
  color: rgba(244, 249, 255, 0.9);
  font-size: 14px;
  font-weight: 750;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-info-cell span {
  max-width: 100%;
  overflow: hidden;
  color: rgba(215, 226, 239, 0.5);
  font-size: 12px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-info-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.goods-info-meta em {
  display: inline-flex;
  min-height: 20px;
  align-items: center;
  padding: 0 7px;
  border-radius: 999px;
  color: rgba(219, 231, 244, 0.62);
  font-size: 11px;
  font-style: normal;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.category-path-cell {
  width: 100%;
  min-height: 24px;
  display: flex;
  align-items: center;
  overflow: hidden;
  padding: 4px 8px;
  border-radius: 8px;
  color: #d8edff;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: rgba(63, 132, 220, 0.1);
  border: 0.5px solid rgba(111, 174, 255, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.goods-info-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.goods-tag-chip,
.price-limit-chip {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.goods-tag-chip {
  max-width: 112px;
  overflow: hidden;
  color: #d9f7ff !important;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: rgba(30, 179, 216, 0.16);
  border: 0.5px solid rgba(80, 214, 244, 0.28);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.price-limit-chip {
  color: #fff7d1 !important;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.26), rgba(244, 63, 94, 0.2));
  border: 0.5px solid rgba(251, 191, 36, 0.38);
  box-shadow: 0 10px 24px rgba(245, 158, 11, 0.12);
}

.field-hint {
  margin: 4px 0 0;
  color: rgba(213, 225, 239, 0.5);
  font-size: 11px;
  line-height: 1.3;
}

.stacked-field-cell {
  width: 100%;
  display: grid;
  gap: 5px;
}

.stacked-field-cell > div,
.metric-stack > div {
  min-width: 0;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
}

.stacked-field-cell small,
.metric-stack small {
  color: rgba(209, 224, 240, 0.48);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

.metric-stack {
  display: grid;
  gap: 6px;
}

.metric-stack > div:first-child {
  grid-column: auto;
}

.field-card-list--type span.is-vip,
.field-card-list--type span.is-type-a {
  color: #e4ddff;
  background: rgba(122, 92, 255, 0.16);
  border: 0.5px solid rgba(148, 121, 255, 0.3);
}

.field-card-list--type span.is-svip,
.field-card-list--type span.is-type-b {
  color: #ffe3f4;
  background: rgba(232, 76, 156, 0.16);
  border: 0.5px solid rgba(255, 116, 188, 0.32);
}

.field-card-list--type span.is-premium,
.field-card-list--type span.is-type-c {
  color: #fff1c8;
  background: rgba(226, 157, 48, 0.17);
  border: 0.5px solid rgba(255, 192, 86, 0.34);
}

.field-card-list--type span.is-member,
.field-card-list--type span.is-type-d {
  color: #d6fff2;
  background: rgba(32, 176, 128, 0.15);
  border: 0.5px solid rgba(74, 219, 167, 0.3);
}

.field-card-list--type span.is-type-e {
  color: #d9ecff;
  background: rgba(54, 132, 232, 0.15);
  border: 0.5px solid rgba(94, 166, 255, 0.3);
}

.field-card-list--brand span.is-tencent,
.field-card-list--brand span.is-brand-a {
  color: #d6f0ff;
  background: rgba(46, 152, 235, 0.15);
  border: 0.5px solid rgba(84, 180, 255, 0.3);
}

.field-card-list--brand span.is-mango,
.field-card-list--brand span.is-brand-b {
  color: #fff0c2;
  background: rgba(238, 164, 41, 0.16);
  border: 0.5px solid rgba(255, 196, 86, 0.32);
}

.field-card-list--brand span.is-iqiyi,
.field-card-list--brand span.is-brand-c {
  color: #dcffd6;
  background: rgba(84, 184, 64, 0.15);
  border: 0.5px solid rgba(132, 222, 104, 0.3);
}

.field-card-list--brand span.is-youku,
.field-card-list--brand span.is-brand-d {
  color: #dce8ff;
  background: rgba(96, 120, 238, 0.16);
  border: 0.5px solid rgba(128, 151, 255, 0.32);
}

.field-card-list--brand span.is-bilibili,
.field-card-list--brand span.is-brand-e {
  color: #d4fbff;
  background: rgba(36, 176, 204, 0.15);
  border: 0.5px solid rgba(84, 216, 238, 0.3);
}

.field-card-list--source span {
  color: #d8fff0;
  background: rgba(23, 171, 116, 0.15);
  border: 0.5px solid rgba(52, 209, 151, 0.28);
}

.field-card-list--source span.local {
  color: #e2e7ee;
  background: rgba(122, 135, 153, 0.15);
  border-color: rgba(166, 181, 199, 0.24);
}

.field-card-list--saleable span {
  color: #eaf7ff;
  background: rgba(68, 134, 255, 0.16);
  border: 0.5px solid rgba(106, 164, 255, 0.32);
}

.field-card-list--forbidden span {
  color: #ffe1e1;
  background: rgba(236, 77, 93, 0.14);
  border: 0.5px solid rgba(255, 112, 126, 0.3);
}

.field-card-list--forbidden em {
  color: #e7f8df;
  background: rgba(109, 180, 83, 0.12);
  border-color: rgba(145, 210, 119, 0.22);
}

.field-metric-card {
  width: fit-content;
  min-width: 64px;
}

.field-metric-card--price {
  min-width: 78px;
  color: #fff2bf;
  background: rgba(255, 184, 77, 0.16);
  border: 0.5px solid rgba(255, 205, 105, 0.34);
  font-size: 14px;
}

.field-metric-card--stock.is-ready {
  color: #d8ffdf;
  background: rgba(80, 190, 102, 0.14);
  border: 0.5px solid rgba(115, 219, 133, 0.3);
}

.field-metric-card--stock.is-low {
  color: #fff0c4;
  background: rgba(235, 164, 48, 0.15);
  border: 0.5px solid rgba(255, 191, 72, 0.32);
}

.field-metric-card--stock.is-empty {
  color: #ffcfcf;
  background: rgba(226, 67, 76, 0.14);
  border: 0.5px solid rgba(255, 105, 114, 0.3);
}

.field-metric-card--delivery.is-direct {
  color: #d9fff3;
  background: rgba(19, 182, 133, 0.18);
  border: 0.5px solid rgba(66, 226, 176, 0.34);
}

.field-metric-card--delivery.is-card {
  color: #ffe2f4;
  background: rgba(214, 78, 153, 0.14);
  border: 0.5px solid rgba(238, 118, 185, 0.3);
}

.field-metric-card--delivery.is-other {
  color: #fff0c9;
  background: rgba(230, 150, 45, 0.17);
  border: 0.5px solid rgba(255, 190, 82, 0.34);
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
  padding: 8px 14px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.09);
  background: rgba(2, 8, 16, 0.72);
  box-shadow: 0 -14px 34px rgba(0, 0, 0, 0.22);
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

.table-toolbar {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.table-toolbar .table-filters {
  flex: 1;
  flex-wrap: nowrap;
  min-width: 0;
}

.table-bulk-row {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: stretch;
  margin: 0 0 12px;
}

.table-filters :deep(.el-input),
.table-filters :deep(.el-select),
.table-filters :deep(.el-tree-select) {
  width: 220px;
  flex: 0 0 220px;
}

.table-filters :deep(.el-input__wrapper),
.table-filters :deep(.el-select__wrapper) {
  min-height: 38px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.075);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

.goods-table-shell {
  position: relative;
  z-index: 1;
  overflow: hidden;
  border-radius: 16px;
  background: rgba(3, 12, 24, 0.24);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
}

.goods-table-shell :deep(.el-table__inner-wrapper),
.goods-table-shell :deep(.el-scrollbar__wrap),
.goods-table-shell :deep(.el-table__body-wrapper) {
  overflow-x: hidden !important;
}

.goods-table-shell :deep(.el-scrollbar__bar.is-horizontal) {
  display: none;
}

.goods-data-table :deep(.el-table__header-wrapper th),
.goods-data-table :deep(.el-table__fixed-header-wrapper th) {
  height: 44px;
  color: rgba(226, 238, 250, 0.7);
  background: rgba(45, 68, 98, 0.28) !important;
  font-size: 13px;
  font-weight: 800;
}

.goods-data-table :deep(.el-table__row td) {
  padding: 8px 0;
}

.goods-data-table :deep(.el-table__row) {
  min-height: 88px;
}

.goods-data-table :deep(.el-table__cell) {
  border-bottom-color: rgba(255, 255, 255, 0.065) !important;
}

.goods-data-table :deep(.cell) {
  display: flex;
  align-items: center;
  min-width: 0;
}

.goods-data-table :deep(.el-table-column--selection .cell) {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}

.goods-data-table :deep(.el-checkbox) {
  --el-checkbox-input-height: 18px;
  --el-checkbox-input-width: 18px;
  --el-checkbox-checked-bg-color: #00ffc3;
  --el-checkbox-checked-input-border-color: #00ffc3;
  --el-checkbox-input-border-color-hover: rgba(0, 255, 195, 0.82);
  height: 30px;
  display: inline-grid;
  place-items: center;
}

.goods-data-table :deep(.el-checkbox__input) {
  display: inline-grid;
  place-items: center;
}

.goods-data-table :deep(.el-checkbox__inner) {
  width: 18px;
  height: 18px;
  border-radius: 6px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.09), rgba(255, 255, 255, 0.035)),
    rgba(12, 24, 40, 0.88);
  border: 0.5px solid rgba(197, 216, 238, 0.32);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.12),
    0 6px 14px rgba(0, 0, 0, 0.2);
  transition: background 160ms ease, border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.goods-data-table :deep(.el-checkbox:hover .el-checkbox__inner) {
  transform: translateY(-1px);
  border-color: rgba(0, 255, 195, 0.72);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.16),
    0 0 0 4px rgba(0, 255, 195, 0.08),
    0 8px 18px rgba(0, 0, 0, 0.24);
}

.goods-data-table :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
.goods-data-table :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) {
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.98), rgba(23, 190, 255, 0.86)) !important;
  border: 0.5px solid rgba(0, 255, 195, 0.92) !important;
  border-color: rgba(0, 255, 195, 0.92) !important;
  box-shadow:
    0 0 0 4px rgba(0, 255, 195, 0.12),
    0 10px 24px rgba(0, 255, 195, 0.16) !important;
}

.goods-data-table :deep(.el-checkbox__input.is-checked .el-checkbox__inner::after) {
  width: 4px;
  height: 8px;
  left: 6px;
  top: 2px;
  border-color: #041512;
  border-width: 0 2px 2px 0;
}

.goods-data-table :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner::before) {
  top: 8px;
  left: 4px;
  right: 4px;
  height: 2px;
  border-radius: 999px;
  background: #041512;
}

.goods-row-actions {
  display: grid;
  gap: 5px;
  width: 72px;
}

.goods-row-actions :deep(.el-button) {
  margin-left: 0 !important;
  width: 72px;
  min-width: 0;
  padding: 4px 7px;
  border-radius: 9px !important;
}

@media (max-width: 980px) {
  .table-toolbar,
  .panel-head {
    align-items: stretch;
    flex-direction: column;
  }

  .table-toolbar .table-filters {
    flex-wrap: wrap;
  }

  .table-filters :deep(.el-input),
  .table-filters :deep(.el-select) {
    width: min(240px, 100%);
    flex: 1 1 220px;
  }

  .primary-actions,
  .selection-actions {
    flex-wrap: wrap;
  }
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
