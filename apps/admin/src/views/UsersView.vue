<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, RefreshCw, Save } from 'lucide-vue-next'
import {
  createUserGroup,
  fetchCategories,
  fetchUserGroups,
  fetchUsers,
  updateGroupRules,
  updateUserGroup
} from '../api/admin'
import type { Category, RulePermission, RuleType, UserAccount, UserGroup, UserGroupCreatePayload } from '../types/operations'

const platformOptions = [
  { label: '移动 H5', value: 'h5' },
  { label: 'PC 网页', value: 'pc' },
  { label: '微信小程序', value: 'miniapp' }
]

const groups = ref<UserGroup[]>([])
const users = ref<UserAccount[]>([])
const categories = ref<Category[]>([])
const selectedGroupId = ref<string>('')
const loading = ref(false)
const groupDialogVisible = ref(false)
const savingType = ref<RuleType | ''>('')
const categoryRules = reactive<Record<string, RulePermission>>({})
const platformRules = reactive<Record<string, RulePermission>>({})
const groupForm = reactive<UserGroupCreatePayload>({
  name: '',
  description: '',
  defaultGroup: false,
  status: 'ENABLED'
})

const selectedGroup = computed(() => groups.value.find((item) => String(item.id) === selectedGroupId.value))
const categoryOptions = computed(() => flattenCategoryTree(buildCategoryTree(categories.value)))

onMounted(() => {
  void loadAll()
})

async function loadAll() {
  loading.value = true
  try {
    const [nextGroups, nextUsers, nextCategories] = await Promise.all([
      fetchUserGroups(),
      fetchUsers(),
      fetchCategories()
    ])
    groups.value = nextGroups
    users.value = nextUsers
    categories.value = nextCategories
    selectedGroupId.value ||= String(nextGroups[0]?.id || '')
    hydrateRules()
  } catch {
    ElMessage.error('用户权限数据加载失败')
  } finally {
    loading.value = false
  }
}

function buildCategoryTree(items: Category[]) {
  const map = new Map<string, Category>()
  const roots: Category[] = []
  items.forEach((item) => map.set(String(item.id), { ...item, children: [] }))
  map.forEach((item) => {
    const parent = item.parentId ? map.get(String(item.parentId)) : undefined
    item.level = parent ? (parent.level || 1) + 1 : 1
    if (parent) parent.children?.push(item)
    else roots.push(item)
  })
  return roots
}

function flattenCategoryTree(nodes: Category[], result: Category[] = []) {
  nodes
    .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0))
    .forEach((node) => {
      result.push(node)
      flattenCategoryTree(node.children || [], result)
    })
  return result
}

function hydrateRules() {
  Object.keys(categoryRules).forEach((key) => delete categoryRules[key])
  Object.keys(platformRules).forEach((key) => delete platformRules[key])
  categoryOptions.value.forEach((item) => {
    categoryRules[String(item.id)] = 'NONE'
  })
  platformOptions.forEach((item) => {
    platformRules[item.value] = 'NONE'
  })
  selectedGroup.value?.rules.forEach((rule) => {
    if (rule.ruleType === 'CATEGORY' && rule.targetId) categoryRules[String(rule.targetId)] = rule.permission
    if (rule.ruleType === 'PLATFORM' && rule.targetCode) platformRules[rule.targetCode] = rule.permission
  })
}

function selectGroup(group: UserGroup) {
  selectedGroupId.value = String(group.id)
  hydrateRules()
}

function setRule(target: Record<string, RulePermission>, key: string, permission: RulePermission) {
  target[key] = permission
}

function permissionClass(permission: RulePermission, active: RulePermission) {
  return { active: permission === active, allow: permission === 'ALLOW', deny: permission === 'DENY' }
}

