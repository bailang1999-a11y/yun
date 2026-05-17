export function formatMoney(value?: number | string, options: { currency?: boolean; fallback?: string } = {}) {
  const { currency = false, fallback = '-' } = options
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) return fallback
  const formatted = numberValue.toFixed(3).replace(/(\.\d{2})0$/, '$1')
  return `${currency ? '¥' : ''}${formatted}`
}
