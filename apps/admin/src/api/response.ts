export interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T | ApiEnvelope<T>
  list?: T
  records?: T
  items?: T
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export function assertApiOk(payload: unknown) {
  const envelope = payload as ApiEnvelope<unknown>
  if (envelope && typeof envelope === 'object' && typeof envelope.code === 'number' && envelope.code !== 0) {
    throw new Error(envelope.message || '请求失败')
  }
}

export function unwrapValue<T>(payload: T | ApiEnvelope<T>): T {
  assertApiOk(payload)
  const envelope = payload as ApiEnvelope<T>
  if (envelope && typeof envelope === 'object' && 'data' in envelope && envelope.data !== undefined) {
    return unwrapValue(envelope.data as T | ApiEnvelope<T>)
  }
  return payload as T
}

export function unwrapResponse<T>(payload: T | ApiEnvelope<T>): T {
  assertApiOk(payload)
  if (Array.isArray(payload)) {
    return payload as T
  }

  const envelope = payload as ApiEnvelope<T>
  const inner = envelope.data

  if (Array.isArray(inner)) {
    return inner as T
  }

  if (inner && typeof inner === 'object') {
    const nested = inner as ApiEnvelope<T>

    if (Array.isArray(nested.data)) {
      return nested.data as T
    }

    if (Array.isArray(nested.records)) {
      return nested.records as T
    }

    if (Array.isArray(nested.list)) {
      return nested.list as T
    }

    if (Array.isArray(nested.items)) {
      return nested.items as T
    }
  }

  if (Array.isArray(envelope.records)) {
    return envelope.records as T
  }

  if (Array.isArray(envelope.list)) {
    return envelope.list as T
  }

  if (Array.isArray(envelope.items)) {
    return envelope.items as T
  }

  return payload as T
}

export function unwrapPage<T>(payload: unknown): PageResult<T> {
  assertApiOk(payload)
  const value = unwrapValue<unknown>(payload as ApiEnvelope<unknown>)
  if (Array.isArray(value)) {
    return { items: value as T[], total: value.length, page: 1, pageSize: value.length || 10 }
  }
  const record = (value || {}) as Record<string, unknown>
  const items = Array.isArray(record.items)
    ? record.items
    : Array.isArray(record.records)
      ? record.records
      : Array.isArray(record.list)
        ? record.list
        : []
  const total = Number(record.total ?? items.length)
  const page = Number(record.page ?? 1)
  const pageSize = Number(record.pageSize ?? record.size ?? 10)
  return {
    items: items as T[],
    total: Number.isFinite(total) ? total : items.length,
    page: Number.isFinite(page) && page > 0 ? page : 1,
    pageSize: Number.isFinite(pageSize) && pageSize > 0 ? pageSize : 10
  }
}