async function saveRules(ruleType: RuleType) {
  if (!selectedGroup.value) return
  savingType.value = ruleType
  try {
    const rules =
      ruleType === 'CATEGORY'
        ? categoryOptions.value.map((item) => ({ targetId: item.id, permission: categoryRules[String(item.id)] || 'NONE' }))
        : platformOptions.map((item) => ({ targetCode: item.value, permission: platformRules[item.value] || 'NONE' }))
    const nextRules = await updateGroupRules(selectedGroup.value.id, { ruleType, rules })
    const index = groups.value.findIndex((item) => String(item.id) === selectedGroupId.value)
    if (index >= 0) groups.value[index] = { ...groups.value[index], rules: nextRules }
    hydrateRules()
    ElMessage.success('规则已保存')
  } catch {
    ElMessage.error('规则保存失败')
  } finally {
    savingType.value = ''
  }
}

async function changeUserGroup(row: UserAccount) {
  if (!row.groupId) return
  try {
    const next = await updateUserGroup(row.id, row.groupId)
    Object.assign(row, next)
    groups.value = await fetchUserGroups()
    ElMessage.success('用户分组已更新')
  } catch {
    ElMessage.error('用户分组更新失败')
  }
}

async function submitGroup() {
  if (!groupForm.name.trim()) {
    ElMessage.warning('请填写会员等级名称')
    return
  }
  try {
    const next = await createUserGroup({
      ...groupForm,
      name: groupForm.name.trim(),
      description: groupForm.description?.trim()
    })
    groups.value.push(next)
    selectedGroupId.value = String(next.id)
    hydrateRules()
    groupDialogVisible.value = false
    groupForm.name = ''
    groupForm.description = ''
    groupForm.defaultGroup = false
    groupForm.status = 'ENABLED'
    ElMessage.success('会员等级已新增')
  } catch {
    ElMessage.error('新增会员等级失败')
  }
}
</script>

<template>
  <section class="permissions-grid">
    <article class="panel group-panel">
      <div class="panel-head">
        <div>
          <h2>用户组</h2>
          <span>分类与平台可见性</span>
        </div>
        <div class="group-actions">
          <el-button type="primary" :icon="Plus" @click="groupDialogVisible = true">新增会员等级</el-button>
          <el-button :icon="RefreshCw" :loading="loading" aria-label="刷新权限数据" @click="loadAll" />
        </div>
      </div>

      <button
        v-for="group in groups"
        :key="group.id"
        type="button"
        class="group-row"
        :class="{ active: String(group.id) === selectedGroupId }"
        @click="selectGroup(group)"
      >
        <strong>{{ group.name }}</strong>
        <span>{{ group.description }}</span>
        <em>{{ group.userCount || 0 }} 人</em>
      </button>
    </article>

    <article class="panel rules-panel">
      <div class="panel-head">
        <div>
          <h2>{{ selectedGroup?.name || '权限规则' }}</h2>
          <span>有允许规则时，商品必须命中允许且不命中禁止</span>
        </div>
      </div>

      <div class="rule-section">
        <div class="section-head">
          <h3>按销售平台限制</h3>
          <el-button type="primary" :icon="Save" :loading="savingType === 'PLATFORM'" @click="saveRules('PLATFORM')">
            保存平台规则
          </el-button>
        </div>
        <div class="rule-list compact">
          <div v-for="platform in platformOptions" :key="platform.value" class="rule-row">
            <span>{{ platform.label }}</span>
            <div class="rule-actions" role="group" :aria-label="`${platform.label} 权限`">
              <button type="button" :class="permissionClass('ALLOW', platformRules[platform.value])" @click="setRule(platformRules, platform.value, 'ALLOW')">允许</button>
              <button type="button" :class="permissionClass('DENY', platformRules[platform.value])" @click="setRule(platformRules, platform.value, 'DENY')">禁止</button>
              <button type="button" :class="permissionClass('NONE', platformRules[platform.value])" @click="setRule(platformRules, platform.value, 'NONE')">无规则</button>
            </div>
          </div>
        </div>
      </div>

      <div class="rule-section">
        <div class="section-head">
          <h3>按分类限制</h3>
          <el-button type="primary" :icon="Save" :loading="savingType === 'CATEGORY'" @click="saveRules('CATEGORY')">
            保存分类规则
          </el-button>
        </div>
        <div class="rule-list">
          <div v-for="category in categoryOptions" :key="category.id" class="rule-row">
            <span :style="{ paddingLeft: `${Math.max((category.level || 1) - 1, 0) * 14}px` }">{{ category.name }}</span>
            <div class="rule-actions" role="group" :aria-label="`${category.name} 权限`">
              <button type="button" :class="permissionClass('ALLOW', categoryRules[String(category.id)])" @click="setRule(categoryRules, String(category.id), 'ALLOW')">允许</button>
              <button type="button" :class="permissionClass('DENY', categoryRules[String(category.id)])" @click="setRule(categoryRules, String(category.id), 'DENY')">禁止</button>
              <button type="button" :class="permissionClass('NONE', categoryRules[String(category.id)])" @click="setRule(categoryRules, String(category.id), 'NONE')">无规则</button>
            </div>
          </div>
        </div>
      </div>
    </article>

    <article class="panel users-panel">
      <div class="panel-head">
        <div>
          <h2>用户列表</h2>
          <span>将用户分配到对应用户组</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="users" height="360" style="width: 100%">
        <el-table-column prop="nickname" label="用户" min-width="130" />
        <el-table-column prop="mobile" label="手机号" min-width="140" />
        <el-table-column label="用户组" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.groupId" aria-label="选择用户组" @change="changeUserGroup(row)">
              <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column prop="balance" label="余额" width="100" />
        <el-table-column prop="status" label="状态" width="110" />
      </el-table>
    </article>
  </section>

  <el-dialog v-model="groupDialogVisible" title="新增会员等级" width="520px">
    <el-form :model="groupForm" label-position="top">
      <el-form-item label="会员等级名称">
        <el-input v-model="groupForm.name" placeholder="例如：高级会员" />
      </el-form-item>
      <el-form-item label="等级说明">
        <el-input v-model="groupForm.description" type="textarea" :rows="3" placeholder="可自定义该等级说明" />
      </el-form-item>
      <el-form-item label="启用状态">
        <el-select v-model="groupForm.status">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="groupDialogVisible = false">取消</el-button>
      <el-button type="primary" :icon="Plus" @click="submitGroup">新增等级</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.permissions-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
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

