<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Banknote, KeyRound, Plus, RefreshCw, Save, UserCog } from 'lucide-vue-next'
import UserRealNameCell from '../components/UserRealNameCell.vue'
import UserVerificationTag from '../components/UserVerificationTag.vue'
import {
  adjustUserFunds,
  createUserGroup,
  fetchUserMemberApiCredential,
  fetchUserGroups,
  fetchUsers,
  saveUserMemberApiCredential,
  updateGroupRules,
  updateUserGroupOrderPermission,
  updateUserCredentials,
  updateUserGroup
} from '../api/users'
import type { MemberApiCredential, RulePermission, RuleType, UserAccount, UserGroup, UserGroupCreatePayload } from '../types/operations'
import {
  formatDateTime,
  formatMoney as formatAmount,
  formatUserStatus,
  maskCertificate,
  userStatusTagType
} from '../utils/formatters'

const DEFAULT_PRICE_LIMIT_NOTICE = '当前会员组暂未开放限价商品购买权限，请联系平台客服处理。'

const platformOptions = [
  { label: '抖音', value: 'douyin' },
  { label: '淘宝', value: 'taobao' },
  { label: '拼多多', value: 'pdd' },
  { label: '咸鱼', value: 'xianyu' },
  { label: '小红书', value: 'xiaohongshu' },
  { label: '私域', value: 'private' }
]

const groups = ref<UserGroup[]>([])
const users = ref<UserAccount[]>([])
const selectedGroupId = ref<string>('')
const loading = ref(false)
const groupDialogVisible = ref(false)
const fundDialogVisible = ref(false)
const apiDialogVisible = ref(false)
const credentialDialogVisible = ref(false)
const savingType = ref<RuleType | ''>('')
const orderPermissionSaving = ref(false)
const fundSaving = ref(false)
const apiLoading = ref(false)
const apiSaving = ref(false)
const credentialSaving = ref(false)
const selectedUser = ref<UserAccount>()
const apiCredential = ref<MemberApiCredential>()
const platformRules = reactive<Record<string, RulePermission>>({})
const groupForm = reactive<UserGroupCreatePayload>({
  name: '',
  description: '',
  defaultGroup: false,
  status: 'ENABLED',
  orderEnabled: true,
  realNameRequiredForOrder: false,
  priceLimitEnabled: true,
  priceLimitNotice: DEFAULT_PRICE_LIMIT_NOTICE
})
const fundForm = reactive({
  accountType: 'balance' as 'balance' | 'deposit',
  direction: 'increase' as 'increase' | 'decrease',
  amount: 0,
  remark: ''
})
const apiForm = reactive({
  enabled: false,
  appKey: '',
  appSecret: '',
  resetSecret: false,
  ipWhitelistText: '',
  dailyLimit: 1000
})
const credentialForm = reactive({
  account: '',
  nickname: '',
  newPassword: '',
  confirmPassword: ''
})

const selectedGroup = computed(() => groups.value.find((item) => String(item.id) === selectedGroupId.value))

onMounted(() => {
  void loadAll()
})

async function loadAll() {
  loading.value = true
  try {
    const [nextGroups, nextUsers] = await Promise.all([
      fetchUserGroups(),
      fetchUsers()
    ])
    groups.value = nextGroups
    users.value = nextUsers
    selectedGroupId.value ||= String(nextGroups[0]?.id || '')
    hydrateRules()
  } catch {
    ElMessage.error('用户权限数据加载失败')
  } finally {
    loading.value = false
  }
}

