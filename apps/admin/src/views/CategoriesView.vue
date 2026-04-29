<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  BadgeIcon,
  BookOpen,
  BriefcaseBusiness,
  Film,
  Flame,
  Gamepad2,
  HeartHandshake,
  MonitorCog,
  Plus,
  RefreshCw,
  Rocket,
  ShoppingBag
} from 'lucide-vue-next'
import { createCategory, fetchCategories, setCategoryEnabled } from '../api/admin'
import type { Category, CategoryCreatePayload } from '../types/operations'

const categories = ref<Category[]>([])
const loading = ref(false)
const saving = ref(false)
const operatingId = ref('')
const selectedRootId = ref<string>('')

const form = reactive<CategoryCreatePayload>({
  parentId: undefined,
  name: '',
  sort: 10,
  enabled: true
})

const categoryTree = computed(() => buildTree(categories.value))
const parentOptions = computed(() => categories.value.filter((item) => (item.level || 1) < 5))
const rootCategories = computed(() => categoryTree.value)
const selectedRoot = computed(() => rootCategories.value.find((item) => String(item.id) === selectedRootId.value) || rootCategories.value[0])
const selectedDescendants = computed(() => (selectedRoot.value ? flattenTree(selectedRoot.value.children || []) : []))

onMounted(loadCategories)

function buildTree(items: Category[]) {
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

function flattenTree(nodes: Category[], result: Category[] = []) {
  nodes.forEach((node) => {
    result.push(node)
    flattenTree(node.children || [], result)
  })
  return result
}

async function loadCategories() {
  loading.value = true
  try {
    const list = await fetchCategories()
    categories.value = flattenTree(buildTree(list))
    selectedRootId.value ||= String(buildTree(list)[0]?.id || '')
  } catch {
    ElMessage.error('分类列表加载失败')
  } finally {
    loading.value = false
  }
}

function selectRoot(row: Category) {
  selectedRootId.value = String(row.id)
}

function iconForCategory(row: Category) {
  const name = row.name || ''
  if (/视频|会员|影视|音频/.test(name)) return Film
  if (/游戏|点券|手游/.test(name)) return Gamepad2
  if (/人工|代办|资料|办公/.test(name)) return BriefcaseBusiness
  if (/API|秒充|加速/.test(name)) return Rocket
  if (/热门|特惠|福利/.test(name)) return Flame
  if (/教育|阅读|知识/.test(name)) return BookOpen
  if (/电商|商品|专区/.test(name)) return ShoppingBag
  if (/生活|服务|权益/.test(name)) return HeartHandshake
  if (/测试|监控/.test(name)) return MonitorCog
  return BadgeIcon
}

function depthLabel(row?: Category) {
  if (!row) return '0 级'
  return `第 ${row.level || 1} 级`
}

async function submitCategory() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  saving.value = true
  try {
    await createCategory({
      parentId: form.parentId || undefined,
      name: form.name.trim(),
      sort: form.sort,
      enabled: form.enabled
    })
    ElMessage.success('分类已创建')
    form.name = ''
    form.parentId = undefined
    form.sort = 10
    form.enabled = true
    await loadCategories()
  } catch {
    ElMessage.error('分类创建失败')
  } finally {
    saving.value = false
  }
}

async function toggleCategory(row: Category) {
  operatingId.value = String(row.id)
  try {
    await setCategoryEnabled(row.id, !row.enabled)
    ElMessage.success(row.enabled ? '分类已停用' : '分类已启用')
    await loadCategories()
  } catch {
    ElMessage.error('分类状态更新失败')
  } finally {
    operatingId.value = ''
  }
}
</script>

