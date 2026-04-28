<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Eye, PlugZap, Plus, RefreshCw, Trash2, Upload } from 'lucide-vue-next'
import {
  createGoods,
  createGoodsChannel,
  deleteGoodsChannel,
  fetchCategories,
  fetchGoods,
  fetchGoodsCards,
  fetchGoodsChannels,
  fetchSuppliers,
  importGoodsCards
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

const platformOptions = [
  { label: '移动 H5', value: 'h5' },
  { label: 'PC 网页', value: 'pc' },
  { label: '微信小程序', value: 'miniapp' }
]

const form = reactive<GoodsCreatePayload>({
  categoryId: undefined,
  name: '',
  price: 0,
  originalPrice: undefined,
  status: 'ON_SALE',
  deliveryType: 'CARD',
  platform: 'GENERAL',
  availablePlatforms: ['h5', 'pc'],
  forbiddenPlatforms: [],
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
    await createGoods({
      ...form,
      name: form.name.trim(),
      description: form.description?.trim()
    })
    ElMessage.success('商品已新增')
    form.name = ''
    form.price = 0
    form.originalPrice = undefined
    form.status = 'ON_SALE'
    form.deliveryType = 'CARD'
    form.platform = 'GENERAL'
    form.availablePlatforms = ['h5', 'pc']
    form.forbiddenPlatforms = []
    form.description = ''
    await loadGoods()
  } catch {
    ElMessage.error('新增商品失败')
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
        <h2>新增商品</h2>
        <span>卡密/自动发货商品资料</span>
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
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="上架" value="ON_SALE" />
            <el-option label="下架" value="OFF_SALE" />
          </el-select>
        </el-form-item>
        <el-form-item label="发货类型">
          <el-select v-model="form.deliveryType">
            <el-option label="卡密" value="CARD" />
            <el-option label="自动发货" value="AUTO" />
            <el-option label="人工处理" value="MANUAL" />
          </el-select>
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
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-button type="primary" :icon="Plus" :loading="saving" @click="submitGoods">新增商品</el-button>
      </el-form>
    </article>

    <article class="panel table-panel">
      <div class="panel-head">
        <h2>商品列表</h2>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadGoods">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="goods" height="640" style="width: 100%">
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
        <el-table-column prop="deliveryType" label="发货类型" width="120" />
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
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="库存/渠道" width="250" fixed="right">
          <template #default="{ row }">
            <el-button-group>
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
.channel-form :deep(.el-input-number),
.channel-form :deep(.el-select) {
  width: 100%;
}

.platform-checks,
.platform-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.platform-checks :deep(.el-checkbox) {
  height: 30px;
  margin-right: 0;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
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
