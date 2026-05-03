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
  ImagePlus,
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
import { createCategory, deleteCategory, fetchCategories, updateCategory } from '../api/catalog'
import type { Category, CategoryCreatePayload, CategoryUpdatePayload } from '../types/operations'
import { buildCategoryTree, flattenCategoryTree } from '../utils/categoryTree'

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
const selectedSecondId = ref<string>('')
const selectedCategoryId = ref<string>(props.modelValue == null ? '' : String(props.modelValue))
const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingCategoryId = ref<Category['id']>()
const iconFileInput = ref<HTMLInputElement>()
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  row: undefined as Category | undefined
})

const form = reactive<CategoryCreatePayload & { icon: string; iconUrl: string; customIconUrl: string; nickname: string }>({
  parentId: undefined,
  name: '',
  nickname: '',
  icon: 'badge',
  iconUrl: '',
  customIconUrl: '',
  sort: 10,
  enabled: true
})

const categoryTree = computed(() => buildCategoryTree(categories.value))
const parentOptions = computed(() =>
  categories.value.filter((item) => {
    if ((item.level || 1) >= 5) return false
    if (editorMode.value !== 'edit' || editingCategoryId.value === undefined) return true
    const blockedIds = new Set([
      String(editingCategoryId.value),
      ...flattenCategoryTree(findInTree(categoryTree.value, editingCategoryId.value)?.children || []).map((child) => String(child.id))
    ])
    return !blockedIds.has(String(item.id))
  })
)
const rootCategories = computed(() => categoryTree.value)
const selectedRoot = computed(() => rootCategories.value.find((item) => String(item.id) === selectedRootId.value) || rootCategories.value[0])
const secondLevelCategories = computed(() => selectedRoot.value?.children || [])
const selectedSecond = computed(() => {
  const explicit = secondLevelCategories.value.find((item) => String(item.id) === selectedSecondId.value)
  if (explicit) return explicit
  const selected = selectedCategory.value
  if (selected && selectedRoot.value) {
    return secondLevelCategories.value.find((item) => String(item.id) === String(selected.id) || findInTree(item.children || [], selected.id))
  }
  return secondLevelCategories.value[0]
})
const thirdLevelCategories = computed(() => selectedSecond.value?.children || [])
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

