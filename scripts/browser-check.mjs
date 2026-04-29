import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'

const h5Url = process.env.H5_URL || 'http://localhost:5173'
const adminUrl = process.env.ADMIN_URL || 'http://localhost:5174'
const outputDir = path.resolve('artifacts/browser-check')

async function expectVisible(page, text, label) {
  const locator = page.getByText(text, { exact: false }).first()
  await locator.waitFor({ state: 'visible', timeout: 8000 })
  return label
}

async function main() {
  await fs.mkdir(outputDir, { recursive: true })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const page = await context.newPage()
  const checked = []

  page.on('pageerror', (error) => {
    throw error
  })

  await page.goto(`${h5Url}/mine`, { waitUntil: 'networkidle' })
  await page.getByPlaceholder('输入手机号或邮箱').fill('13800000001')
  await page.getByRole('button', { name: /登录/ }).click()
  checked.push(await expectVisible(page, '默认会员', 'h5 login'))
  await page.screenshot({ path: path.join(outputDir, 'h5-mine.png'), fullPage: true })

  await page.goto(h5Url, { waitUntil: 'networkidle' })
  checked.push(await expectVisible(page, '可售商品', 'h5 goods list'))
  await page.getByRole('button', { name: '购买' }).first().click()
  checked.push(await expectVisible(page, '立即购买', 'h5 goods detail'))
  await page.getByRole('button', { name: '立即购买' }).click()
  checked.push(await expectVisible(page, '确认支付', 'h5 checkout'))
  await page.getByRole('button', { name: /支付宝/ }).click()
  await page.getByRole('button', { name: /使用支付宝/ }).click()
  checked.push(await expectVisible(page, '支付成功', 'h5 payment result'))
  await page.screenshot({ path: path.join(outputDir, 'h5-result.png'), fullPage: true })
  await page.getByText('提取卡密').click()
  checked.push(await expectVisible(page, '卡密已显示', 'h5 card delivery'))
  await page.screenshot({ path: path.join(outputDir, 'h5-cards.png'), fullPage: true })

  const adminContext = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  const admin = await adminContext.newPage()
  await admin.goto(adminUrl, { waitUntil: 'networkidle' })
  await admin.getByLabel('账号').fill('admin')
  await admin.getByLabel('密码').fill('admin123')
  await admin.getByRole('button', { name: '登录' }).click()
  checked.push(await expectVisible(admin, '实时业务看板', 'admin dashboard'))
  await admin.getByRole('button', { name: /订单管理/ }).click()
  checked.push(await expectVisible(admin, '订单列表', 'admin orders'))
  await admin.getByRole('button', { name: /审计开放/ }).click()
  checked.push(await expectVisible(admin, '审计与开放平台', 'admin audit'))
  await admin.getByRole('button', { name: /会员 API/ }).click()
  checked.push(await expectVisible(admin, 'demo_app_key', 'admin member api'))
  await admin.getByRole('button', { name: /系统设置/ }).click()
  checked.push(await expectVisible(admin, '支付与退款', 'admin settings'))
  await admin.screenshot({ path: path.join(outputDir, 'admin-settings.png'), fullPage: true })

  await browser.close()
  console.log(JSON.stringify({ ok: true, checked, screenshots: outputDir }, null, 2))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