<template>
  <section class="category-board">
    <article class="category-canvas liquid-admin-panel">
      <div class="catalog-sidebar">
        <span>目录</span>
        <span>分类</span>
      </div>

      <div class="catalog-content">
        <div class="panel-head">
          <div>
            <h2>分类管理</h2>
            <span>{{ categories.length }} 个节点，当前 {{ selectedRoot?.name || '未选择' }} · {{ depthLabel(selectedRoot) }}</span>
          </div>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadCategories">刷新</el-button>
        </div>

        <section class="category-strip" aria-label="一级分类目录">
          <button
            v-for="item in rootCategories"
            :key="item.id"
            type="button"
            class="category-icon-card root"
            :class="{ active: String(item.id) === String(selectedRoot?.id) }"
            @click="selectRoot(item)"
          >
            <span class="icon-bubble"><component :is="iconForCategory(item)" :size="30" /></span>
            <strong>{{ item.name }}</strong>
            <em>{{ item.children?.length || 0 }} 个子类</em>
          </button>
        </section>

        <section class="category-matrix" aria-label="所选分类下级">
          <button
            v-for="item in selectedDescendants"
            :key="item.id"
            type="button"
            class="category-icon-card"
            :style="{ '--depth': String(Math.max((item.level || 1) - 1, 0)) }"
            @click="form.parentId = item.id"
          >
            <span class="icon-bubble"><component :is="iconForCategory(item)" :size="26" /></span>
            <strong>{{ item.name }}</strong>
            <em>{{ depthLabel(item) }} · 排序 {{ item.sort }}</em>
            <small :data-enabled="item.enabled">{{ item.enabled ? '启用' : '停用' }}</small>
          </button>
        </section>
      </div>
    </article>

    <article class="create-panel liquid-admin-panel">
      <div class="panel-head compact">
        <div>
          <h2>新增分类</h2>
          <span>最多五级，建议挂到叶子节点</span>
        </div>
        <el-button type="primary" :icon="Plus" :loading="saving" @click="submitCategory">创建分类</el-button>
      </div>

      <el-form :model="form" label-position="top" class="category-form compact-form">
        <el-form-item label="父级分类">
          <el-select v-model="form.parentId" clearable filterable placeholder="不选则创建一级分类">
            <el-option
              v-for="item in parentOptions"
              :key="item.id"
              :label="`${'· '.repeat(Math.max((item.level || 1) - 1, 0))}${item.name}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分类名称">
          <el-input v-model="form.name" placeholder="例如：会员周卡" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :step="10" controls-position="right" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.enabled">创建后启用</el-checkbox>
        </el-form-item>
      </el-form>
    </article>

    <article class="tree-panel liquid-admin-panel">
      <div class="panel-head compact">
        <div>
          <h2>层级明细</h2>
          <span>用于停用、排序查看和定位父级</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="categoryTree" row-key="id" height="280" default-expand-all style="width: 100%">
        <el-table-column prop="name" label="分类名称" min-width="240" />
        <el-table-column label="层级" width="100">
          <template #default="{ row }">{{ depthLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="100" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span class="status-pill" :data-enabled="row.enabled">{{ row.enabled ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="operatingId === String(row.id)" @click="toggleCategory(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>
</template>

<style scoped>
.category-board {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  grid-template-rows: minmax(420px, auto) auto;
  gap: 14px;
}

.category-canvas,
.create-panel,
.tree-panel {
  overflow: hidden;
  border-radius: 22px;
}

.category-canvas {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  min-height: 560px;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.03)),
    rgba(255, 255, 255, 0.04);
}

.catalog-sidebar {
  display: grid;
  grid-template-rows: 148px 1fr;
  border-right: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
}

.catalog-sidebar span {
  display: grid;
  place-items: start center;
  padding-top: 34px;
  color: rgba(255, 255, 255, 0.58);
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.08);
}

.catalog-content {
  min-width: 0;
  padding: 18px 22px 22px;
}

.create-panel,
.tree-panel {
  padding: 18px;
}

.tree-panel {
  grid-column: 1;
  grid-row: 2;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head.compact {
  margin-bottom: 12px;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.45);
  font-size: 13px;
}

.category-form :deep(.el-input-number),
.category-form :deep(.el-select) {
  width: 100%;
}

.compact-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

.category-strip,
.category-matrix {
  display: grid;
  gap: 18px 20px;
}

.category-strip {
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  align-items: start;
  padding: 8px 0 22px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.08);
}

.category-matrix {
  grid-template-columns: repeat(auto-fill, minmax(92px, 1fr));
  align-items: start;
  padding-top: 22px;
}

.category-icon-card {
  position: relative;
  min-width: 0;
  display: grid;
  justify-items: center;
  gap: 7px;
  padding: 10px 8px;
  color: rgba(255, 255, 255, 0.76);
  text-align: center;
  border: 0.5px solid transparent;
  border-radius: 18px;
  background: transparent;
  cursor: pointer;
  transition: transform 150ms ease, background 150ms ease, border-color 150ms ease;
}

.category-icon-card:hover,
.category-icon-card.active {
  transform: translateY(-2px);
  border-color: rgba(0, 255, 195, 0.24);
  background: rgba(0, 255, 195, 0.07);
}

.category-icon-card:active {
  transform: scale(0.98);
}

.icon-bubble {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  color: #071410;
  background:
    radial-gradient(circle at 30% 18%, rgba(255, 255, 255, 0.72), transparent 24%),
    linear-gradient(135deg, #00ffc3, #3aa5ff);
  box-shadow: 0 12px 26px rgba(0, 255, 195, 0.13);
}

.category-icon-card.root .icon-bubble {
  width: 64px;
  height: 64px;
}

.category-icon-card strong {
  max-width: 96px;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-icon-card em {
  color: rgba(255, 255, 255, 0.44);
  font-size: 12px;
  font-style: normal;
}

.category-icon-card small {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 6px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.5);
  font-size: 11px;
  background: rgba(255, 255, 255, 0.06);
}

.category-icon-card small[data-enabled="true"] {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
}

.category-form :deep(.el-checkbox__label) {
  color: rgba(255, 255, 255, 0.68);
}

.status-pill {
  display: inline-flex;
  padding: 4px 9px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.54);
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.status-pill[data-enabled="true"] {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border-color: rgba(0, 255, 195, 0.2);
}
</style>
