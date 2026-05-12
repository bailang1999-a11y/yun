<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit3, Plus, RefreshCw, Trash2, UserPlus } from 'lucide-vue-next'
import { createAdminStaff, deleteAdminStaff, fetchAdminStaff, updateAdminStaff } from '../api/staff'
import type { AdminStaff, AdminStaffPayload } from '../types/operations'
import { formatDateTime } from '../utils/formatters'

const permissionOptions = [
  { value: 'dashboard:read', label: '仪表盘 / 审计查看', description: '查看业务看板、短信日志、操作日志' },
  { value: 'goods:manage', label: '商品与货源', description: '管理商品、分类、供应商、货源对接、商品监控' },
  { value: 'orders:manage', label: '订单处理', description: '查看订单、补单、退款、重试采购' },
  { value: 'users:manage', label: '会员与开放平台', description: '管理会员、会员组、会员 API' },
  { value: 'settings:manage', label: '系统与支付设置', description: '管理站点设置、支付通道、短信登录、人机验证' },
  { value: 'staff:manage', label: '员工账号', description: '创建员工、编辑权限、停用账号' }
]

const staff = ref<AdminStaff[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingStaff = ref<AdminStaff | null>(null)

const form = reactive<AdminStaffPayload>({
  account: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  status: 'ENABLED',
  permissions: ['dashboard:read']
})

const dialogTitle = computed(() => (editingStaff.value ? '编辑员工账号' : '创建员工账号'))

onMounted(() => {
  void loadStaff()
})

async function loadStaff() {
  loading.value = true
  try {
    staff.value = await fetchAdminStaff()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '员工账号加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingStaff.value = null
  form.account = ''
  form.nickname = ''
  form.password = ''
  form.confirmPassword = ''
  form.status = 'ENABLED'
  form.permissions = ['dashboard:read']
}

function openCreateDialog() {
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row: AdminStaff) {
  editingStaff.value = row
  form.account = row.account
  form.nickname = row.nickname
  form.password = ''
  form.confirmPassword = ''
  form.status = row.status === 'DISABLED' ? 'DISABLED' : 'ENABLED'
  form.permissions = row.permissions.length ? [...row.permissions] : ['dashboard:read']
  dialogVisible.value = true
}

async function submitStaff() {
  if (!form.account.trim()) {
    ElMessage.warning('请填写员工登录账号')
    return
  }
  if (!form.nickname.trim()) {
    ElMessage.warning('请填写员工名称')
    return
  }
  if (!form.permissions.length) {
    ElMessage.warning('至少分配一个权限')
    return
  }
  if (!editingStaff.value && !form.password) {
    ElMessage.warning('创建员工时需要设置初始密码')
    return
  }
  if (form.password || form.confirmPassword) {
    if ((form.password || '').length < 6) {
      ElMessage.warning('密码至少需要 6 位')
      return
    }
    if (form.password !== form.confirmPassword) {
      ElMessage.warning('两次输入的密码不一致')
      return
    }
  }

  saving.value = true
  try {
    const payload = {
      account: form.account.trim(),
      nickname: form.nickname.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
      status: form.status,
      permissions: [...form.permissions]
    }
    const next = editingStaff.value
      ? await updateAdminStaff(editingStaff.value.id, payload)
      : await createAdminStaff(payload)
    const index = staff.value.findIndex((item) => String(item.id) === String(next.id))
    if (index >= 0) staff.value[index] = next
    else staff.value.unshift(next)
    dialogVisible.value = false
    ElMessage.success(editingStaff.value ? '员工账号已更新' : '员工账号已创建')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeStaff(row: AdminStaff) {
  try {
    await ElMessageBox.confirm(`确认删除员工账号「${row.nickname || row.account}」？删除后该账号将无法登录。`, '删除员工账号', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      customClass: 'xiyiyun-glass-dialog'
    })
    await deleteAdminStaff(row.id)
    staff.value = staff.value.filter((item) => String(item.id) !== String(row.id))
    ElMessage.success('员工账号已删除')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

function permissionLabels(values: string[]) {
  const map = new Map(permissionOptions.map((item) => [item.value, item.label]))
  return values.map((value) => map.get(value) || value)
}
</script>

<template>
  <section class="staff-shell">
    <header class="staff-hero liquid-admin-panel">
      <div>
        <span>后台安全</span>
        <h2>员工账号与权限</h2>
        <p>为运营同事创建独立后台账号，并按模块分配最小必要权限。</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="RefreshCw" @click="loadStaff">刷新</el-button>
        <el-button type="primary" :icon="UserPlus" @click="openCreateDialog">创建员工</el-button>
      </div>
    </header>

    <section class="staff-panel liquid-admin-panel">
      <el-table v-loading="loading" :data="staff" row-key="id" class="staff-table">
        <el-table-column prop="nickname" label="员工" min-width="180">
          <template #default="{ row }">
            <div class="staff-identity">
              <strong>{{ row.nickname }}</strong>
              <span>{{ row.account }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DISABLED' ? 'danger' : 'success'" effect="dark">
              {{ row.status === 'DISABLED' ? '已停用' : '启用中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限" min-width="360">
          <template #default="{ row }">
            <div class="permission-tags">
              <el-tag v-for="label in permissionLabels(row.permissions)" :key="label" effect="plain">{{ label }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit3" @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" :icon="Trash2" @click="removeStaff(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" class="xiyiyun-glass-dialog staff-dialog" destroy-on-close>
      <el-form label-position="top" class="staff-form">
        <div class="form-grid">
          <el-form-item label="登录账号">
            <el-input v-model.trim="form.account" placeholder="例如 zhangsan" autocomplete="username" />
          </el-form-item>
          <el-form-item label="员工名称">
            <el-input v-model.trim="form.nickname" placeholder="例如 张三" />
          </el-form-item>
          <el-form-item label="登录密码">
            <el-input v-model="form.password" type="password" show-password :placeholder="editingStaff ? '留空则不修改密码' : '至少 6 位'" autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input v-model="form.confirmPassword" type="password" show-password :placeholder="editingStaff ? '留空则不修改密码' : '再次输入密码'" autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="账号状态">
            <el-segmented v-model="form.status" :options="[{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }]" />
          </el-form-item>
        </div>

        <section class="permission-picker">
          <div class="permission-picker-head">
            <strong>分配权限</strong>
            <span>{{ form.permissions.length }} / {{ permissionOptions.length }}</span>
          </div>
          <el-checkbox-group v-model="form.permissions" class="permission-grid">
            <label v-for="item in permissionOptions" :key="item.value" class="permission-card">
              <el-checkbox :value="item.value">
                <strong>{{ item.label }}</strong>
                <small>{{ item.description }}</small>
              </el-checkbox>
            </label>
          </el-checkbox-group>
        </section>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Plus" :loading="saving" @click="submitStaff">保存员工</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.staff-shell {
  display: grid;
  gap: 18px;
}

.staff-hero,
.staff-panel {
  border-radius: 24px;
}

.staff-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px;
}

.staff-hero span,
.staff-hero p,
.staff-identity span {
  color: rgba(226, 232, 240, 0.68);
}

.staff-hero h2 {
  margin: 6px 0 8px;
  color: #f8fafc;
}

.staff-hero p {
  margin: 0;
}

.hero-actions {
  display: flex;
  gap: 10px;
}

.staff-panel {
  padding: 14px;
}

.staff-identity {
  display: grid;
  gap: 5px;
}

.staff-identity strong {
  color: #f8fafc;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.staff-form {
  display: grid;
  gap: 18px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}

.permission-picker {
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 18px;
  padding: 14px;
  background: rgba(15, 23, 42, 0.36);
}

.permission-picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #f8fafc;
}

.permission-picker-head span {
  color: rgba(226, 232, 240, 0.62);
  font-size: 12px;
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.permission-card {
  display: block;
  min-height: 82px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 16px;
  padding: 12px;
  background: rgba(15, 23, 42, 0.32);
}

.permission-card :deep(.el-checkbox) {
  align-items: flex-start;
  height: auto;
  width: 100%;
  white-space: normal;
}

.permission-card :deep(.el-checkbox__label) {
  display: grid;
  gap: 5px;
  color: #f8fafc;
  line-height: 1.35;
}

.permission-card small {
  color: rgba(226, 232, 240, 0.62);
}

:global(.staff-dialog .el-dialog__body) {
  padding-top: 6px;
}
</style>
