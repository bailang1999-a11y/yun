<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
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
  Supplier
} from '../types/operations'

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
const selectedGoods = ref<Goods>()
const cardText = ref('')
const editingGoodsId = ref<Goods['id']>()
const detailImageText = ref('')
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
  { label: '自动直充', value: 'AUTO' },
  { label: '自动直充', value: 'DIRECT' },
  { label: '人工代充', value: 'MANUAL' }
]

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
  maxBuy: 1,
  requireRechargeAccount: false,
  accountTypes: [],
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
  return deliveryOptions.find((item) => item.value === value)?.label || value || '-'
}

function resetForm() {
  editingGoodsId.value = undefined
  form.name = ''
  form.subTitle = ''
  form.coverUrl = ''
  form.detailImages = []
  detailImageText.value = ''
  form.price = 0
  form.originalPrice = undefined
  form.stock = 0
  form.status = 'ON_SALE'
  form.deliveryType = 'CARD'
  form.platform = 'GENERAL'
  form.availablePlatforms = ['douyin', 'taobao', 'private']
  form.forbiddenPlatforms = []
  form.maxBuy = 1
  form.requireRechargeAccount = false
  form.accountTypes = []
  form.priceMode = 'FIXED'
  form.priceCoefficient = 1
  form.priceFixedAdd = 0
  form.description = ''
}

function fillForm(row: Goods) {
  editingGoodsId.value = row.id
  form.categoryId = row.categoryId
  form.name = row.name
  form.subTitle = row.subTitle || ''
  form.coverUrl = row.coverUrl || ''
  form.detailImages = [...(row.detailImages || [])]
  detailImageText.value = form.detailImages.join('\n')
  form.price = Number(row.price) || 0
  form.originalPrice = row.originalPrice === undefined ? undefined : Number(row.originalPrice)
  form.stock = Number(row.stock) || 0
  form.status = row.status || 'ON_SALE'
  form.deliveryType = row.deliveryType === 'DIRECT' ? 'AUTO' : row.deliveryType || 'CARD'
  form.platform = row.platform || 'GENERAL'
  form.availablePlatforms = row.availablePlatforms?.length ? [...row.availablePlatforms] : ['private']
  form.forbiddenPlatforms = row.forbiddenPlatforms?.length ? [...row.forbiddenPlatforms] : []
  form.maxBuy = row.maxBuy || 1
  form.requireRechargeAccount = Boolean(row.requireRechargeAccount)
  form.accountTypes = [...(row.accountTypes || [])]
  form.priceMode = row.priceMode || 'FIXED'
  form.priceCoefficient = row.priceCoefficient || 1
  form.priceFixedAdd = row.priceFixedAdd || 0
  form.description = row.description || ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
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
    const payload = {
      ...form,
      name: form.name.trim(),
      subTitle: form.subTitle?.trim(),
      coverUrl: form.coverUrl?.trim(),
      detailImages: detailImageText.value
        .split('\n')
        .map((item) => item.trim())
        .filter(Boolean),
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
    <article class="panel create-panel">
      <div class="panel-head">
        <h2>{{ formTitle }}</h2>
        <span>{{ formSubtitle }}</span>
      </div>

      <el-form :model="form" label-position="top" class="goods-form">
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
        <el-form-item label="商品名称">
          <el-input v-model="form.name" placeholder="例如：视频会员月卡" />
        </el-form-item>
        <el-form-item label="商品副标题">
          <el-input v-model="form.subTitle" placeholder="例如：自动发卡，秒级到账" />
        </el-form-item>
        <div class="media-grid">
          <el-form-item label="商品主图">
            <el-input v-model="form.coverUrl" placeholder="主图 URL，建议 800x800" />
          </el-form-item>
          <div v-if="form.coverUrl" class="image-preview">
            <img :src="form.coverUrl" alt="商品主图预览" />
          </div>
        </div>
        <el-form-item label="详情图">
          <el-input
            v-model="detailImageText"
            type="textarea"
            :rows="3"
            placeholder="每行一个详情图 URL，可用于商品详情页轮播/说明"
          />
        </el-form-item>
        <el-form-item label="售价">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="划线原价">
          <el-input-number
            v-model="form.originalPrice"
            :min="0"
            :precision="2"
            :step="1"
            controls-position="right"
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="发货类型">
          <el-select v-model="form.deliveryType">
            <el-option v-for="item in deliveryOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大购买数量">
          <el-input-number v-model="form.maxBuy" :min="1" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="售价模式">
          <el-segmented
            v-model="form.priceMode"
            :options="[
              { label: '固定售价', value: 'FIXED' },
              { label: '动态加价', value: 'DYNAMIC' }
            ]"
          />
        </el-form-item>
        <div v-if="form.priceMode === 'DYNAMIC'" class="inline-grid">
          <el-form-item label="成本系数">
            <el-input-number v-model="form.priceCoefficient" :min="0" :precision="2" :step="0.1" controls-position="right" />
          </el-form-item>
          <el-form-item label="固定加价">
            <el-input-number v-model="form.priceFixedAdd" :min="0" :precision="2" :step="1" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item v-if="form.deliveryType !== 'CARD'" label="充值账号要求">
          <el-checkbox v-model="form.requireRechargeAccount">要求用户填写充值账号</el-checkbox>
          <el-checkbox-group v-if="form.requireRechargeAccount" v-model="form.accountTypes" class="platform-checks account-checks">
            <el-checkbox v-for="item in accountTypeOptions" :key="item.value" :label="item.value">
              {{ item.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
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
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="商品描述、使用教程、兑换链接、注意事项" />
        </el-form-item>
        <div class="form-actions">
          <el-button v-if="editingGoodsId" :icon="X" @click="resetForm">取消编辑</el-button>
          <el-button type="primary" :icon="editingGoodsId ? Edit3 : Plus" :loading="saving" @click="submitGoods">
            {{ editingGoodsId ? '保存修改' : '新增商品' }}
          </el-button>
        </div>
      </el-form>
    </article>

    <article class="panel table-panel">
      <div class="panel-head">
        <h2>商品列表</h2>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadGoods">刷新</el-button>
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
              <el-button size="small" :icon="Edit3" @click="fillForm(row)">编辑</el-button>
              <el-button v-if="isDirectGoods(row)" size="small" :icon="PlugZap" @click="openChannels(row)">渠道</el-button>
              <el-button v-else size="small" :icon="Upload" @click="openImport(row)">导入</el-button>
              <el-button v-if="!isDirectGoods(row)" size="small" :icon="Eye" @click="openCards(row)">查看</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>

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
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
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

.media-grid,
.inline-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 88px;
  gap: 10px;
  align-items: end;
}

.inline-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
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
.platform-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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
