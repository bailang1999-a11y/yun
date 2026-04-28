const baseUrl = process.env.API_BASE_URL || 'http://localhost:8080'
const concurrency = Number(process.env.CONCURRENCY || 12)
const rounds = Number(process.env.ROUNDS || 20)

async function json(path, options = {}) {
  const start = performance.now()
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  })
  const body = await response.json()
  const durationMs = performance.now() - start
  if (body.code !== 0) throw new Error(`${path}: ${body.message}`)
  return durationMs
}

async function worker(index) {
  const durations = []
  const login = await fetch(`${baseUrl}/api/h5/auth/sms/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account: `1390000${String(index).padStart(4, '0')}`, code: '000000' })
  }).then((res) => res.json())
  const token = login.data.token
  for (let round = 0; round < rounds; round++) {
    durations.push(await json('/api/h5/goods?platform=h5&page=1&pageSize=10'))
    durations.push(await json('/api/h5/orders', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ goodsId: 10002, quantity: 1, rechargeAccount: `1390000${String(index).padStart(4, '0')}`, requestId: `load_${index}_${round}_${Date.now()}` })
    }))
  }
  return durations
}

const startedAt = performance.now()
const results = (await Promise.all(Array.from({ length: concurrency }, (_, index) => worker(index + 1)))).flat()
results.sort((a, b) => a - b)
const elapsedSeconds = (performance.now() - startedAt) / 1000
const total = results.length
const p95 = results[Math.floor(total * 0.95)] || 0
const p99 = results[Math.floor(total * 0.99)] || 0

console.log(JSON.stringify({
  ok: true,
  concurrency,
  rounds,
  requests: total,
  elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
  rps: Number((total / elapsedSeconds).toFixed(2)),
  p95Ms: Number(p95.toFixed(1)),
  p99Ms: Number(p99.toFixed(1))
}, null, 2))
