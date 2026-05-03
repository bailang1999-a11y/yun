import { apiClient } from './client'
import { booleanValue, numberValue, stringArray, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type {
  GroupRule,
  GroupRulePatchPayload,
  MemberApiCredential,
  MemberApiCredentialPayload,
  OpenApiLog,
  UserAccount,
  UserFundAdjustPayload,
  UserGroup,
  UserGroupCreatePayload
} from '../types/operations'

export async function fetchMemberApiCredentials() {
  const { data } = await apiClient.get<unknown>('/api/admin/member-api-credentials')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeMemberApiCredential)
}

export async function fetchUserMemberApiCredential(userId: UserAccount['id']) {
  const { data } = await apiClient.get<unknown>(`/api/admin/users/${userId}/member-api`)
  return normalizeMemberApiCredential(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function saveUserMemberApiCredential(userId: UserAccount['id'], payload: MemberApiCredentialPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/users/${userId}/member-api`, payload)
  return normalizeMemberApiCredential(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchOpenApiLogs() {
  const { data } = await apiClient.get<unknown>('/api/admin/open-api-logs')
  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeOpenApiLog)
}

export async function fetchUserGroups() {
  const { data } = await apiClient.get<unknown>('/api/admin/user-groups')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeUserGroup)
}

export async function createUserGroup(payload: UserGroupCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/user-groups', payload)

  return normalizeUserGroup(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function fetchUsers() {
  const { data } = await apiClient.get<unknown>('/api/admin/users')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeUser)
}

export async function updateGroupRules(groupId: UserGroup['id'], payload: GroupRulePatchPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/user-groups/${groupId}/rules`, payload)

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeGroupRule)
}

export async function updateUserGroupOrderPermission(
  groupId: UserGroup['id'],
  payload: { orderEnabled: boolean; realNameRequiredForOrder: boolean }
) {
  const { data } = await apiClient.post<unknown>(`/api/admin/user-groups/${groupId}/order-permission`, payload)

  return normalizeUserGroup(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateUserGroup(userId: UserAccount['id'], groupId: UserGroup['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/users/${userId}/group`, { groupId })

  return normalizeUser(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function adjustUserFunds(userId: UserAccount['id'], payload: UserFundAdjustPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/users/${userId}/funds`, payload)

  return normalizeUser(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

function normalizeGroupRule(item: Record<string, unknown>): GroupRule {
  const ruleType = text(item.ruleType, 'CATEGORY').toUpperCase() === 'PLATFORM' ? 'PLATFORM' : 'CATEGORY'
  const permissionText = text(item.permission, 'NONE').toUpperCase()
  const permission = permissionText === 'ALLOW' || permissionText === 'DENY' ? permissionText : 'NONE'

  return {
    groupId: text(item.groupId),
    ruleType,
    targetId: text(item.targetId) || undefined,
    targetCode: text(item.targetCode) || undefined,
    targetName: text(item.targetName) || undefined,
    permission
  }
}

function normalizeUserGroup(item: Record<string, unknown>): UserGroup {
  const rawRules = Array.isArray(item.rules) ? item.rules : []
  return {
    id: text(item.id),
    name: text(item.name, '未命名用户组'),
    description: text(item.description),
    defaultGroup: Boolean(item.defaultGroup),
    userCount: numberValue(item.userCount),
    status: text(item.status, 'UNKNOWN'),
    orderEnabled: booleanValue(item.orderEnabled, true),
    realNameRequiredForOrder: booleanValue(item.realNameRequiredForOrder, false),
    rules: rawRules.map((rule) => normalizeGroupRule((rule || {}) as Record<string, unknown>))
  }
}

function normalizeUser(item: Record<string, unknown>): UserAccount {
  return {
    id: text(item.id),
    avatar: text(item.avatar),
    mobile: text(item.mobile),
    email: text(item.email),
    nickname: text(item.nickname, '未命名用户'),
    groupId: text(item.groupId),
    groupName: text(item.groupName),
    balance: numberValue(item.balance),
    deposit: numberValue(item.deposit),
    status: text(item.status, 'UNKNOWN'),
    createdAt: text(item.createdAt),
    lastLoginAt: text(item.lastLoginAt),
    realNameType: text(item.realNameType),
    realName: text(item.realName),
    subjectName: text(item.subjectName),
    certificateNo: text(item.certificateNo),
    verificationStatus: text(item.verificationStatus)
  }
}

function normalizeMemberApiCredential(item: Record<string, unknown>): MemberApiCredential {
  return {
    id: text(item.id),
    userId: text(item.userId),
    appKey: text(item.appKey),
    appSecret: text(item.appSecret),
    status: text(item.status),
    ipWhitelist: stringArray(item.ipWhitelist),
    dailyLimit: numberValue(item.dailyLimit),
    createdAt: text(item.createdAt),
    lastUsedAt: text(item.lastUsedAt)
  }
}

function normalizeOpenApiLog(item: Record<string, unknown>): OpenApiLog {
  return {
    id: text(item.id),
    userId: text(item.userId),
    appKey: text(item.appKey),
    path: text(item.path),
    status: text(item.status),
    message: text(item.message),
    createdAt: text(item.createdAt)
  }
}