.panel-head,
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

h2,
h3 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
}

h2 {
  font-size: 18px;
}

h3 {
  font-size: 15px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 13px;
}

.group-actions {
  display: flex;
  gap: 8px;
}

.group-row {
  width: 100%;
  display: grid;
  gap: 4px;
  margin-top: 10px;
  padding: 14px;
  text-align: left;
  border-radius: 16px;
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.04);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.group-row.active {
  color: #fff;
  border-color: rgba(0, 255, 195, 0.26);
  box-shadow: 0 0 30px rgba(0, 255, 195, 0.12);
}

.group-row span,
.group-row em {
  color: rgba(255, 255, 255, 0.48);
  font-style: normal;
  font-size: 12px;
}

.rules-panel {
  min-width: 0;
}

.users-panel {
  grid-column: 1 / -1;
}

.rule-section + .rule-section {
  margin-top: 18px;
}

.rule-list {
  max-height: 420px;
  overflow: auto;
}

.rule-list.compact {
  max-height: none;
}

.rule-row {
  min-height: 42px;
  display: grid;
  grid-template-columns: minmax(160px, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.07);
  color: rgba(255, 255, 255, 0.78);
}

.rule-actions {
  display: inline-flex;
  gap: 6px;
}

.rule-actions button {
  height: 30px;
  padding: 0 10px;
  color: rgba(255, 255, 255, 0.6);
  border-radius: 999px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.04);
}

.rule-actions button.active.allow {
  color: #04100d;
  background: #00ffc3;
}

.rule-actions button.active.deny {
  color: #fff;
  background: rgba(255, 59, 48, 0.72);
  box-shadow: 0 0 22px rgba(255, 59, 48, 0.18);
}

.rule-actions button.active:not(.allow):not(.deny) {
  color: rgba(255, 255, 255, 0.86);
  background: rgba(255, 255, 255, 0.12);
}

.rule-actions button:active,
.group-row:active {
  transform: scale(0.98);
}
</style>
