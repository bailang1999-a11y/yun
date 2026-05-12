export function cleanParams(params: object) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '')
  )
}

export function text(value: unknown, fallback = '') {
  return value === null || value === undefined ? fallback : String(value)
}

export function numberValue(value: unknown, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

export function booleanValue(value: unknown, fallback = false) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (['false', '0', 'disabled', 'disable', 'off', 'no'].includes(normalized)) return false
    if (['true', '1', 'enabled', 'enable', 'on', 'yes'].includes(normalized)) return true
  }
  return Boolean(value)
}

export function stringArray(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item)).filter(Boolean) : []
}