async function loadCategories() {
  loading.value = true
  try {
    const tree = buildCategoryTree(await fetchCategories())
    categories.value = flattenCategoryTree(tree)
    syncRootToSelection()
    selectedRootId.value ||= String(tree[0]?.id || '')
    selectedSecondId.value ||= String(selectedRoot.value?.children?.[0]?.id || '')
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
  selectedSecondId.value = String(row.children?.[0]?.id || '')
  selectCategory(row)
}

function selectChild(row: Category) {
  form.parentId = row.id
  if ((row.level || 1) === 2) selectedSecondId.value = String(row.id)
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
  const selectedItem = selectedCategory.value
  const selectedSecondItem = findSecondForId(categoryTree.value, selectedCategoryId.value)
  if (selectedSecondItem) selectedSecondId.value = String(selectedSecondItem.id)
  else if (selectedItem && (selectedItem.level || 1) === 1) selectedSecondId.value = String(selectedItem.children?.[0]?.id || '')
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

function categoryIconUrl(row: Category) {
  const candidates = [row.customIconUrl, row.iconUrl, row.icon]
  return candidates.find((value) => Boolean(value && isImageUrl(value))) || ''
}

function isImageUrl(value?: string) {
  return Boolean(value && /^(data:image\/|https?:\/\/|\/)/i.test(value))
}

function triggerIconUpload() {
  iconFileInput.value?.click()
}

function clearCustomIcon() {
  form.iconUrl = ''
  form.customIconUrl = ''
  if (iconFileInput.value) iconFileInput.value.value = ''
}

function handleIconFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  if (!/^image\/(jpeg|png)$/.test(file.type)) {
    ElMessage.warning('仅支持 JPG、JPEG、PNG 图片')
    input.value = ''
    return
  }

  const reader = new FileReader()
  reader.onload = () => {
    const dataUrl = typeof reader.result === 'string' ? reader.result : ''
    if (!dataUrl) {
      ElMessage.error('图标读取失败')
      input.value = ''
      return
    }

    const image = new Image()
    image.onload = () => {
      if (image.naturalWidth !== image.naturalHeight) {
        ElMessage.warning('请上传 1:1 的正方形图片')
        input.value = ''
        return
      }
      form.iconUrl = dataUrl
      form.customIconUrl = dataUrl
      ElMessage.success('图标已上传')
    }
    image.onerror = () => {
      ElMessage.error('图标解析失败')
      input.value = ''
    }
    image.src = dataUrl
  }
  reader.onerror = () => {
    ElMessage.error('图标读取失败')
    input.value = ''
  }
  reader.readAsDataURL(file)
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

function findSecondForId(nodes: Category[], id?: Category['id'] | string): Category | undefined {
  if (id === undefined || id === '') return undefined
  const root = findRootForId(nodes, id)
  if (!root) return undefined
  for (const child of root.children || []) {
    if (String(child.id) === String(id)) return child
    if (findInTree(child.children || [], id)) return child
  }
  return undefined
}

function resetForm() {
  form.parentId = undefined
  form.name = ''
  form.nickname = ''
  form.icon = 'badge'
  form.iconUrl = ''
  form.customIconUrl = ''
  form.sort = 10
  form.enabled = true
  if (iconFileInput.value) iconFileInput.value.value = ''
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
  form.icon = isImageUrl(row.icon) ? row.iconKey || 'badge' : row.icon || row.iconKey || 'badge'
  form.iconUrl = categoryIconUrl(row)
  form.customIconUrl = categoryIconUrl(row)
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
      iconUrl: form.iconUrl || undefined,
      customIconUrl: form.customIconUrl || form.iconUrl || undefined,
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
      type: 'warning',
      customClass: 'xiyiyun-glass-message-box'
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
      <div class="catalog-content">
        <div class="panel-head">
          <div>
            <h2>分类管理</h2>
            <span class="category-breadcrumb">
              目录 / 分类
              <em>{{ categories.length }} 个节点</em>
              <em>当前 {{ selectedCategory?.name || selectedRoot?.name || '未选择' }}</em>
              <em>{{ depthLabel(selectedCategory || selectedRoot) }}</em>
            </span>
          </div>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadCategories">刷新</el-button>
        </div>

        <nav v-if="rootCategories.length" class="root-tabs" aria-label="一级分类">
          <button
            v-for="item in rootCategories"
            :key="item.id"
            type="button"
            :class="{ active: String(item.id) === selectedRootId }"
            @click="selectRoot(item)"
          >
            {{ item.name }}
          </button>
          <button type="button" class="add-root-tab" @click="openCreate()">+ 一级分类</button>
        </nav>

        <section class="category-level" aria-label="二级分类">
          <div class="level-title">
            <strong>二级分类</strong>
            <span>{{ selectedRoot ? `归属 ${selectedRoot.name}` : '请选择一级分类' }}</span>
          </div>
          <div class="category-strip">
            <button
              v-for="item in secondLevelCategories"
              :key="item.id"
              type="button"
              class="category-icon-card"
              :class="{ active: String(item.id) === selectedSecondId || String(item.id) === selectedCategoryId }"
              @click="selectChild(item)"
              @contextmenu.prevent="openContextMenu($event, item)"
            >
              <span class="icon-bubble" :class="{ 'has-image': categoryIconUrl(item) }">
                <img v-if="categoryIconUrl(item)" :src="categoryIconUrl(item)" :alt="`${item.name}图标`" />
                <component v-else :is="iconForCategory(item)" :size="28" />
              </span>
              <strong>{{ item.name }}</strong>
              <em>{{ item.children?.length || 0 }} 个子类</em>
              <span class="card-more" aria-hidden="true"><MoreVertical :size="15" /></span>
            </button>
            <button type="button" class="category-icon-card add-card" @click="openCreate(selectedRoot?.id)">
              <span class="icon-bubble"><Plus :size="30" /></span>
              <strong>新增分类</strong>
              <em>{{ selectedRoot ? `挂到 ${selectedRoot.name}` : '二级分类' }}</em>
            </button>
          </div>
        </section>

        <section class="category-level" aria-label="三级分类">
          <div class="level-title">
            <strong>三级分类</strong>
            <span>{{ selectedSecond ? `归属 ${selectedSecond.name}` : '请选择二级分类' }}</span>
          </div>
          <div class="category-matrix">
            <button
              v-for="item in thirdLevelCategories"
              :key="item.id"
              type="button"
              class="category-icon-card"
              :class="{ active: String(item.id) === selectedCategoryId }"
              @click="selectChild(item)"
              @contextmenu.prevent="openContextMenu($event, item)"
            >
              <span class="icon-bubble" :class="{ 'has-image': categoryIconUrl(item) }">
                <img v-if="categoryIconUrl(item)" :src="categoryIconUrl(item)" :alt="`${item.name}图标`" />
                <component v-else :is="iconForCategory(item)" :size="26" />
              </span>
              <strong>{{ item.name }}</strong>
              <em>排序 {{ item.sort }}</em>
              <small :data-enabled="item.enabled">{{ item.enabled ? '启用' : '停用' }}</small>
              <span class="card-more" aria-hidden="true"><MoreVertical :size="15" /></span>
            </button>
            <button type="button" class="category-icon-card add-card" @click="openCreate(selectedSecond?.id || selectedRoot?.id)">
              <span class="icon-bubble"><FolderPlus :size="28" /></span>
              <strong>新增分类</strong>
              <em>{{ selectedSecond ? `挂到 ${selectedSecond.name}` : '选择二级分类' }}</em>
            </button>
          </div>
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

      <el-dialog v-model="editorVisible" :title="editorTitle" width="540px" destroy-on-close class="xiyiyun-glass-dialog category-editor-dialog" @closed="resetForm">
        <el-form :model="form" label-position="top" class="category-form dialog-form">
          <el-form-item label="图标">
            <div class="custom-icon-uploader">
              <span class="icon-bubble preview" :class="{ 'has-image': form.iconUrl }">
                <img v-if="form.iconUrl" :src="form.iconUrl" alt="自定义分类图标预览" />
                <component v-else :is="iconMap.get(form.icon) || BadgeIcon" :size="24" />
              </span>
              <div class="custom-icon-actions">
                <input
                  ref="iconFileInput"
                  type="file"
                  accept="image/png,image/jpeg"
                  class="sr-only-input"
                  @change="handleIconFileChange"
                />
                <el-button :icon="ImagePlus" @click="triggerIconUpload">上传图片</el-button>
                <el-button v-if="form.iconUrl" :icon="Trash2" @click="clearCustomIcon">移除图片</el-button>
              </div>
              <span class="custom-icon-hint">支持 JPG、JPEG、PNG，图片比例需为 1:1</span>
            </div>
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
  align-self: start;
  height: auto;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.03)),
    rgba(255, 255, 255, 0.04);
}

