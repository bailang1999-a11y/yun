<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  BadgeIcon,
  BookOpen,
  BriefcaseBusiness,
  Edit3,
  Film,
  Flame,
  FolderPlus,
  Gamepad2,
  HeartHandshake,
  MonitorCog,
  MoreVertical,
  Plus,
  RefreshCw,
  Rocket,
  Save,
  ShoppingBag,
  Trash2,
  X
} from 'lucide-vue-next'
import { createCategory, deleteCategory, fetchCategories, updateCategory } from '../api/admin'
import type { Category, CategoryCreatePayload, CategoryUpdatePayload } from '../types/operations'

const props = defineProps<{
  modelValue?: string | number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
  select: [category: Category | null]
  'categories-loaded': [categories: Category[]]
}>()

const categories = ref<Category[]>([])
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const selectedRootId = ref<string>('')
const selectedCategoryId = ref<string>(props.modelValue == null ? '' : String(props.modelValue))
const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingCategoryId = ref<Category['id']>()
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  row: undefined as Category | undefined
})

const form = reactive<CategoryCreatePayload & { icon: string; nickname: string }>({
  parentId: undefined,
  name: '',
  nickname: '',
  icon: 'badge',
  sort: 10,
  enabled: true
})

const categoryTree = computed(() => buildTree(categories.value))
const parentOptions = computed(() =>
  categories.value.filter((item) => {
    if ((item.level || 1) >= 5) return false
    if (editorMode.value !== 'edit' || editingCategoryId.value === undefined) return true
    const blockedIds = new Set([
      String(editingCategoryId.value),
      ...flattenTree(findInTree(categoryTree.value, editingCategoryId.value)?.children || []).map((child) => String(child.id))
    ])
    return !blockedIds.has(String(item.id))
  })
)
const rootCategories = computed(() => categoryTree.value)
const selectedRoot = computed(() => rootCategories.value.find((item) => String(item.id) === selectedRootId.value) || rootCategories.value[0])
const selectedDescendants = computed(() => (selectedRoot.value ? flattenTree(selectedRoot.value.children || []) : []))
const selectedCategory = computed(() => findInTree(categoryTree.value, selectedCategoryId.value))
const editorTitle = computed(() => (editorMode.value === 'edit' ? '编辑分类' : '新增分类'))
const editorActionText = computed(() => (editorMode.value === 'edit' ? '保存修改' : '创建分类'))

const iconOptions = [
  { key: 'badge', label: '通用', icon: BadgeIcon },
  { key: 'film', label: '影音', icon: Film },
  { key: 'gamepad', label: '游戏', icon: Gamepad2 },
  { key: 'business', label: '办公', icon: BriefcaseBusiness },
  { key: 'rocket', label: '秒充', icon: Rocket },
  { key: 'flame', label: '热门', icon: Flame },
  { key: 'book', label: '教育', icon: BookOpen },
  { key: 'bag', label: '电商', icon: ShoppingBag },
  { key: 'heart', label: '生活', icon: HeartHandshake },
  { key: 'monitor', label: '监控', icon: MonitorCog }
]
const iconMap = new Map(iconOptions.map((item) => [item.key, item.icon]))

watch(
  () => props.modelValue,
  (value) => {
    const nextId = value == null ? '' : String(value)
    if (nextId === selectedCategoryId.value) return
    selectedCategoryId.value = nextId
    syncRootToSelection()
  }
)

onMounted(() => {
  loadCategories()
  window.addEventListener('click', closeContextMenu)
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', closeContextMenu)
  window.removeEventListener('keydown', handleKeydown)
})

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
    const tree = buildTree(await fetchCategories())
    categories.value = flattenTree(tree)
    syncRootToSelection()
    selectedRootId.value ||= String(tree[0]?.id || '')
    emit('categories-loaded', categories.value)
    if (selectedCategoryId.value && !selectedCategory.value) selectCategory(null)
  } catch {
    ElMessage.error('分类列表加载失败')
  } finally {
    loading.value = false
  }
}

function selectRoot(row: Category) {
  selectedRootId.value = String(row.id)
  selectCategory(row)
}

