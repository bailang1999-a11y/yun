import type { CreateOrderPayload } from '../types/web'

const PENDING_ORDER_TTL_MS = 15 * 60 * 1000

interface PendingOrderRequest {
  requestId: string
  fingerprint: string
  createdAt: number
}

export async function getPendingOrderRequestId(payload: Omit<CreateOrderPayload, 'requestId'>) {
  const terminal = payload.terminal || 'web'
  const storageKey = `xiyiyun_${terminal}_pending_order`
  const fingerprint = await payloadFingerprint(payload)
  const stored = readPendingOrder(storageKey)
  const now = Date.now()

  if (stored && stored.fingerprint === fingerprint && now - stored.createdAt < PENDING_ORDER_TTL_MS) {
    return stored.requestId
  }

  const requestId = `${terminal}_${now}_${payload.goodsId}_${randomSuffix()}`
  writePendingOrder(storageKey, { requestId, fingerprint, createdAt: now })
  return requestId
}

export function clearPendingOrderRequest(terminal: string, requestId: string) {
  const storageKey = `xiyiyun_${terminal}_pending_order`
  const stored = readPendingOrder(storageKey)
  if (stored?.requestId === requestId) {
    try {
      sessionStorage.removeItem(storageKey)
    } catch {
      // Storage may be unavailable in privacy-restricted browsers.
    }
  }
}

async function payloadFingerprint(payload: Omit<CreateOrderPayload, 'requestId'>) {
  const rechargeFields = Object.fromEntries(
    Object.entries(payload.rechargeFields || {}).sort(([left], [right]) => left.localeCompare(right))
  )
  const source = JSON.stringify({
    goodsId: String(payload.goodsId),
    quantity: payload.quantity,
    rechargeAccount: payload.rechargeAccount?.trim() || '',
    rechargeFields,
    buyerRemark: payload.buyerRemark?.trim() || '',
    terminal: payload.terminal || 'web'
  })
  if (globalThis.crypto?.subtle) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(source))
    return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('')
  }
  return fallbackFingerprint(source)
}

function readPendingOrder(storageKey: string): PendingOrderRequest | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') as Partial<PendingOrderRequest> | null
    if (!value?.requestId || !value.fingerprint || !Number.isFinite(value.createdAt)) return null
    return value as PendingOrderRequest
  } catch {
    return null
  }
}

function writePendingOrder(storageKey: string, request: PendingOrderRequest) {
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(request))
  } catch {
    // The in-memory requestId still protects retries in the current submission.
  }
}

function randomSuffix() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID().replaceAll('-', '').slice(0, 12)
  }
  return Math.random().toString(36).slice(2, 14)
}

function fallbackFingerprint(source: string) {
  let hash = 2166136261
  for (let index = 0; index < source.length; index += 1) {
    hash = Math.imul(hash ^ source.charCodeAt(index), 16777619)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}
