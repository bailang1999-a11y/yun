<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Activity, BookOpen, Check, ClipboardCopy, Code2, Globe2, KeyRound, Layers3, PackageOpen, Save, ServerCog, ShieldCheck, SlidersHorizontal, Truck, Users } from 'lucide-vue-next'
import { fetchCategories } from '../api/catalog'
import { fetchGoods } from '../api/goods'
import { fetchOutboundProtocolSettings, saveOutboundProtocolSettings } from '../api/outboundProtocols'
import { fetchMemberApiCredentials, fetchOpenApiLogsPage } from '../api/users'
import type { Category, Goods, MemberApiCredential, OpenApiLog } from '../types/operations'

type ProtocolId = 'KASUSHOU' | 'KAKAYUN' | 'FULU' | 'FENGZHUSHOU' | 'CHENGQUAN' | 'FANCHEN' | 'JINGZHAO'
type CapabilityState = 'supported' | 'mapped'
type ProtocolDraft = { enabled: boolean }
type InvocationLog = OpenApiLog & { protocol: ProtocolId | 'UNKNOWN' }

interface ProtocolDefinition {
  id: ProtocolId
  name: string
  shortName: string
  identityLabel: string
  secretLabel: string
  callbackLabel: string
  signature: string
  note: string
  capabilities: Record<'balance' | 'goods' | 'card' | 'direct' | 'order', CapabilityState>
  endpoints: Array<{ name: string; method: 'GET' | 'POST'; path: string }>
}