function selectChild(row: Category) {
  form.parentId = row.id
  selectCategory(row)
}

function selectCategory(row: Category | null) {
  const nextId = row == null ? '' : String(row.id)
  selectedCategoryId.value = nextId
  emit('update:modelValue', row?.id ?? null)
  emit('select', row)
}

function syncRootToSelection() {
  const selectedRootItem = findRootForId(categoryTree.value, selectedCategoryId.value)
  if (selectedRootItem) selectedRootId.value = String(selectedRootItem.id)
}

function iconForCategory(row: Category) {
  const savedIcon = row.icon || row.iconKey
  if (savedIcon && iconMap.has(savedIcon)) return iconMap.get(savedIcon)
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

function findInTree(nodes: Category[], id?: Category['id'] | string): Category | undefined {
  if (id === undefined || id === '') return undefined
  for (const node of nodes) {
    if (String(node.id) === String(id)) return node
    const child = findInTree(node.children || [], id)
    if (child) return child
  }
  return undefined
}

function findRootForId(nodes: Category[], id?: Category['id'] | string): Category | undefined {
  if (id === undefined || id === '') return undefined
  for (const node of nodes) {
    if (String(node.id) === String(id)) return node
    if (findInTree(node.children || [], id)) return node
  }
  return undefined
}

function resetForm() {
  form.parentId = undefined
  form.name = ''
  form.nickname = ''
  form.icon = 'badge'
  form.sort = 10
  form.enabled = true
  editingCategoryId.value = undefined
}

function openCreate(parentId?: Category['id']) {
  closeContextMenu()
  resetForm()
  editorMode.value = 'create'
  form.parentId = parentId || undefined
  form.sort = 10
  form.enabled = true
  editorVisible.value = true
}

function openEdit(row: Category) {
  closeContextMenu()
  editorMode.value = 'edit'
  editingCategoryId.value = row.id
  form.parentId = row.parentId || undefined
  form.name = row.name || ''
  form.nickname = row.nickname || ''
  form.icon = row.icon || row.iconKey || 'badge'
  form.sort = Number(row.sort ?? 10)
  form.enabled = row.enabled ?? true
  editorVisible.value = true
}

function openContextMenu(event: MouseEvent, row: Category) {
  contextMenu.visible = true
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.row = row
}

function closeContextMenu() {
  contextMenu.visible = false
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeContextMenu()
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function editContextRow() {
  if (contextMenu.row) openEdit(contextMenu.row)
}

function createChildForContextRow() {
  if (contextMenu.row) openCreate(contextMenu.row.id)
}

function removeContextRow() {
  if (contextMenu.row) removeCategory(contextMenu.row)
}

async function submitCategory() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  saving.value = true
  try {
    const payload: CategoryUpdatePayload = {
      parentId: form.parentId || undefined,
      name: form.name.trim(),
      nickname: form.nickname.trim() || undefined,
      icon: form.icon || undefined,
      iconKey: form.icon || undefined,
      sort: form.sort,
      enabled: form.enabled
    }
    if (editorMode.value === 'edit' && editingCategoryId.value !== undefined) {
      await updateCategory(editingCategoryId.value, payload)
      ElMessage.success('分类已更新')
    } else {
      await createCategory(payload)
      ElMessage.success('分类已创建')
    }
    editorVisible.value = false
    resetForm()
    await loadCategories()
  } catch (error) {
    ElMessage.error(errorMessage(error, editorMode.value === 'edit' ? '分类更新失败' : '分类创建失败'))
  } finally {
    saving.value = false
  }
}

async function removeCategory(row: Category) {
  closeContextMenu()
  try {
    await ElMessageBox.confirm(`确认删除「${row.name}」？存在子分类或已被商品引用时系统会拒绝删除。`, '删除分类', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  deleting.value = true
  try {
    await deleteCategory(row.id)
    ElMessage.success('分类已删除')
    if (String(selectedRootId.value) === String(row.id)) selectedRootId.value = ''
    if (String(selectedCategoryId.value) === String(row.id)) selectCategory(null)
    await loadCategories()
  } catch (error) {
    ElMessage.error(errorMessage(error, '分类删除失败'))
  } finally {
    deleting.value = false
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
            <span>{{ categories.length }} 个节点，当前 {{ selectedCategory?.name || selectedRoot?.name || '未选择' }} · {{ depthLabel(selectedCategory || selectedRoot) }}</span>
          </div>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadCategories">刷新</el-button>
        </div>

        <section class="category-strip" aria-label="一级分类目录">
          <button
            v-for="item in rootCategories"
            :key="item.id"
            type="button"
            class="category-icon-card root"
            :class="{ active: String(item.id) === selectedRootId || String(item.id) === selectedCategoryId }"
            @click="selectRoot(item)"
            @contextmenu.prevent="openContextMenu($event, item)"
          >
            <span class="icon-bubble"><component :is="iconForCategory(item)" :size="30" /></span>
            <strong>{{ item.name }}</strong>
            <em>{{ item.children?.length || 0 }} 个子类</em>
            <span class="card-more" aria-hidden="true"><MoreVertical :size="15" /></span>
          </button>
          <button type="button" class="category-icon-card root add-card" @click="openCreate()">
            <span class="icon-bubble"><Plus :size="30" /></span>
            <strong>新增分类</strong>
            <em>一级分类</em>
          </button>
        </section>

        <section class="category-matrix" aria-label="所选分类下级">
          <button
            v-for="item in selectedDescendants"
            :key="item.id"
            type="button"
            class="category-icon-card"
            :class="{ active: String(item.id) === selectedCategoryId }"
            :style="{ '--depth': String(Math.max((item.level || 1) - 1, 0)) }"
            @click="selectChild(item)"
            @contextmenu.prevent="openContextMenu($event, item)"
          >
            <span class="icon-bubble"><component :is="iconForCategory(item)" :size="26" /></span>
            <strong>{{ item.name }}</strong>
            <em>{{ depthLabel(item) }} · 排序 {{ item.sort }}</em>
            <small :data-enabled="item.enabled">{{ item.enabled ? '启用' : '停用' }}</small>
            <span class="card-more" aria-hidden="true"><MoreVertical :size="15" /></span>
          </button>
          <button type="button" class="category-icon-card add-card" @click="openCreate(selectedRoot?.id)">
            <span class="icon-bubble"><FolderPlus :size="28" /></span>
            <strong>新增分类</strong>
            <em>{{ selectedRoot ? `挂到 ${selectedRoot.name}` : '选择父级' }}</em>
          </button>
        </section>
      </div>
    </article>

    <Teleport to="body">
      <div
        v-if="contextMenu.visible && contextMenu.row"
        class="category-context-menu"
        :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        @click.stop
      >
        <button type="button" @click="editContextRow">
          <Edit3 :size="16" />
          <span>编辑</span>
        </button>
        <button type="button" @click="createChildForContextRow">
          <FolderPlus :size="16" />
          <span>添加子分类</span>
        </button>
        <button type="button" class="danger" :disabled="deleting" @click="removeContextRow">
          <Trash2 :size="16" />
          <span>删除</span>
        </button>
      </div>

      <el-dialog v-model="editorVisible" :title="editorTitle" width="540px" destroy-on-close class="category-editor-dialog" @closed="resetForm">
        <el-form :model="form" label-position="top" class="category-form dialog-form">
          <el-form-item label="图标">
            <div class="icon-picker" role="radiogroup" aria-label="选择分类图标">
              <button
                v-for="option in iconOptions"
                :key="option.key"
                type="button"
                :class="{ active: form.icon === option.key }"
                :aria-pressed="form.icon === option.key"
                @click="form.icon = option.key"
              >
                <component :is="option.icon" :size="18" />
                <span>{{ option.label }}</span>
              </button>
            </div>
          </el-form-item>
          <el-form-item label="分类名称">
            <el-input v-model="form.name" placeholder="例如：会员周卡" />
          </el-form-item>
          <el-form-item label="分类昵称">
            <el-input v-model="form.nickname" placeholder="用于前台展示或运营别名，可选" />
          </el-form-item>
          <el-form-item label="父级分类">
            <el-select v-model="form.parentId" clearable filterable placeholder="不选则为一级分类">
              <el-option
                v-for="item in parentOptions"
                :key="item.id"
                :label="`${'· '.repeat(Math.max((item.level || 1) - 1, 0))}${item.name}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <div class="form-grid">
            <el-form-item label="排序">
              <el-input-number v-model="form.sort" :min="0" :step="10" controls-position="right" />
            </el-form-item>
            <el-form-item label="启用状态">
              <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
            </el-form-item>
          </div>
        </el-form>
        <template #footer>
          <el-button :icon="X" @click="editorVisible = false">取消</el-button>
          <el-button type="primary" :icon="editorMode === 'edit' ? Save : Plus" :loading="saving" @click="submitCategory">
            {{ editorActionText }}
          </el-button>
        </template>
      </el-dialog>
    </Teleport>
  </section>
</template>

<style scoped>
.category-board {
  display: block;
  min-width: 0;
}

.category-canvas {
  overflow: hidden;
  border-radius: 22px;
}

.category-canvas {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  min-height: calc(100vh - 190px);
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
  padding: 20px 24px 28px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
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

.category-strip,
.category-matrix {
  display: grid;
  gap: 18px 20px;
}

.category-strip {
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  align-items: start;
  padding: 8px 0 22px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.08);
}

.category-matrix {
  grid-template-columns: repeat(auto-fill, minmax(106px, 1fr));
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

.category-icon-card.add-card {
  border-color: rgba(255, 255, 255, 0.1);
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.06), rgba(58, 165, 255, 0.04)),
    rgba(255, 255, 255, 0.025);
}

.category-icon-card.add-card .icon-bubble {
  color: rgba(255, 255, 255, 0.9);
  background:
    linear-gradient(135deg, rgba(0, 255, 195, 0.22), rgba(58, 165, 255, 0.18)),
    rgba(255, 255, 255, 0.08);
  border: 0.5px dashed rgba(255, 255, 255, 0.24);
  box-shadow: none;
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

.card-more {
  position: absolute;
  top: 8px;
  left: 8px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.34);
  background: rgba(255, 255, 255, 0.035);
  opacity: 0;
  transition: opacity 150ms ease, color 150ms ease, background 150ms ease;
}

.category-icon-card:hover .card-more,
.category-icon-card.active .card-more {
  color: rgba(255, 255, 255, 0.8);
  background: rgba(255, 255, 255, 0.08);
  opacity: 1;
}

.icon-picker {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(78px, 1fr));
  gap: 8px;
  width: 100%;
}

.icon-picker button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 8px;
  color: rgba(255, 255, 255, 0.62);
  border: 0.5px solid rgba(255, 255, 255, 0.09);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  cursor: pointer;
  transition: color 150ms ease, border-color 150ms ease, background 150ms ease;
}

.icon-picker button:hover,
.icon-picker button.active {
  color: #00ffc3;
  border-color: rgba(0, 255, 195, 0.32);
  background: rgba(0, 255, 195, 0.09);
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.dialog-form {
  display: grid;
  gap: 2px;
}

.category-context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 156px;
  padding: 6px;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(12, 18, 24, 0.96);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(18px);
}

.category-context-menu button {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 36px;
  padding: 0 10px;
  color: rgba(255, 255, 255, 0.76);
  border: 0;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.category-context-menu button:hover {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.08);
}

.category-context-menu button.danger {
  color: #ff8f8f;
}

.category-context-menu button.danger:hover {
  color: #ffb3b3;
  background: rgba(255, 93, 93, 0.1);
}

.category-context-menu button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

:global(.category-editor-dialog) {
  overflow: hidden;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  border-radius: 20px;
  background:
    radial-gradient(circle at 12% 0%, rgba(0, 255, 195, 0.12), transparent 30%),
    linear-gradient(145deg, rgba(16, 24, 33, 0.96), rgba(6, 10, 15, 0.96));
  box-shadow: 0 28px 72px rgba(0, 0, 0, 0.48);
  backdrop-filter: blur(24px);
}

:global(.category-editor-dialog .el-dialog__header) {
  display: flex;
  align-items: center;
  min-height: 52px;
  padding: 16px 18px 10px;
  margin: 0;
}

:global(.category-editor-dialog .el-dialog__title) {
  color: rgba(255, 255, 255, 0.9);
  font-size: 17px;
  font-weight: 700;
}

:global(.category-editor-dialog .el-dialog__headerbtn) {
  top: 10px;
  right: 10px;
  width: 34px;
  height: 34px;
  border-radius: 12px;
}

:global(.category-editor-dialog .el-dialog__headerbtn:hover) {
  background: rgba(255, 255, 255, 0.08);
}

:global(.category-editor-dialog .el-dialog__close) {
  color: rgba(255, 255, 255, 0.62);
}

:global(.category-editor-dialog .el-dialog__body) {
  padding: 6px 18px 4px;
  background: transparent;
}

:global(.category-editor-dialog .el-dialog__footer) {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 18px 18px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.08);
}

:global(.category-editor-dialog .el-form-item) {
  margin-bottom: 13px;
}

:global(.category-editor-dialog .el-form-item__label) {
  padding-bottom: 5px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 12px;
  line-height: 1.2;
}

:global(.category-editor-dialog .el-input__wrapper),
:global(.category-editor-dialog .el-select__wrapper),
:global(.category-editor-dialog .el-input-number .el-input__wrapper) {
  min-height: 36px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.065);
  box-shadow: none;
}

