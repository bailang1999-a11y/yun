import { apiClient } from './client'
import { booleanValue, cleanParams, numberValue, text } from './normalize'
import { type ApiEnvelope, unwrapResponse, unwrapValue } from './response'
import type {
  Category,
  CategoryCreatePayload,
  CategoryUpdatePayload,
  RechargeField,
  RechargeFieldPayload
} from '../types/operations'

export async function fetchRechargeFields(query: { enabled?: boolean } = {}) {
  const { data } = await apiClient.get<unknown>('/api/admin/recharge-fields', {
    params: cleanParams(query)
  })

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeRechargeField)
}

export async function createRechargeField(payload: RechargeFieldPayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/recharge-fields', payload)

  return normalizeRechargeField(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateRechargeField(id: RechargeField['id'], payload: RechargeFieldPayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/recharge-fields/${id}`, payload)

  return normalizeRechargeField(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function enableRechargeField(id: RechargeField['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/recharge-fields/${id}/enable`)

  return normalizeRechargeField(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function disableRechargeField(id: RechargeField['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/recharge-fields/${id}/disable`)

  return normalizeRechargeField(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteRechargeField(id: RechargeField['id']) {
  const { data } = await apiClient.post<unknown>(`/api/admin/recharge-fields/${id}/delete`)

  return unwrapValue<string>(data as ApiEnvelope<string>)
}

export async function fetchCategories() {
  const { data } = await apiClient.get<unknown>('/api/admin/categories')

  return unwrapResponse<Record<string, unknown>[]>(data as ApiEnvelope<Record<string, unknown>[]>).map(normalizeCategory)
}

export async function createCategory(payload: CategoryCreatePayload) {
  const { data } = await apiClient.post<unknown>('/api/admin/categories', payload)

  return normalizeCategory(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function updateCategory(categoryId: Category['id'], payload: CategoryUpdatePayload) {
  const { data } = await apiClient.post<unknown>(`/api/admin/categories/${categoryId}`, payload)

  return normalizeCategory(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

export async function deleteCategory(categoryId: Category['id']) {
  const { data } = await apiClient.post(`/api/admin/categories/${categoryId}/delete`)

  return data
}

export async function setCategoryEnabled(categoryId: Category['id'], enabled: boolean) {
  const action = enabled ? 'enable' : 'disable'
  const { data } = await apiClient.post<unknown>(`/api/admin/categories/${categoryId}/${action}`)

  return normalizeCategory(unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>))
}

function normalizeRechargeField(item: Record<string, unknown>): RechargeField {
  return {
    id: text(item.id),
    code: text(item.code),
    label: text(item.label, '未命名字段'),
    placeholder: text(item.placeholder),
    helpText: text(item.helpText),
    inputType: text(item.inputType, 'TEXT'),
    required: booleanValue(item.required, true),
    sort: numberValue(item.sort, 0),
    enabled: booleanValue(item.enabled, true),
    createdAt: text(item.createdAt),
    updatedAt: text(item.updatedAt)
  }
}

function normalizeCategory(item: Record<string, unknown>): Category {
  const rawChildren = item.children
  const rawParentId = text(item.parentId ?? item.parent_id)
  return {
    id: text(item.id),
    name: text(item.name ?? item.categoryName, '未命名分类'),
    nickname: text(item.nickname),
    icon: text(item.icon),
    iconKey: text(item.iconKey),
    iconUrl: text(item.iconUrl),
    customIconUrl: text(item.customIconUrl),
    parentId: rawParentId && rawParentId !== '0' ? rawParentId : undefined,
    sort: numberValue(item.sort ?? item.sortOrder ?? item.sort_order),
    enabled: booleanValue(item.enabled ?? item.isEnabled ?? item.status, true),
    level: numberValue(item.level),
    children: Array.isArray(rawChildren)
      ? rawChildren.map((child) => normalizeCategory((child || {}) as Record<string, unknown>))
      : undefined
  }
}