const fullCapabilities = { balance: 'supported', goods: 'supported', card: 'supported', direct: 'supported', order: 'supported' } as const
const mappedCapabilities = { ...fullCapabilities, goods: 'mapped' } as const
const protocols: ProtocolDefinition[] = [
  {
    id: 'KASUSHOU', name: '卡速售兼容协议', shortName: '卡速售', identityLabel: 'APPID', secretLabel: '签名密钥', callbackLabel: '订单异步通知地址',
    signature: 'Headers 使用 Sign / Timestamp / APPID，按卡速售签名规则验签。',
    note: '商品列表、卡密和直充订单均通过卡速售标准路径对外提供。', capabilities: fullCapabilities,
    endpoints: [
      { name: '账户信息', method: 'POST', path: '/api/v1/user/info' }, { name: '商品分类', method: 'POST', path: '/api/v1/goods/cate' },
      { name: '商品列表', method: 'POST', path: '/api/v1/goods/list' }, { name: '创建订单', method: 'POST', path: '/api/v1/order/buy' },
      { name: '订单查询', method: 'POST', path: '/api/v1/order/info' }
    ]
  },
  {
    id: 'KAKAYUN', name: '咔咔云兼容协议', shortName: '咔咔云', identityLabel: 'userid', secretLabel: '商户 KEY', callbackLabel: '订单回调地址',
    signature: '请求参数按 ASCII 排序拼接并追加商户 KEY，计算 MD5 签名。', note: '同时适配卡密回传和直充状态回调。', capabilities: fullCapabilities,
    endpoints: [
      { name: '账户信息', method: 'POST', path: '/dockapiv3/user/info' }, { name: '商品分组', method: 'POST', path: '/dockapiv3/goods/group' },
      { name: '全部商品', method: 'POST', path: '/dockapiv3/goods/all' }, { name: '创建订单', method: 'POST', path: '/dockapiv3/order/create' },
      { name: '订单查询', method: 'POST', path: '/dockapiv3/order/get' }
    ]
  },
  {
    id: 'FULU', name: '福禄新平台兼容协议', shortName: '福禄新平台', identityLabel: 'app_key', secretLabel: 'app_secret', callbackLabel: '订单通知地址',
    signature: '统一网关参数排序后追加 APP_SECRET，计算 MD5 小写签名。', note: '协议不提供商品列表，需通过喜易云商品映射开放卡密与直充商品。', capabilities: mappedCapabilities,
    endpoints: [
      { name: '统一网关', method: 'POST', path: '/api/rechargeapi/gateway' }, { name: '余额查询方法', method: 'POST', path: 'merchant.balance.query' },
      { name: '订单查询方法', method: 'POST', path: 'order.query' }, { name: '订单通知方法', method: 'POST', path: 'order.notify' }
    ]
  },
  {
    id: 'FENGZHUSHOU', name: '蜂助手兼容协议', shortName: '蜂助手', identityLabel: 'projectCode', secretLabel: 'signKey', callbackLabel: '发货结果通知地址',
    signature: '非空参数按 ASCII 升序拼接，追加 signKey 后计算 SHA1 大写。', note: '协议不提供商品列表，需先建立 SKU 映射；卡密和直充共用发货订单入口。', capabilities: mappedCapabilities,
    endpoints: [
      { name: '余额查询', method: 'POST', path: '/fzs-stdopen-api/api/v1/balance' }, { name: '提交发货', method: 'POST', path: '/fzs-stdopen-api/api/v1/sendgoods' },
      { name: '发货查询', method: 'POST', path: '/fzs-stdopen-api/api/v1/queryorder' }
    ]
  },
  {
    id: 'CHENGQUAN', name: '鼎信橙券兼容协议', shortName: '鼎信橙券', identityLabel: 'app_id', secretLabel: '商户密钥 key', callbackLabel: '订单通知地址',
    signature: '非空参数按字典序拼接并追加商户密钥，计算 MD5 大写。', note: '优惠券卡密与直充订单按商品交付类型自动路由。', capabilities: fullCapabilities,
    endpoints: [
      { name: '账户余额', method: 'POST', path: '/user/balance/get' }, { name: '品牌列表', method: 'POST', path: '/coupon/type/list' },
      { name: '商品列表', method: 'POST', path: '/coupon/type/goods/list' }, { name: '创建订单', method: 'POST', path: '/order/directCharge' },
      { name: '订单查询', method: 'POST', path: '/order/get' }
    ]
  },
  {
    id: 'FANCHEN', name: '浙江梵尘兼容协议', shortName: '浙江梵尘', identityLabel: 'userid', secretLabel: '商户 KEY', callbackLabel: '订单回调地址',
    signature: '按接口指定顺序拼接参数，使用 GBK 字符集计算 MD5 大写。', note: '商品价格按 productid 映射，卡密和直充统一进入订单履约流程。', capabilities: mappedCapabilities,
    endpoints: [
      { name: '余额查询', method: 'POST', path: '/fcsearchbalance.do' }, { name: '商品价格', method: 'POST', path: '/fcuserproductprice.do' },
      { name: '创建订单', method: 'POST', path: '/fcgameonlinepay.do' }, { name: '订单查询', method: 'POST', path: '/fcsearchpay.do' }
    ]
  },
  {
    id: 'JINGZHAO', name: '京兆云兼容协议', shortName: '京兆云', identityLabel: 'customer_id', secretLabel: '商户密钥 key', callbackLabel: '订单状态通知地址',
    signature: 'MD5(key + 参数名和值按字典序拼接)，输出 32 位小写。', note: '商品列表、充值参数、卡密交付和直充查单均使用京兆云字段结构。', capabilities: fullCapabilities,
    endpoints: [
      { name: '商户信息', method: 'POST', path: '/api/customer' }, { name: '商品列表', method: 'POST', path: '/api/product-list' },
      { name: '购买商品', method: 'POST', path: '/api/buy' }, { name: '订单查询', method: 'POST', path: '/api/outer-order' }
    ]
  }
]