:global(.category-editor-dialog .el-input__wrapper:hover),
:global(.category-editor-dialog .el-select__wrapper:hover),
:global(.category-editor-dialog .el-input__wrapper.is-focus),
:global(.category-editor-dialog .el-select__wrapper.is-focused) {
  border-color: rgba(0, 255, 195, 0.28);
  background: rgba(255, 255, 255, 0.085);
  box-shadow: 0 0 0 1px rgba(0, 255, 195, 0.08);
}

:global(.category-editor-dialog .el-input__inner),
:global(.category-editor-dialog .el-select__placeholder),
:global(.category-editor-dialog .el-select__selected-item) {
  color: rgba(255, 255, 255, 0.86);
}

:global(.category-editor-dialog .el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.32);
}

:global(.category-editor-dialog .el-select__caret),
:global(.category-editor-dialog .el-input-number__decrease),
:global(.category-editor-dialog .el-input-number__increase) {
  color: rgba(255, 255, 255, 0.68);
}

:global(.category-editor-dialog .el-input-number__decrease),
:global(.category-editor-dialog .el-input-number__increase) {
  border-color: rgba(255, 255, 255, 0.09);
  background: rgba(255, 255, 255, 0.055);
}

:global(.category-editor-dialog .el-input-number__decrease:hover),
:global(.category-editor-dialog .el-input-number__increase:hover) {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.08);
}