function hydrateRules() {
  Object.keys(platformRules).forEach((key) => delete platformRules[key])
  platformOptions.forEach((item) => {
    platformRules[item.value] = 'NONE'
  })
  selectedGroup.value?.rules.forEach((rule) => {
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

async function saveOrderPermission() {
  if (!selectedGroup.value) return
  orderPermissionSaving.value = true
  try {
    const next = await updateUserGroupOrderPermission(selectedGroup.value.id, {
      orderEnabled: selectedGroup.value.orderEnabled !== false,
      realNameRequiredForOrder: selectedGroup.value.realNameRequiredForOrder === true,
      priceLimitEnabled: selectedGroup.value.priceLimitEnabled !== false,
      priceLimitNotice: selectedGroup.value.priceLimitNotice?.trim() || DEFAULT_PRICE_LIMIT_NOTICE
    })
    const index = groups.value.findIndex((item) => String(item.id) === String(next.id))
    if (index >= 0) groups.value[index] = next
    ElMessage.success('下单权限已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下单权限保存失败')
  } finally {
    orderPermissionSaving.value = false
  }
}

async function saveRules(ruleType: RuleType) {
  if (!selectedGroup.value) return
  savingType.value = ruleType
  try {
    const rules = platformOptions.map((item) => ({ targetCode: item.value, permission: platformRules[item.value] || 'NONE' }))
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

function openFundDialog(row: UserAccount, accountType: 'balance' | 'deposit' = 'balance') {
  selectedUser.value = row
  fundForm.accountType = accountType
  fundForm.direction = 'increase'
  fundForm.amount = 0
  fundForm.remark = ''
  fundDialogVisible.value = true
}

function openCredentialDialog(row: UserAccount) {
  selectedUser.value = row
  credentialForm.account = row.mobile || row.email || ''
  credentialForm.nickname = row.nickname || ''
  credentialForm.newPassword = ''
  credentialForm.confirmPassword = ''
  credentialDialogVisible.value = true
}

async function submitCredentialUpdate() {
  if (!selectedUser.value) return
  if (!credentialForm.account.trim()) {
    ElMessage.warning('请填写用户登录账号')
    return
  }
  if (credentialForm.newPassword || credentialForm.confirmPassword) {
    if (credentialForm.newPassword.length < 6) {
      ElMessage.warning('新密码至少需要 6 位')
      return
    }
    if (credentialForm.newPassword !== credentialForm.confirmPassword) {
      ElMessage.warning('两次输入的新密码不一致')
      return
    }
  }
  credentialSaving.value = true
  try {
    const next = await updateUserCredentials(selectedUser.value.id, {
      account: credentialForm.account.trim(),
      nickname: credentialForm.nickname.trim(),
      newPassword: credentialForm.newPassword,
      confirmPassword: credentialForm.confirmPassword
    })
    Object.assign(selectedUser.value, next)
    credentialDialogVisible.value = false
    ElMessage.success(credentialForm.newPassword ? '用户账号与密码已更新' : '用户账号已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '用户账号密码更新失败')
  } finally {
    credentialSaving.value = false
  }
}

async function submitFundAdjust() {
  if (!selectedUser.value) return
  if (Number(fundForm.amount) <= 0) {
    ElMessage.warning('请输入大于 0 的调整金额')
    return
  }
  fundSaving.value = true
  try {
    const next = await adjustUserFunds(selectedUser.value.id, {
      accountType: fundForm.accountType,
      direction: fundForm.direction,
      amount: Number(fundForm.amount),
      remark: fundForm.remark.trim()
    })
    Object.assign(selectedUser.value, next)
    fundDialogVisible.value = false
    ElMessage.success('资金已调整')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资金调整失败')
  } finally {
    fundSaving.value = false
  }
}

async function openApiDialog(row: UserAccount) {
  selectedUser.value = row
  apiDialogVisible.value = true
  apiLoading.value = true
  try {
    const credential = await fetchUserMemberApiCredential(row.id)
    apiCredential.value = credential
    apiForm.enabled = credential.status === 'ENABLED'
    apiForm.appKey = credential.appKey
    apiForm.appSecret = credential.appSecret
    apiForm.resetSecret = false
    apiForm.ipWhitelistText = credential.ipWhitelist.join('\n')
    apiForm.dailyLimit = credential.dailyLimit || 1000
  } catch {
    ElMessage.error('会员 API 配置加载失败')
  } finally {
    apiLoading.value = false
  }
}

async function submitApiConfig(resetSecret = false) {
  if (!selectedUser.value) return
  if (!apiForm.appKey.trim()) {
    ElMessage.warning('请填写用户 Key')
    return
  }
  apiSaving.value = true
  try {
    const credential = await saveUserMemberApiCredential(selectedUser.value.id, {
      enabled: apiForm.enabled,
      appKey: apiForm.appKey.trim(),
      appSecret: apiForm.appSecret.trim(),
      resetSecret,
      ipWhitelist: splitLines(apiForm.ipWhitelistText),
      dailyLimit: Number(apiForm.dailyLimit) || 1000
    })
    apiCredential.value = credential
    apiForm.enabled = credential.status === 'ENABLED'
    apiForm.appKey = credential.appKey
    apiForm.appSecret = credential.appSecret
    apiForm.ipWhitelistText = credential.ipWhitelist.join('\n')
    apiForm.dailyLimit = credential.dailyLimit
    ElMessage.success(resetSecret ? '用户 Secret 已重置' : '会员 API 配置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '会员 API 配置保存失败')
  } finally {
    apiSaving.value = false
  }
}

function splitLines(value: string) {
  return value
    .split(/[\n,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

async function copyText(value: string, label: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败')
  }
}

async function submitGroup() {
  if (!groupForm.name.trim()) {
    ElMessage.warning('请填写会员分组名称')
    return
  }
  try {
    const next = await createUserGroup({
      ...groupForm,
      name: groupForm.name.trim(),
      description: groupForm.description?.trim(),
      priceLimitNotice: groupForm.priceLimitNotice?.trim() || DEFAULT_PRICE_LIMIT_NOTICE
    })
    groups.value.push(next)
    selectedGroupId.value = String(next.id)
    hydrateRules()
    groupDialogVisible.value = false
    groupForm.name = ''
    groupForm.description = ''
    groupForm.defaultGroup = false
    groupForm.status = 'ENABLED'
    groupForm.orderEnabled = true
    groupForm.realNameRequiredForOrder = false
    groupForm.priceLimitEnabled = true
    groupForm.priceLimitNotice = DEFAULT_PRICE_LIMIT_NOTICE
    ElMessage.success('会员分组已新增')
  } catch {
    ElMessage.error('新增会员分组失败')
  }
}

function statusLabel(value?: string) {
  return formatUserStatus(value)
}

function formatMoney(value?: number | string) {
  return formatAmount(value, { currency: false, fallback: '0.00' })
}

function statusTagType(value?: string) {
  return userStatusTagType(value)
}

function formatTime(value?: string) {
  return formatDateTime(value)
}

function maskedCertificate(value?: string) {
  return maskCertificate(value)
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
          <el-button type="primary" :icon="Plus" @click="groupDialogVisible = true">新增会员分组</el-button>
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
          <span>按销售平台与下单条件设置该用户组规则</span>
        </div>
      </div>

      <div v-if="selectedGroup" class="order-permission-card">
        <div>
          <h3>下单权限设置</h3>
          <p>控制该会员组是否可以下单、购买限价商品，以及下单前是否必须完成实名认证。</p>
        </div>
        <div class="permission-switches">
          <label class="switch-row">
            <span>
              <strong>允许下单</strong>
              <em>关闭后该分组用户无法创建新订单</em>
            </span>
            <el-switch v-model="selectedGroup.orderEnabled" active-text="开启" inactive-text="关闭" />
          </label>
          <label class="switch-row">
            <span>
              <strong>必须实名认证</strong>
              <em>开启后仅已实名用户可下单</em>
            </span>
            <el-switch v-model="selectedGroup.realNameRequiredForOrder" active-text="开启" inactive-text="关闭" />
          </label>
          <label class="switch-row">
            <span>
              <strong>限价商品</strong>
              <em>关闭后该分组仍可看到限价商品，但无法创建限价商品订单</em>
            </span>
            <el-switch v-model="selectedGroup.priceLimitEnabled" active-text="开启" inactive-text="关闭" />
          </label>
          <label class="switch-row switch-row--stacked">
            <span>
              <strong>限价提示</strong>
              <em>用户尝试购买限价商品时展示</em>
            </span>
            <el-input
              v-model="selectedGroup.priceLimitNotice"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="当前会员组暂未开放限价商品购买权限，请联系平台客服处理。"
            />
          </label>
          <el-button type="primary" :icon="Save" :loading="orderPermissionSaving" @click="saveOrderPermission">
            保存下单权限
          </el-button>
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

    </article>

    <article class="panel users-panel">
      <div class="panel-head">
        <div>
          <h2>用户列表</h2>
          <span>将用户分配到对应用户组</span>
        </div>
      </div>

      <el-table v-loading="loading" :data="users" height="420" style="width: 100%">
        <el-table-column prop="nickname" label="用户" min-width="130" fixed="left" />
        <el-table-column prop="mobile" label="手机号" min-width="140" />
        <el-table-column label="用户组" min-width="230">
          <template #default="{ row }">
            <el-select v-model="row.groupId" aria-label="选择用户组" @change="changeUserGroup(row)">
              <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="实名信息" min-width="210">
          <template #default="{ row }">
            <UserRealNameCell
              :real-name-type="row.realNameType"
              :real-name="row.realName"
              :subject-name="row.subjectName"
              :verification-status="row.verificationStatus"
            />
          </template>
        </el-table-column>
        <el-table-column label="证件/主体编号" min-width="150">
          <template #default="{ row }">{{ maskedCertificate(row.certificateNo) }}</template>
        </el-table-column>
        <el-table-column label="余额" width="120">
          <template #default="{ row }">{{ formatMoney(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="保证金" width="120">
          <template #default="{ row }">{{ formatMoney(row.deposit) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最后登录" min-width="150">
          <template #default="{ row }">{{ formatTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="实名状态" width="110">
          <template #default="{ row }">
            <UserVerificationTag :status="row.verificationStatus" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" :icon="UserCog" @click="openCredentialDialog(row)">账号</el-button>
              <el-button size="small" :icon="Banknote" @click="openFundDialog(row)">资金</el-button>
              <el-button size="small" :icon="KeyRound" @click="openApiDialog(row)">API 配置</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>

  <el-dialog v-model="groupDialogVisible" title="新增会员分组" width="520px" class="xiyiyun-glass-dialog users-dialog">
    <el-form :model="groupForm" label-position="top">
      <el-form-item label="会员分组名称">
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
      <el-form-item label="下单权限">
        <div class="dialog-switch-grid">
          <el-switch v-model="groupForm.orderEnabled" active-text="允许下单" inactive-text="禁止下单" />
          <el-switch v-model="groupForm.realNameRequiredForOrder" active-text="必须实名" inactive-text="不强制实名" />
          <el-switch v-model="groupForm.priceLimitEnabled" active-text="开放限价" inactive-text="限制购买" />
        </div>
      </el-form-item>
      <el-form-item label="限价提示">
        <el-input
          v-model="groupForm.priceLimitNotice"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="用户购买限价商品受限时展示"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="groupDialogVisible = false">取消</el-button>
      <el-button type="primary" :icon="Plus" @click="submitGroup">新增分组</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="credentialDialogVisible" title="修改用户账号密码" width="560px" class="xiyiyun-glass-dialog users-dialog">
    <el-form :model="credentialForm" label-position="top">
      <div class="fund-target">
        <span>当前用户</span>
        <strong>{{ selectedUser?.nickname || '-' }}</strong>
        <em>用户 ID {{ selectedUser?.id || '-' }} · {{ selectedUser?.mobile || selectedUser?.email || '未设置账号' }}</em>
      </div>
      <el-form-item label="登录账号">
        <el-input v-model="credentialForm.account" placeholder="手机号或邮箱" />
      </el-form-item>
      <el-form-item label="用户昵称">
        <el-input v-model="credentialForm.nickname" placeholder="用于后台与前台展示" />
      </el-form-item>
      <div class="credential-password-grid">
        <el-form-item label="新密码">
          <el-input v-model="credentialForm.newPassword" show-password placeholder="留空则不修改密码" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="credentialForm.confirmPassword" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </div>
      <p class="dialog-hint">账号支持手机号或邮箱。填写新密码后会重置该用户登录密码，并清理该用户现有登录态。</p>
    </el-form>
    <template #footer>
      <el-button @click="credentialDialogVisible = false">取消</el-button>
      <el-button type="primary" :icon="Save" :loading="credentialSaving" @click="submitCredentialUpdate">保存修改</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="fundDialogVisible" title="资金调整" width="560px" class="xiyiyun-glass-dialog users-dialog">
    <el-form :model="fundForm" label-position="top">
      <div class="fund-target">
        <span>当前用户</span>
        <strong>{{ selectedUser?.nickname || '-' }}</strong>
        <em>余额 {{ formatMoney(selectedUser?.balance) }} · 保证金 {{ formatMoney(selectedUser?.deposit) }}</em>
      </div>
      <el-form-item label="账户类型">
        <el-radio-group v-model="fundForm.accountType">
          <el-radio-button value="balance">余额</el-radio-button>
          <el-radio-button value="deposit">保证金</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="调整方向">
        <el-radio-group v-model="fundForm.direction">
          <el-radio-button value="increase">增加</el-radio-button>
          <el-radio-button value="decrease">扣减</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="调整金额">
        <el-input-number v-model="fundForm.amount" :min="0" :precision="2" :step="10" controls-position="right" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="fundForm.remark" type="textarea" :rows="3" placeholder="例如：人工充值、保证金补缴、异常扣减" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="fundDialogVisible = false">取消</el-button>
      <el-button type="primary" :icon="Banknote" :loading="fundSaving" @click="submitFundAdjust">确认调整</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="apiDialogVisible" title="会员 API 下单配置" width="680px" class="xiyiyun-glass-dialog users-dialog">
    <el-form v-loading="apiLoading" :model="apiForm" label-position="top">
      <div class="fund-target">
        <span>当前用户</span>
        <strong>{{ selectedUser?.nickname || '-' }}</strong>
        <em>用户 ID {{ selectedUser?.id || '-' }} · 会员 API 下单权限</em>
      </div>
      <el-form-item label="对接权限">
        <el-switch v-model="apiForm.enabled" active-text="启用" inactive-text="停用" />
      </el-form-item>
      <el-form-item label="用户 ID">
        <el-input :model-value="String(selectedUser?.id || '')" readonly>
          <template #append>
            <el-button @click="copyText(String(selectedUser?.id || ''), '用户 ID')">复制</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="用户 Key">
        <el-input v-model="apiForm.appKey">
          <template #append>
            <el-button @click="copyText(apiForm.appKey, '用户 Key')">复制</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="用户 Secret">
        <el-input v-model="apiForm.appSecret" show-password>
          <template #append>
            <el-button @click="copyText(apiForm.appSecret, '用户 Secret')">复制</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="白名单 IP">
        <el-input
          v-model="apiForm.ipWhitelistText"
          type="textarea"
          :rows="4"
          placeholder="每行一个 IP；留空表示不限制来源 IP"
        />
      </el-form-item>
      <el-form-item label="每日下单限制">
        <el-input-number v-model="apiForm.dailyLimit" :min="1" :step="100" controls-position="right" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="apiDialogVisible = false">取消</el-button>
      <el-button :icon="RefreshCw" :loading="apiSaving" @click="submitApiConfig(true)">重置 Secret</el-button>
      <el-button type="primary" :icon="Save" :loading="apiSaving" @click="submitApiConfig(false)">保存配置</el-button>
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

.order-permission-card {
  display: grid;
  grid-template-columns: minmax(180px, 0.8fr) minmax(360px, 1.2fr);
  gap: 16px;
  margin-bottom: 18px;
  padding: 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.08), rgba(88, 166, 255, 0.07));
  border: 0.5px solid rgba(0, 255, 195, 0.13);
}

.order-permission-card p {
  margin: 6px 0 0;
  color: rgba(255, 255, 255, 0.5);
  font-size: 13px;
  line-height: 1.6;
}

.permission-switches {
  display: grid;
  gap: 10px;
}

.switch-row {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.switch-row span {
  display: grid;
  gap: 3px;
}

.switch-row strong {
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
}

.switch-row em {
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
  font-style: normal;
}

.switch-row--stacked {
  align-items: stretch;
  flex-direction: column;
}

.switch-row--stacked :deep(.el-textarea__inner) {
  color: rgba(255, 255, 255, 0.86);
  border-color: rgba(0, 255, 195, 0.16);
  background: rgba(2, 10, 20, 0.42);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
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

.fund-target {
  display: grid;
  gap: 6px;
  margin-bottom: 16px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.fund-target span,
.fund-target em {
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
  font-style: normal;
}

.fund-target strong {
  color: rgba(255, 255, 255, 0.92);
  font-size: 18px;
}

.credential-password-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dialog-hint {
  margin: 4px 0 0;
  color: rgba(214, 226, 240, 0.56);
  font-size: 12px;
  line-height: 1.7;
}

.dialog-switch-grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

:global(.users-dialog.el-dialog) {
  --el-dialog-bg-color: rgba(8, 17, 31, 0.92);
  border: 0.5px solid rgba(255, 255, 255, 0.14);
  border-radius: 22px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.36), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(24px) saturate(170%);
}

:global(.users-dialog .el-dialog__header) {
  padding: 18px 22px 10px;
  margin: 0;
}

:global(.users-dialog .el-dialog__title) {
  color: rgba(255, 255, 255, 0.92);
  font-weight: 700;
}

:global(.users-dialog .el-dialog__body) {
  padding: 10px 22px 8px;
}

:global(.users-dialog .el-dialog__footer) {
  padding: 12px 22px 18px;
}

:global(.users-dialog .el-form-item__label) {
  color: rgba(255, 255, 255, 0.7);
}

:global(.users-dialog .el-dialog__close) {
  color: rgba(255, 255, 255, 0.72);
}
</style>