const globalDraft = reactive({ enabled: false, baseUrl: '', requestLimit: 120, timeoutSeconds: 30 })
const protocolDrafts = reactive(Object.fromEntries(protocols.map((protocol) => [protocol.id, { enabled: false }])) as Record<ProtocolId, ProtocolDraft>)
const commerceDraft = reactive({ exposureMode: 'ALL', categoryIds: [] as number[], goodsIds: [] as number[], deliveryTypes: ['CARD', 'DIRECT'] })
const selectedProtocolId = ref<ProtocolId>('KASUSHOU')
const logProtocol = ref<'ALL' | ProtocolId>('ALL')
const logStatus = ref<'ALL' | 'SUCCESS' | 'FAILED'>('ALL')
const invocationLogs = ref<InvocationLog[]>([])
const credentials = ref<MemberApiCredential[]>([])
const categories = ref<Category[]>([])
const goods = ref<Goods[]>([])
const loading = ref(false)
const saving = ref(false)
const router = useRouter()

const currentProtocol = computed(() => protocols.find((item) => item.id === selectedProtocolId.value) || protocols[0])
const currentDraft = computed(() => protocolDrafts[selectedProtocolId.value])
const publicBaseUrl = computed(() => globalDraft.baseUrl.trim().replace(/\/$/, '') || 'https://你的喜易云域名')
const filteredLogs = computed(() => invocationLogs.value.filter((item) => (logProtocol.value === 'ALL' || item.protocol === logProtocol.value) && (logStatus.value === 'ALL' || item.status === logStatus.value)))
const enabledCredentialCount = computed(() => credentials.value.filter((item) => item.status === 'ENABLED').length)

onMounted(() => { void loadPage() })

function selectProtocol(id: ProtocolId) { selectedProtocolId.value = id }
function capabilityLabel(state: CapabilityState) { return state === 'supported' ? '支持' : '商品映射' }
function endpointUrl(path: string) { return path.startsWith('/') ? `${publicBaseUrl.value}${path}` : path }
function protocolName(id: ProtocolId | 'UNKNOWN') { return protocols.find((item) => item.id === id)?.shortName || '其他接口' }