:global(.category-editor-dialog .el-switch__label) {
  color: rgba(255, 255, 255, 0.46);
}

:global(.category-editor-dialog .el-switch__label.is-active) {
  color: #00ffc3;
}

:global(.category-editor-dialog .el-switch__core) {
  border-color: rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.12);
}

:global(.category-editor-dialog .el-switch.is-checked .el-switch__core) {
  border-color: rgba(0, 255, 195, 0.55);
  background: linear-gradient(135deg, #00ffc3, #3aa5ff);
}

:global(.category-editor-dialog .el-button) {
  min-width: 92px;
  color: rgba(255, 255, 255, 0.78);
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.07);
  box-shadow: none;
}

:global(.category-editor-dialog .el-button:hover) {
  color: #00ffc3;
  border-color: rgba(0, 255, 195, 0.28);
  background: rgba(0, 255, 195, 0.08);
}

:global(.category-editor-dialog .el-button--primary) {
  color: #071410;
  border-color: rgba(0, 255, 195, 0.56);
  background: linear-gradient(135deg, #00ffc3, #3aa5ff);
}

:global(.category-editor-dialog .el-button--primary:hover) {
  color: #071410;
  border-color: rgba(0, 255, 195, 0.72);
  background: linear-gradient(135deg, #3fffd1, #65b9ff);
}
</style>
