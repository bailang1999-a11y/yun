export interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T | ApiEnvelope<T>
  list?: T
  records?: T
  items?: T
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
