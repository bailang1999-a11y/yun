<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { GitBranch, Plus, RefreshCw } from 'lucide-vue-next'
import { createCategory, fetchCategories, setCategoryEnabled } from '../api/admin'
import type { Category, CategoryCreatePayload } from '../types/operations'

const categories = ref<Category[]>([])
const loading = ref(false)
const saving = ref(false)
const operatingId = ref('')

const form = reactive<CategoryCreatePayload>({
  parentId: undefined,
  name: '',
  sort: 10,
  enabled: true
})

const categoryTree = computed(() => buildTree(categories.value))
const parentOptions = computed(() => categories.value.filter((item) => (item.level || 1) < 5))

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
  } catch {
    ElMessage.error('分类列表加载失败')
  } finally {
    loading.value = false
  }
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
  <section class="category-grid">
    <article class="create-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>新增分类</h2>
          <span>最多五级，商品建议挂到叶子节点</span>
        </div>
        <GitBranch :size="20" />
      </div>

      <el-form :model="form" label-position="top" class="category-form">
        <el-form-item label="父级分类">
          <el-select v-model="form.parentId" clearable placeholder="不选则创建一级分类">
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
        <el-button type="primary" :icon="Plus" :loading="saving" @click="submitCategory">创建分类</el-button>
      </el-form>
    </article>

    <article class="tree-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>五级分类树</h2>
          <span>{{ categories.length }} 个节点</span>
        </div>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadCategories">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="categoryTree"
        row-key="id"
        height="640"
        default-expand-all
        style="width: 100%"
      >
        <el-table-column prop="name" label="分类名称" min-width="240" />
        <el-table-column label="层级" width="100">
          <template #default="{ row }">第 {{ row.level || 1 }} 级</template>
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
.category-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 14px;
}

.create-panel,
.tree-panel {
  padding: 18px;
  overflow: hidden;
  border-radius: 22px;
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

.panel-head svg {
  color: #00ffc3;
  filter: drop-shadow(0 0 18px rgba(0, 255, 195, 0.35));
}

.category-form :deep(.el-input-number),
.category-form :deep(.el-select) {
  width: 100%;
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