.catalog-content {
  min-width: 0;
  padding: 20px 24px 26px;
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

.category-breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.category-breadcrumb em {
  padding-left: 8px;
  color: rgba(255, 255, 255, 0.48);
  font-style: normal;
  border-left: 0.5px solid rgba(255, 255, 255, 0.14);
}

.root-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: -2px 0 14px;
}

.root-tabs button {
  height: 30px;
  padding: 0 14px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.58);
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  cursor: pointer;
  transition: transform 160ms ease, border-color 160ms ease, background 160ms ease, color 160ms ease;
}

.root-tabs button:hover {
  transform: translateY(-1px);
  border-color: rgba(0, 255, 195, 0.24);
}

.root-tabs button.active {
  color: #06110f;
  background: #00ffc3;
  border-color: rgba(0, 255, 195, 0.8);
  box-shadow: 0 8px 22px rgba(0, 255, 195, 0.12);
}

.root-tabs .add-root-tab {
  color: rgba(205, 255, 246, 0.78);
  border-style: dashed;
  background: rgba(0, 255, 195, 0.06);
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

.category-level {
  min-width: 0;
}

.category-level + .category-level {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.08);
}

.level-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-height: 24px;
  margin-bottom: 8px;
}

.level-title strong {
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
  font-weight: 700;
}

.level-title span {
  color: rgba(255, 255, 255, 0.42);
  font-size: 12px;
}

.category-strip {
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  align-items: start;
  padding: 4px 0 2px;
}

.category-matrix {
  grid-template-columns: repeat(auto-fill, minmax(106px, 1fr));
  align-items: start;
  padding-top: 4px;
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
  overflow: hidden;
  border-radius: 18px;
  color: #071410;
  background:
    radial-gradient(circle at 30% 18%, rgba(255, 255, 255, 0.72), transparent 24%),
    linear-gradient(135deg, #00ffc3, #3aa5ff);
  box-shadow: 0 12px 26px rgba(0, 255, 195, 0.13);
}

.icon-bubble.has-image {
  color: transparent;
  background: rgba(255, 255, 255, 0.08);
}

.icon-bubble img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
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

.custom-icon-uploader {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 8px 12px;
  align-items: center;
  width: 100%;
  margin-bottom: 10px;
}

.icon-bubble.preview {
  width: 58px;
  height: 58px;
}

.custom-icon-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.custom-icon-hint {
  grid-column: 2;
  color: rgba(255, 255, 255, 0.42);
  font-size: 12px;
  line-height: 1.3;
}

.sr-only-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
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
