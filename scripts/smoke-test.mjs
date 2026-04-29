import crypto from 'node:crypto'

const baseUrl = process.env.API_BASE_URL || 'http://localhost:8080'

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

function sign(path, noncePrefix = 'n') {
  const timestamp = Math.floor(Date.now() / 1000).toString()
  const nonce = `${noncePrefix}${Date.now()}${Math.random().toString(16).slice(2)}`
  const signature = crypto
    .createHmac('sha256', 'demo_app_secret')
    .update(`${timestamp}\n${nonce}\n${path}`)
    .digest('hex')
  return {
    'X-App-Key': 'demo_app_key',
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': signature
  }
}

async function main() {
  const h5Session = await request('/api/h5/auth/sms/login', {
    method: 'POST',
    body: JSON.stringify({ account: '13800000001', code: '000000' })
  })
  const auth = { Authorization: `Bearer ${h5Session.token}` }

  const goods = await request('/api/h5/goods?platform=h5&page=1&pageSize=10')
  if (!goods.items?.length) throw new Error('h5 goods list is empty')

  const order = await request('/api/h5/orders', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ goodsId: 10001, quantity: 1, requestId: `smoke_${Date.now()}` })
  })
  const paid = await request(`/api/h5/orders/${order.orderNo}/pay`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ payMethod: 'alipay', terminal: 'h5' })
  })
  if (paid.status !== 'DELIVERED' || !paid.paymentNo) throw new Error('paid order not delivered')

  const delivery = await request(`/api/h5/orders/${paid.orderNo}/delivery`, { headers: auth })
  if (!delivery.cards?.length) throw new Error('card delivery missing')

  const admin = await request('/api/admin/auth/login', {
    method: 'POST',
    body: JSON.stringify({ account: 'admin', password: 'admin123' })
  })
  const adminAuth = { Authorization: `Bearer ${admin.token}` }
  await request('/api/admin/orders', { headers: adminAuth })
  await request('/api/admin/payments', { headers: adminAuth })
  await request('/api/admin/settings', { headers: adminAuth })

  const memberBalance = await request('/api/member/balance', { headers: sign('/api/member/balance', 'b') })
  if (!memberBalance.id) throw new Error('member balance missing')
  const memberOrder = await request('/api/member/orders', {
    method: 'POST',
    headers: sign('/api/member/orders', 'o'),
    body: JSON.stringify({ goodsId: 10002, quantity: 1, rechargeAccount: '13800000002', requestId: `member_${Date.now()}` })
  })
  if (!['DELIVERED', 'WAITING_MANUAL', 'FAILED', 'REFUNDED'].includes(memberOrder.status)) {
    throw new Error(`unexpected member order status ${memberOrder.status}`)
  }

  console.log(JSON.stringify({
    ok: true,
    h5Order: paid.orderNo,
    paymentNo: paid.paymentNo,
    memberOrder: memberOrder.orderNo,
    deliveredCards: delivery.cards.length
  }, null, 2))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