async function loadPage() {
  loading.value = true
  try {
    const [settings, memberCredentials, logs, categoryItems, goodsItems] = await Promise.all([
      fetchOutboundProtocolSettings(),
      fetchMemberApiCredentials(),
      fetchOpenApiLogsPage({ page: 1, pageSize: 100 }),
      fetchCategories(),
      fetchGoods({ page: 1, pageSize: 500 })
    ])
    globalDraft.enabled = settings.enabled
    globalDraft.baseUrl = settings.baseUrl
    globalDraft.requestLimit = settings.requestLimitPerMinute
    globalDraft.timeoutSeconds = settings.timeoutSeconds
    protocols.forEach((protocol) => { protocolDrafts[protocol.id].enabled = Boolean(settings.protocols[protocol.id]) })
    commerceDraft.exposureMode = settings.exposureMode
    commerceDraft.categoryIds = settings.categoryIds
    commerceDraft.goodsIds = settings.goodsIds
    commerceDraft.deliveryTypes = [...settings.deliveryTypes]
    credentials.value = memberCredentials
    invocationLogs.value = logs.items.map((item) => ({ ...item, protocol: protocolFromPath(item.path || '') }))
    categories.value = categoryItems
    goods.value = goodsItems
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '对外供货配置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  saving.value = true
  try {
    await saveOutboundProtocolSettings({
      enabled: globalDraft.enabled,
      baseUrl: globalDraft.baseUrl,
      requestLimitPerMinute: globalDraft.requestLimit,
      timeoutSeconds: globalDraft.timeoutSeconds,
      protocols: Object.fromEntries(protocols.map((protocol) => [protocol.id, protocolDrafts[protocol.id].enabled])) as Record<ProtocolId, boolean>,
      exposureMode: commerceDraft.exposureMode as 'ALL' | 'CATEGORY' | 'SELECTED',
      categoryIds: commerceDraft.categoryIds,
      goodsIds: commerceDraft.goodsIds,
      deliveryTypes: commerceDraft.deliveryTypes as Array<'CARD' | 'DIRECT'>,
      pricePolicy: 'MEMBER_GROUP',
      priceAdjustment: 0,
      includeDisabledGoods: false
    })
    ElMessage.success('对外供货协议配置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '配置保存失败')
  } finally {
    saving.value = false
  }
}

function protocolFromPath(path: string): ProtocolId | 'UNKNOWN' {
  if (path.startsWith('/api/v1/')) return 'KASUSHOU'
  if (path.startsWith('/dockapiv3/')) return 'KAKAYUN'
  if (path === '/api/rechargeapi/gateway') return 'FULU'
  if (path.startsWith('/fzs-stdopen-api/')) return 'FENGZHUSHOU'
  if (path.startsWith('/coupon/') || path.startsWith('/user/balance/') || path.startsWith('/order/directCharge')) return 'CHENGQUAN'
  if (path.startsWith('/fc')) return 'FANCHEN'
  if (path.startsWith('/api/customer') || path.startsWith('/api/product-list') || path.startsWith('/api/buy') || path.startsWith('/api/outer-order')) return 'JINGZHAO'
  return 'UNKNOWN'
}

async function copyText(value: string, label: string) {
  try { await navigator.clipboard.writeText(value); ElMessage.success(`${label}已复制`) }
  catch { ElMessage.warning('复制失败，请手动复制') }
}
</script>

<template>
  <main class="outbound-page" v-loading="loading">
    <header class="page-intro">
      <div>
        <span class="eyebrow"><ServerCog :size="15" /> 对外供货协议中心</span>
        <h1>让喜易云兼容主流供货协议</h1>
        <p>统一开放卡密与直充能力，并按合作方现有协议完成鉴权、商品、下单和查单适配。</p>
      </div>
      <div class="draft-state">
        <span class="state-dot" />
        <div><strong>{{ globalDraft.enabled ? '对外供货已启用' : '对外供货已停用' }}</strong><small>配置与调用日志均由后端持久化</small></div>
      </div>
    </header>

    <section class="global-bar liquid-admin-panel">
      <div class="global-title">
        <Globe2 :size="20" />
        <div><strong>公共接入设置</strong><span>七套兼容协议共用的域名、限流与超时策略</span></div>
      </div>
      <label class="compact-field base-field">
        <span>公共 API Base URL</span>
        <el-input v-model="globalDraft.baseUrl" placeholder="https://api.example.com">
          <template #append><el-button :icon="ClipboardCopy" aria-label="复制公共 API 地址" @click="copyText(publicBaseUrl, '公共 API 地址')" /></template>
        </el-input>
      </label>
      <label class="compact-field number-field">
        <span>每分钟限流</span>
        <el-input-number v-model="globalDraft.requestLimit" :min="1" :max="10000" controls-position="right" />
      </label>
      <label class="compact-field number-field timeout-field">
        <span>超时（秒）</span>
        <el-input-number v-model="globalDraft.timeoutSeconds" :min="5" :max="120" controls-position="right" />
      </label>
      <div class="global-switch"><span>总开关</span><el-switch v-model="globalDraft.enabled" active-text="启用" inactive-text="停用" /></div>
    </section>

    <section class="workspace-grid">
      <aside class="protocol-panel liquid-admin-panel">
        <div class="panel-head">
          <div><h2>兼容协议</h2><span>配置独立凭据和回调</span></div><Layers3 :size="19" />
        </div>
        <nav class="protocol-list" aria-label="对外供货协议">
          <button v-for="protocol in protocols" :key="protocol.id" type="button" class="protocol-item" :class="{ active: protocol.id === selectedProtocolId }" @click="selectProtocol(protocol.id)">
            <span class="protocol-monogram">{{ protocol.shortName.slice(0, 1) }}</span>
            <span class="protocol-copy"><strong>{{ protocol.shortName }}</strong><small>{{ protocolDrafts[protocol.id].enabled ? '已启用' : '已停用' }}</small></span>
            <span class="protocol-status" :data-enabled="protocolDrafts[protocol.id].enabled">{{ protocolDrafts[protocol.id].enabled ? 'ON' : 'OFF' }}</span>
          </button>
        </nav>
      </aside>

      <section class="config-panel liquid-admin-panel">
        <div class="panel-head config-head">
          <div><span class="protocol-kicker">{{ currentProtocol.shortName }}</span><h2>{{ currentProtocol.name }}</h2><p>{{ currentProtocol.note }}</p></div>
          <el-switch v-model="currentDraft.enabled" active-text="启用" inactive-text="停用" />
        </div>
        <div class="credential-section">
          <div class="section-label"><KeyRound :size="17" /><strong>商户身份映射</strong><span>每个会员使用独立凭据、白名单和日限额</span></div>
          <div class="credential-summary">
            <div><span>{{ currentProtocol.identityLabel }}</span><strong>会员 API AppKey</strong></div>
            <div><span>{{ currentProtocol.secretLabel }}</span><strong>会员 API AppSecret</strong></div>
            <div><span>已启用商户</span><strong>{{ enabledCredentialCount }} 个</strong></div>
            <el-button :icon="Users" @click="router.push({ name: 'users' })">管理商户凭据</el-button>
          </div>
        </div>
        <div class="signature-strip"><ShieldCheck :size="17" /><div><strong>签名规则</strong><span>{{ currentProtocol.signature }}</span></div></div>
        <div class="config-actions"><span>协议关闭后对应外部入口会立即拒绝请求。</span><el-button type="primary" :icon="Save" :loading="saving" @click="saveSettings">保存配置</el-button></div>
      </section>
    </section>

    <section class="capability-panel liquid-admin-panel">
      <div class="panel-head"><div><h2>协议能力矩阵</h2><span>全部协议覆盖卡密和直充；无原生商品列表的平台通过商品映射开放</span></div><ShieldCheck :size="19" /></div>
      <div class="matrix-scroll">
        <table class="capability-table">
          <thead><tr><th>协议</th><th>余额</th><th>商品列表</th><th>卡密发货</th><th>直充</th><th>订单查询</th></tr></thead>
          <tbody>
            <tr v-for="protocol in protocols" :key="protocol.id" :class="{ current: protocol.id === selectedProtocolId }">
              <td><button type="button" @click="selectProtocol(protocol.id)">{{ protocol.shortName }}</button></td>
              <td v-for="key in ['balance', 'goods', 'card', 'direct', 'order'] as const" :key="key">
                <span class="capability-state" :data-state="protocol.capabilities[key]"><Check :size="14" />{{ capabilityLabel(protocol.capabilities[key]) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="details-grid">
      <article class="policy-panel liquid-admin-panel">
        <div class="panel-head"><div><h2>商品开放与结算</h2><span>控制对合作方可见的货源和会员结算价</span></div><SlidersHorizontal :size="19" /></div>
        <el-form :model="commerceDraft" label-position="top" class="policy-form">
          <el-form-item label="商品开放范围">
            <el-radio-group v-model="commerceDraft.exposureMode"><el-radio-button value="ALL">全部上架商品</el-radio-button><el-radio-button value="CATEGORY">指定分类</el-radio-button><el-radio-button value="SELECTED">指定商品</el-radio-button></el-radio-group>
            <el-select v-if="commerceDraft.exposureMode === 'CATEGORY'" v-model="commerceDraft.categoryIds" multiple filterable collapse-tags placeholder="选择允许对外开放的分类" class="scope-select">
              <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="Number(category.id)" />
            </el-select>
            <el-select v-if="commerceDraft.exposureMode === 'SELECTED'" v-model="commerceDraft.goodsIds" multiple filterable collapse-tags placeholder="选择允许对外开放的商品" class="scope-select">
              <el-option v-for="item in goods" :key="item.id" :label="item.name" :value="Number(item.id)" />
            </el-select>
          </el-form-item>
          <el-form-item label="交付类型">
            <el-checkbox-group v-model="commerceDraft.deliveryTypes"><el-checkbox-button value="CARD"><PackageOpen :size="15" /> 卡密</el-checkbox-button><el-checkbox-button value="DIRECT"><Truck :size="15" /> 直充</el-checkbox-button></el-checkbox-group>
          </el-form-item>
          <div class="settlement-note"><strong>结算价格</strong><span>按会员 API 所属用户组价格计算，接口报价与订单实扣保持一致。</span></div>
        </el-form>
      </article>

      <article class="docs-panel liquid-admin-panel">
        <div class="panel-head"><div><h2>{{ currentProtocol.shortName }}接口文档</h2><span>当前协议对外暴露的兼容路径</span></div><BookOpen :size="19" /></div>
        <div class="endpoint-list">
          <div v-for="endpoint in currentProtocol.endpoints" :key="`${endpoint.method}:${endpoint.path}`" class="endpoint-row">
            <span class="method-badge" :data-method="endpoint.method">{{ endpoint.method }}</span>
            <div><strong>{{ endpoint.name }}</strong><code>{{ endpointUrl(endpoint.path) }}</code></div>
            <el-button text :icon="ClipboardCopy" :aria-label="`复制${endpoint.name}路径`" @click="copyText(endpointUrl(endpoint.path), `${endpoint.name}路径`)" />
          </div>
        </div>
        <div class="docs-note"><Code2 :size="17" /><span>字段映射、签名示例和响应码将在后端协议转换器接入后生成正式文档。</span></div>
      </article>
    </section>

    <section class="logs-panel liquid-admin-panel">
      <div class="panel-head log-head">
        <div><h2>最近调用</h2><span>展示真实请求结果、路径和错误信息</span></div>
        <div class="log-filters">
          <el-select v-model="logProtocol" aria-label="按协议筛选"><el-option label="全部协议" value="ALL" /><el-option v-for="protocol in protocols" :key="protocol.id" :label="protocol.shortName" :value="protocol.id" /></el-select>
          <el-select v-model="logStatus" aria-label="按调用状态筛选"><el-option label="全部状态" value="ALL" /><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILED" /></el-select>
        </div>
      </div>
      <el-table :data="filteredLogs" style="width: 100%" min-height="190">
        <el-table-column label="协议" width="150"><template #default="{ row }">{{ protocolName(row.protocol) }}</template></el-table-column>
        <el-table-column prop="path" label="接口" min-width="260" /><el-table-column prop="status" label="状态" width="120" /><el-table-column prop="message" label="结果" min-width="180" /><el-table-column prop="createdAt" label="调用时间" width="190" />
        <template #empty><div class="logs-empty"><Activity :size="30" /><strong>暂无调用记录</strong><span>下游平台首次请求后会显示在这里。</span></div></template>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.outbound-page { display: grid; gap: 16px; color: rgba(244, 249, 255, .92); }
.page-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 4px 2px 8px; }
.eyebrow, .section-label, .signature-strip, .docs-note { display: flex; align-items: center; gap: 8px; }
.eyebrow { color: #8df7e0; font-size: 12px; font-weight: 800; text-transform: uppercase; }
.page-intro h1 { margin: 8px 0 6px; font-size: 28px; line-height: 1.2; }
.page-intro p, .config-head p { margin: 0; color: rgba(220, 234, 248, .58); font-size: 13px; line-height: 1.7; }
.draft-state { display: flex; align-items: center; gap: 10px; min-width: 275px; padding: 10px 12px; border: .5px solid rgba(255, 184, 77, .2); border-radius: 10px; background: rgba(255, 184, 77, .07); }
.draft-state div { display: grid; gap: 2px; }
.draft-state strong { color: rgba(255, 240, 213, .94); font-size: 13px; }
.draft-state small { color: rgba(255, 228, 181, .58); }
.state-dot { width: 8px; height: 8px; border-radius: 50%; background: #ffb84d; box-shadow: 0 0 14px rgba(255, 184, 77, .55); }
.global-bar, .protocol-panel, .config-panel, .capability-panel, .policy-panel, .docs-panel, .logs-panel { overflow: hidden; border-radius: 18px; }
.global-bar { display: grid; grid-template-columns: minmax(230px, 1.1fr) minmax(280px, 1.5fr) 132px 120px 126px; gap: 16px; align-items: end; padding: 15px 18px; }
.global-title { display: flex; align-items: center; align-self: center; gap: 10px; }
.global-title svg, .panel-head > svg, .section-label svg, .signature-strip svg, .docs-note svg { color: #00ffc3; }
.global-title div, .compact-field, .global-switch { display: grid; gap: 5px; }
.global-title strong, .compact-field > span, .global-switch > span { font-size: 13px; font-weight: 700; }
.global-title span, .panel-head span, .compact-field > span, .global-switch > span { color: rgba(220, 234, 248, .5); font-size: 12px; }
.number-field :deep(.el-input-number), .policy-form :deep(.el-input-number), .policy-form :deep(.el-select) { width: 100%; }
.workspace-grid { display: grid; grid-template-columns: 268px minmax(0, 1fr); gap: 16px; }
.protocol-panel, .config-panel, .capability-panel, .policy-panel, .docs-panel, .logs-panel { padding: 18px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.panel-head h2 { margin: 0 0 3px; font-size: 17px; }
.panel-head > div:first-child { min-width: 0; }
.protocol-list { display: grid; gap: 6px; }
.protocol-item { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; gap: 10px; align-items: center; width: 100%; min-height: 52px; padding: 8px 10px; border: .5px solid transparent; border-radius: 10px; color: rgba(230, 240, 251, .7); text-align: left; background: transparent; cursor: pointer; transition: border-color 160ms ease, background 160ms ease, color 160ms ease; }
.protocol-item:hover { border-color: rgba(122, 204, 255, .2); color: rgba(244, 249, 255, .94); background: rgba(255, 255, 255, .05); }
.protocol-item.active { border-color: rgba(0, 255, 195, .28); color: rgba(244, 255, 252, .96); background: linear-gradient(90deg, rgba(0, 255, 195, .13), rgba(88, 166, 255, .06)); }
.protocol-monogram { display: grid; place-items: center; width: 34px; height: 34px; border: .5px solid rgba(122, 204, 255, .2); border-radius: 9px; color: #9dfbe6; background: rgba(88, 166, 255, .09); font-size: 14px; font-weight: 800; }
.protocol-copy { display: grid; gap: 2px; min-width: 0; }
.protocol-copy strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.protocol-copy small { color: rgba(210, 225, 242, .42); }
.protocol-status { color: rgba(210, 225, 242, .38); font-size: 10px; font-weight: 800; }
.protocol-status[data-enabled="true"] { color: #00ffc3; }
.config-head { align-items: flex-start; padding-bottom: 15px; border-bottom: .5px solid rgba(255, 255, 255, .08); }
.config-head h2 { margin: 3px 0 5px; font-size: 20px; }
.protocol-kicker { color: #58a6ff !important; font-weight: 800; }
.credential-section { padding: 2px 0 4px; }
.section-label { margin-bottom: 12px; }
.section-label span { color: rgba(220, 234, 248, .46); font-size: 12px; }
.credential-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: 10px; align-items: end; }
.credential-summary > div { display: grid; gap: 4px; min-height: 58px; padding: 10px 12px; border: .5px solid rgba(183, 215, 244, .12); border-radius: 10px; background: rgba(255, 255, 255, .035); }
.credential-summary span { color: rgba(220, 234, 248, .46); font-size: 11px; }
.credential-summary strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.signature-strip { padding: 11px 12px; border: .5px solid rgba(0, 255, 195, .14); border-radius: 10px; background: rgba(0, 255, 195, .045); }
.signature-strip div { display: grid; gap: 2px; }
.signature-strip strong { font-size: 12px; }
.signature-strip span { color: rgba(220, 234, 248, .58); font-size: 12px; }
.config-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 16px; }
.config-actions > span { color: rgba(220, 234, 248, .42); font-size: 12px; }
.matrix-scroll { overflow-x: auto; }
.capability-table { width: 100%; min-width: 780px; border-collapse: collapse; }
.capability-table th, .capability-table td { padding: 11px 14px; border-bottom: .5px solid rgba(255, 255, 255, .07); color: rgba(220, 234, 248, .62); font-size: 12px; text-align: left; }
.capability-table th { color: rgba(220, 234, 248, .42); font-weight: 700; background: rgba(255, 255, 255, .025); }
.capability-table tr.current td { background: rgba(0, 255, 195, .035); }
.capability-table td button { padding: 0; border: 0; color: rgba(244, 249, 255, .9); background: transparent; font-weight: 700; cursor: pointer; }
.capability-state { display: inline-flex; align-items: center; gap: 5px; color: #8df7e0; }
.capability-state[data-state="mapped"] { color: #ffca72; }
.details-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 16px; }
.policy-form :deep(.el-radio-group), .policy-form :deep(.el-checkbox-group) { display: flex; flex-wrap: wrap; gap: 7px; }
.policy-form :deep(.el-radio-button), .policy-form :deep(.el-checkbox-button) { margin: 0; }
.policy-form :deep(.el-radio-button__inner), .policy-form :deep(.el-checkbox-button__inner) { display: inline-flex; align-items: center; gap: 6px; border: .5px solid rgba(183, 215, 244, .14) !important; border-radius: 9px !important; color: rgba(220, 234, 248, .64); background: rgba(255, 255, 255, .045); box-shadow: none !important; }
.policy-form :deep(.is-active .el-radio-button__inner), .policy-form :deep(.is-checked .el-checkbox-button__inner) { color: #dffff7; background: rgba(0, 255, 195, .13); border-color: rgba(0, 255, 195, .34) !important; }
.field-help { width: 100%; margin: 7px 0 0; color: #ffca72; font-size: 12px; }
.scope-select { width: 100%; margin-top: 10px; }
.settlement-note { display: grid; gap: 4px; padding: 11px 12px; border: .5px solid rgba(183, 215, 244, .12); border-radius: 10px; background: rgba(255, 255, 255, .035); }
.settlement-note strong { font-size: 12px; }
.settlement-note span { color: rgba(220, 234, 248, .5); font-size: 12px; }
.endpoint-list { display: grid; }
.endpoint-row { display: grid; grid-template-columns: 52px minmax(0, 1fr) 34px; gap: 10px; align-items: center; padding: 10px 0; border-bottom: .5px solid rgba(255, 255, 255, .07); }
.endpoint-row > div { display: grid; gap: 3px; min-width: 0; }
.endpoint-row strong { font-size: 12px; }
.endpoint-row code { overflow: hidden; color: rgba(174, 206, 238, .56); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.method-badge { display: inline-flex; justify-content: center; padding: 4px 5px; border-radius: 6px; color: #a8d8ff; background: rgba(88, 166, 255, .1); font-size: 10px; font-weight: 800; }
.method-badge[data-method="POST"] { color: #9dfbe6; background: rgba(0, 255, 195, .09); }
.docs-note { margin-top: 12px; color: rgba(220, 234, 248, .48); font-size: 12px; }
.log-head { align-items: flex-end; }
.log-filters { display: grid; grid-template-columns: 150px 130px; gap: 8px; }
.logs-empty { display: grid; place-items: center; gap: 6px; padding: 34px 0; color: rgba(220, 234, 248, .4); }
.logs-empty svg { color: rgba(88, 166, 255, .5); }
.logs-empty strong { color: rgba(220, 234, 248, .7); font-size: 13px; }
.logs-empty span { font-size: 12px; }

@media (max-width: 1480px) {
  .global-bar { grid-template-columns: minmax(220px, 1fr) minmax(260px, 1.6fr) 120px 126px; }
  .timeout-field { display: none; }
}
@media (max-width: 1180px) {
  .page-intro, .config-actions, .log-head { align-items: stretch; flex-direction: column; }
  .draft-state { min-width: 0; }
  .global-bar, .workspace-grid, .details-grid { grid-template-columns: 1fr; }
  .timeout-field { display: grid; }
  .protocol-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .credential-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .log-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
