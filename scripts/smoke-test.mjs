import crypto from 'node:crypto'

const baseUrl = process.env.API_BASE_URL || 'http://localhost:8080'
const captchaTicket = process.env.CAPTCHA_TICKET || ''
const captchaRandstr = process.env.CAPTCHA_RANDSTR || ''
const memberAppKey = process.env.MEMBER_APP_KEY || 'demo_app_key'
const memberAppSecret = process.env.MEMBER_APP_SECRET || 'demo_app_secret'
const allowMutatingRemoteTest = process.env.ALLOW_MUTATING_REMOTE_TEST === '1'

guardMutatingBaseUrl()

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  })
  const body = await response.json()
  if (body.code !== 0) {
    throw new Error(`${path} failed: ${body.message}`)
  }
  return body.data
}

async function optionalRequest(path, options = {}) {
  try {
    return await request(path, options)
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) }
  }
}

function sign(path, noncePrefix = 'n') {
  const timestamp = Math.floor(Date.now() / 1000).toString()
  const nonce = `${noncePrefix}${Date.now()}${Math.random().toString(16).slice(2)}`
  const signature = crypto
    .createHmac('sha256', memberAppSecret)
    .update(`${timestamp}\n${nonce}\n${path}`)
    .digest('hex')
  return {
    'X-App-Key': memberAppKey,
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': signature
  }
}

function guardMutatingBaseUrl() {
  const parsed = new URL(baseUrl)
  const hostname = parsed.hostname.toLowerCase()
  const isLocal = hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]' || hostname === '::1'
  if (!isLocal && !allowMutatingRemoteTest) {
    throw new Error('smoke-test creates/logs in/orders and is blocked for non-local API_BASE_URL. Set ALLOW_MUTATING_REMOTE_TEST=1 only for an isolated staging environment.')
  }
  console.warn(`smoke-test: mutating demo flow against ${baseUrl}`)
}

async function main() {
  const health = await request('/api/health')

  const goods = await request('/api/h5/goods?page=1&pageSize=10')
  if (!goods.items?.length) throw new Error('h5 goods list is empty')

  const h5Captcha = await request('/api/h5/auth/captcha-config?terminal=h5')
  const adminCaptcha = await request('/api/admin/auth/captcha-config')
  const canAutomateCaptcha = Boolean(captchaTicket && captchaRandstr)
  const skipped = []
  let h5Order = null
  let paymentNo = null
  let deliveredCards = 0

  if (h5Captcha.enabled && !canAutomateCaptcha) {
    skipped.push('h5 login/order flow skipped: captcha is enabled; set CAPTCHA_TICKET and CAPTCHA_RANDSTR to test it')
  } else {
    const h5Session = await request('/api/h5/auth', {
      method: 'POST',
      body: JSON.stringify({
        account: '13800000001',
        password: '000000',
        terminal: 'h5',
        mode: 'login',
        captchaTicket,
        captchaRandstr
      })
    })
    const auth = { Authorization: `Bearer ${h5Session.token}` }

    const order = await request('/api/h5/orders', {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ goodsId: goods.items[0].id, quantity: 1, requestId: `smoke_${Date.now()}` })
    })
    const paid = await request(`/api/h5/orders/${order.orderNo}/pay`, {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ payMethod: 'balance', terminal: 'h5' })
    })
    if (!paid.paymentNo) throw new Error('paid order paymentNo missing')
    const delivery = await optionalRequest(`/api/h5/orders/${paid.orderNo}/delivery`, { headers: auth })
    h5Order = paid.orderNo
    paymentNo = paid.paymentNo
    deliveredCards = Array.isArray(delivery.cards) ? delivery.cards.length : 0
  }

  if (adminCaptcha.enabled && !canAutomateCaptcha) {
    skipped.push('admin authenticated APIs skipped: captcha is enabled; set CAPTCHA_TICKET and CAPTCHA_RANDSTR to test it')
  } else {
    const admin = await request('/api/admin/auth/login', {
      method: 'POST',
      body: JSON.stringify({ account: 'admin', password: 'admin123', captchaTicket, captchaRandstr })
    })
    const adminAuth = { Authorization: `Bearer ${admin.token}` }
    await request('/api/admin/orders', { headers: adminAuth })
    await request('/api/admin/payments', { headers: adminAuth })
    await request('/api/admin/settings', { headers: adminAuth })
    await request('/api/admin/price-templates', { headers: adminAuth })
  }

  let memberOrderNo = null
  const memberBalance = await optionalRequest('/api/member/balance', { headers: sign('/api/member/balance', 'b') })
  if (memberBalance.error) {
    skipped.push(`member API skipped: ${memberBalance.error}; set MEMBER_APP_KEY and MEMBER_APP_SECRET to test it`)
  } else {
    if (!memberBalance.id) throw new Error('member balance missing')
    const memberOrder = await request('/api/member/orders', {
      method: 'POST',
      headers: sign('/api/member/orders', 'o'),
      body: JSON.stringify({ goodsId: goods.items[0].id, quantity: 1, rechargeAccount: '13800000002', requestId: `member_${Date.now()}` })
    })
    if (!['DELIVERED', 'WAITING_MANUAL', 'FAILED', 'REFUNDED', 'PROCURING'].includes(memberOrder.status)) {
      throw new Error(`unexpected member order status ${memberOrder.status}`)
    }
    memberOrderNo = memberOrder.orderNo
  }

  console.log(JSON.stringify({
    ok: true,
    health: health.status,
    goodsCount: goods.items.length,
    h5Order,
    paymentNo,
    memberOrder: memberOrderNo,
    deliveredCards,
    skipped
  }, null, 2))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
