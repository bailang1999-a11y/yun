<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit3, KeyRound, PackageCheck, PlugZap, Plus, RefreshCw, ShieldCheck, Trash2, WalletCards, X } from 'lucide-vue-next'
import {
  createGoodsChannel,
  fetchGoods
} from '../api/goods'
import {
  createSupplier,
  deleteSupplier,
  fetchSuppliers,
  refreshSupplierBalance,
  syncSupplierGoods,
  updateSupplier
} from '../api/suppliers'
import type { Goods, RemoteGoods, RemoteGoodsSyncResult, Supplier, SupplierCreatePayload } from '../types/operations'
import { formatDateTime, formatMoney } from '../utils/formatters'

const KASUSHOU_BASE_URL = 'https://你的卡速售域名'
const KAKAYUN_BASE_URL = 'http://public.kky.v3.api.kakayun.vip'
const KAKAYUN_TEST_BASE_URL = 'http://qqlogin.yxp8.cn'
const FULU_BASE_URL = 'https://ooms-shop-service.fulumedia.cn/api/rechargeapi/gateway'
const FULU_SANDBOX_BASE_URL = 'https://it-ooms-shop-service.fulumovie.com/api/rechargeapi/gateway'
const FENGZHUSHOU_BASE_URL = 'https://orderapi.phone580.com'
const FENGZHUSHOU_TEST_BASE_URL = 'http://test-www.phone580.net:9000'
const CHENGQUAN_BASE_URL = 'https://api.chengquan.cn'
const FANCHEN_BASE_URL = 'https://www.fanchenrj.cn'
const JINGZHAO_BASE_URL = 'http://jingzhao.xhygo.cn'

const platformOptions = [
  { label: '自定义供应商', value: 'CUSTOM' },
  { label: '卡速售 2.0', value: 'KASUSHOU_2' },
  { label: '卡卡云', value: 'KAKAYUN' },
  { label: '福禄新平台', value: 'FULU' },
  { label: '蜂助手直充', value: 'FENGZHUSHOU' },
  { label: '鼎信橙券', value: 'CHENGQUAN' },
  { label: '浙江梵尘', value: 'FANCHEN_RJ' },
  { label: '京兆云', value: 'JINGZHAO' }
] as const

const platformDefaults: Record<string, { name: string; baseUrl: string }> = {
  CUSTOM: { name: '', baseUrl: '' },
  KASUSHOU_2: { name: '卡速售 2.0', baseUrl: KASUSHOU_BASE_URL },
  KAKAYUN: { name: '卡卡云', baseUrl: KAKAYUN_BASE_URL },
  FULU: { name: '福禄新平台', baseUrl: FULU_BASE_URL },
  FENGZHUSHOU: { name: '蜂助手直充', baseUrl: FENGZHUSHOU_BASE_URL },
  CHENGQUAN: { name: '鼎信橙券', baseUrl: CHENGQUAN_BASE_URL },
  FANCHEN_RJ: { name: '浙江梵尘', baseUrl: FANCHEN_BASE_URL },
  JINGZHAO: { name: '京兆云', baseUrl: JINGZHAO_BASE_URL }
}

const knownDefaultNames = new Set(Object.values(platformDefaults).map((item) => item.name).filter(Boolean))
const knownDefaultBaseUrls = new Set(Object.values(platformDefaults).map((item) => item.baseUrl).filter(Boolean))

const kasushouCapabilityGroups = [
  { group: '基础', items: ['余额查询'] },
  { group: '商品', items: ['商品分类', '商品列表', '商品详情', '商品调价记录', '商品变更通知', '商品下单模板'] },
  { group: '订单', items: ['订单提交', '订单详情', '订单异步回调', '订单撤单'] },
  { group: '售后', items: ['售后申请', '售后处理回调'] },
  { group: '回调', items: ['商品变更通知', '订单异步回调', '售后处理回调'] }
]

const kakayunCapabilityGroups = [
  { group: '基础', items: ['余额查询', '接口连通性'] },
  { group: '商品', items: ['商品分组', '商品列表', '商品详情', '充值模板', '商品订阅'] },
  { group: '订单', items: ['订单提交', '订单查询', '卡密回传', '状态刷新'] },
  { group: '回调', items: ['订单回调', '商品推送地址'] }
]

const fuluCapabilityGroups = [
  { group: '基础', items: ['余额查询', '接口连通性'] },
  { group: '商品', items: ['手动绑定 product_id', '不支持商品列表', '不支持实时价格库存'] },
  { group: '订单', items: ['订单提交', '订单查询', '状态刷新'] },
  { group: '回调', items: ['订单异步回调'] }
]

const fengzhushouCapabilityGroups = [
  { group: '基础', items: ['接口签名校验', '超时按处理中'] },
  { group: '商品', items: ['手动绑定 skuCode', '不支持商品列表', '不支持实时价格库存'] },
  { group: '订单', items: ['异步发货', '查询发货结果', '状态刷新'] },
  { group: '回调', items: ['发货结果通知', '失败回调重试'] }
]

const chengquanCapabilityGroups = [
  { group: '基础', items: ['余额查询', 'MD5 签名', '接口连通性'] },
  { group: '商品', items: ['品牌列表', '品牌产品', '库存字段', '商品同步'] },
  { group: '订单', items: ['直充下单', '订单查询', '状态刷新'] },
  { group: '回调', items: ['订单通知', '返回 OK 确认'] }
]

const fanchenCapabilityGroups = [
  { group: '基础', items: ['余额查询', 'GBK 表单请求', '固定顺序 MD5 签名'] },
  { group: '商品', items: ['商品价格查询', '分类字段', '价格同步'] },
  { group: '订单', items: ['权益/游戏直充', '订单查询', '状态刷新'] },
  { group: '回调', items: ['订单回调', '返回 OK 确认'] }
]

const jingzhaoCapabilityGroups = [
  { group: '基础', items: ['商家余额', 'MD5 小写签名', '接口连通性'] },
  { group: '商品', items: ['商品列表', '商品详情', '充值参数', '商品订阅'] },
  { group: '订单', items: ['购买商品', '订单查询', '外部订单查询', '状态刷新'] },
  { group: '回调', items: ['订单状态通知', '商品信息订阅'] }
]

const suppliers = ref<Supplier[]>([])
const goods = ref<Goods[]>([])
const loading = ref(false)
const saving = ref(false)
const operatingId = ref('')
const editingSupplierId = ref<Supplier['id'] | ''>('')
const syncingId = ref<Supplier['id'] | ''>('')
const bindingKey = ref('')
const syncDialogVisible = ref(false)
const syncSupplierName = ref('')
const syncSupplierId = ref<Supplier['id'] | ''>('')
const syncResult = ref<RemoteGoodsSyncResult | null>(null)
const bindingGoodsId = ref<Goods['id'] | ''>('')
const bindForm = reactive({
  priority: 10,
  timeoutSeconds: 30,
  status: 'ENABLED'
})

const syncGoods = computed(() => syncResult.value?.goods ?? [])
const directGoods = computed(() => goods.value.filter((item) => item.deliveryType === 'DIRECT'))

const form = reactive<SupplierCreatePayload>({
  name: '',
  baseUrl: '',
  appKey: '',
  appSecret: '',
  platformType: 'CUSTOM',
  userId: '',
  appId: '',
  apiKey: '',
  callbackUrl: '',
  timeoutSeconds: 15,
  balance: 0,
  status: 'ENABLED',
  remark: ''
})

const isKasushouSelected = computed(() => form.platformType === 'KASUSHOU_2')
const isKakayunSelected = computed(() => form.platformType === 'KAKAYUN')
const isFuluSelected = computed(() => form.platformType === 'FULU')
const isFengzhushouSelected = computed(() => form.platformType === 'FENGZHUSHOU')
const isChengquanSelected = computed(() => form.platformType === 'CHENGQUAN')
const isFanchenSelected = computed(() => form.platformType === 'FANCHEN_RJ')
const isJingzhaoSelected = computed(() => form.platformType === 'JINGZHAO')
const isIntegratedSelected = computed(() => isKasushouSelected.value || isKakayunSelected.value || isFuluSelected.value || isFengzhushouSelected.value || isChengquanSelected.value || isFanchenSelected.value || isJingzhaoSelected.value)
const currentCapabilityGroups = computed(() => {
  if (isJingzhaoSelected.value) return jingzhaoCapabilityGroups
  if (isFanchenSelected.value) return fanchenCapabilityGroups
  if (isChengquanSelected.value) return chengquanCapabilityGroups
  if (isFengzhushouSelected.value) return fengzhushouCapabilityGroups
  if (isFuluSelected.value) return fuluCapabilityGroups
  return isKakayunSelected.value ? kakayunCapabilityGroups : kasushouCapabilityGroups
})
const baseUrlLabel = computed(() => {
  if (isKasushouSelected.value) return '卡速售 API 对接地址'
  if (isKakayunSelected.value) return '卡卡云 API 对接地址'
  if (isFuluSelected.value) return '福禄新平台网关地址'
  if (isFengzhushouSelected.value) return '蜂助手 API 对接地址'
  if (isChengquanSelected.value) return '鼎信橙券 API 对接地址'
  if (isFanchenSelected.value) return '浙江梵尘 API 对接地址'
  if (isJingzhaoSelected.value) return '京兆云 API 对接地址'
  return 'API 地址'
})
const baseUrlPlaceholder = computed(() => {
  if (isKasushouSelected.value) return KASUSHOU_BASE_URL
  if (isKakayunSelected.value) return KAKAYUN_BASE_URL
  if (isFuluSelected.value) return FULU_BASE_URL
  if (isFengzhushouSelected.value) return FENGZHUSHOU_BASE_URL
  if (isChengquanSelected.value) return CHENGQUAN_BASE_URL
  if (isFanchenSelected.value) return FANCHEN_BASE_URL
  if (isJingzhaoSelected.value) return JINGZHAO_BASE_URL
  return '请输入供应商 API 地址'
})
const integrationTitle = computed(() => {
  if (isChengquanSelected.value) return '鼎信橙券接口能力'
  if (isFanchenSelected.value) return '浙江梵尘接口能力'
  if (isJingzhaoSelected.value) return '京兆云接口能力'
  if (isFengzhushouSelected.value) return '蜂助手直充接口能力'
  if (isFuluSelected.value) return '福禄新平台接口能力'
  return isKakayunSelected.value ? '卡卡云接口能力' : '卡速售接口能力'
})
const signatureNote = computed(() => {
  if (isChengquanSelected.value) return 'Sign = 非空参数按字典序拼接 key=value&...&key=商户密钥，再 MD5 大写'
  if (isFanchenSelected.value) return 'Sign = 按每个接口指定顺序拼接参数，使用 GBK 字符集 MD5 大写'
  if (isJingzhaoSelected.value) return 'Sign = MD5(key + 参数名和值按字典序拼接)，32 位小写'
  if (isFengzhushouSelected.value) return 'Sign = 非空参数按 ASCII 升序拼接 key=value&...&signKey=密钥，再 SHA1 大写'
  if (isFuluSelected.value) return 'Sign = 请求参数 JSON 字符排序后追加 APP_SECRET，再 MD5 小写'
  if (isKakayunSelected.value) return 'Sign = MD5(按字段 ASCII 排序拼接 key=value&... 后追加商户 KEY)'
  return 'Sign = sha1(Timestamp + 排序后的 JSON Body + 密钥)'
})
const headerNote = computed(() => {
  if (isChengquanSelected.value) return 'POST JSON Body 包含 app_id / timestamp / sign，成功码为 7000'
  if (isFanchenSelected.value) return 'POST 表单 application/x-www-form-urlencoded; charset=GBK，返回 JSON'
  if (isJingzhaoSelected.value) return 'POST Body 包含 customer_id / timestamp / sign，成功码为 ok'
  if (isFengzhushouSelected.value) return 'POST JSON Body 包含 projectCode / timestamp / sign，timestamp 为 13 位毫秒'
  if (isFuluSelected.value) return '统一网关 JSON 请求体包含 app_key / method / timestamp / biz_content / sign'
  return isKakayunSelected.value ? '请求体包含 userid / timestamp / sign' : 'Headers 使用 Sign / Timestamp / APPID'
})
const formTitle = computed(() => (editingSupplierId.value ? '编辑供应商' : '新增供应商'))
const formSubtitle = computed(() => (editingSupplierId.value ? '更新渠道参数和密钥' : '用于直充渠道、余额预警和自动切换'))

onMounted(() => {
  void loadSuppliers()
  void loadBindingGoods()
})

watch(
  () => form.platformType,
  (platformType, previousPlatformType) => {
    applyPlatformDefaults(platformType, previousPlatformType)
    if (!form.timeoutSeconds) form.timeoutSeconds = 15
  }
)

function formatTime(value?: string) {
  return formatDateTime(value)
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function loadSuppliers() {
  loading.value = true
  try {
    suppliers.value = await fetchSuppliers()
  } catch {
    ElMessage.error('供应商列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadBindingGoods() {
  try {
    goods.value = await fetchGoods()
    if (!bindingGoodsId.value) bindingGoodsId.value = directGoods.value[0]?.id ?? ''
  } catch {
    ElMessage.error('本地商品列表加载失败')
  }
}

function isKasushouSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('KASUSHOU') || signature.includes('KASU') || signature.includes('卡速售')
}

function isKakayunSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('KAKAYUN') || signature.includes('KKY') || signature.includes('卡卡云')
}

function isFuluSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('FULU') || signature.includes('福禄')
}

function isFengzhushouSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('FENGZHUSHOU') || signature.includes('FZS') || signature.includes('PHONE580') || signature.includes('蜂助手')
}

function isChengquanSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('CHENGQUAN') || signature.includes('橙券') || signature.includes('鼎信')
}

function isFanchenSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('FANCHEN') || signature.includes('FANCHEN_RJ') || signature.includes('梵尘') || signature.includes('浙江梵尘')
}

function isJingzhaoSupplier(row: Supplier) {
  const signature = `${row.platformType || ''} ${row.name || ''} ${row.baseUrl || ''}`.toUpperCase()
  return signature.includes('JINGZHAO') || signature.includes('XHYGO') || signature.includes('京兆')
}

function isIntegratedSupplier(row: Supplier) {
  return isKasushouSupplier(row) || isKakayunSupplier(row) || isFuluSupplier(row) || isFengzhushouSupplier(row) || isChengquanSupplier(row) || isFanchenSupplier(row) || isJingzhaoSupplier(row)
}

function supportsGoodsSync(row: Supplier) {
  return isKasushouSupplier(row) || isKakayunSupplier(row) || isChengquanSupplier(row) || isFanchenSupplier(row) || isJingzhaoSupplier(row)
}

function platformLabel(row: Supplier) {
  if (isKasushouSupplier(row)) return '卡速售 2.0'
  if (isKakayunSupplier(row)) return '卡卡云'
  if (isFuluSupplier(row)) return '福禄新平台'
  if (isFengzhushouSupplier(row)) return '蜂助手直充'
  if (isChengquanSupplier(row)) return '鼎信橙券'
  if (isFanchenSupplier(row)) return '浙江梵尘'
  if (isJingzhaoSupplier(row)) return '京兆云'
  return '自定义'
}

function resetForm() {
  editingSupplierId.value = ''
  form.name = ''
  form.baseUrl = ''
  form.appKey = ''
  form.appSecret = ''
  form.platformType = 'CUSTOM'
  form.userId = ''
  form.appId = ''
  form.apiKey = ''
  form.callbackUrl = ''
  form.timeoutSeconds = 15
  form.balance = 0
  form.status = 'ENABLED'
  form.remark = ''
}

function applyPlatformDefaults(platformType = form.platformType || 'CUSTOM', previousPlatformType = '') {
  const defaults = platformDefaults[platformType] || platformDefaults.CUSTOM
  const isEditing = Boolean(editingSupplierId.value)
  const shouldRefreshName = !isEditing || !form.name.trim() || knownDefaultNames.has(form.name.trim())
  const shouldRefreshBaseUrl = !isEditing || !form.baseUrl.trim() || knownDefaultBaseUrls.has(form.baseUrl.trim())

  if (shouldRefreshName) form.name = defaults.name
  if (shouldRefreshBaseUrl) form.baseUrl = defaults.baseUrl

  if (!isEditing && previousPlatformType && previousPlatformType !== platformType) {
    form.appId = ''
    form.apiKey = ''
    form.appKey = ''
    form.appSecret = ''
    form.userId = ''
    form.callbackUrl = ''
  }
}

function fillSupplierForm(row: Supplier) {
  editingSupplierId.value = row.id
  form.name = row.name || ''
  form.baseUrl = row.baseUrl || ''
  form.appKey = row.appKey || ''
  form.appSecret = ''
  form.platformType = isKasushouSupplier(row)
    ? 'KASUSHOU_2'
    : (isKakayunSupplier(row)
        ? 'KAKAYUN'
        : (isFuluSupplier(row)
            ? 'FULU'
            : (isFengzhushouSupplier(row)
                ? 'FENGZHUSHOU'
                : (isChengquanSupplier(row) ? 'CHENGQUAN' : (isFanchenSupplier(row) ? 'FANCHEN_RJ' : (isJingzhaoSupplier(row) ? 'JINGZHAO' : 'CUSTOM'))))))
  form.userId = row.userId || ''
  form.appId = row.appId || ''
  form.apiKey = ''
  form.callbackUrl = row.callbackUrl || ''
  form.timeoutSeconds = row.timeoutSeconds || 15
  form.balance = Number(row.balance) || 0
  form.status = row.status || 'ENABLED'
  form.remark = row.remark || ''
}

function supplierPayload() {
  const kasushouAppId = form.appId?.trim()
  const kasushouSecret = form.apiKey?.trim() || form.appSecret.trim()
  const kakayunUserId = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const kakayunSecret = form.apiKey?.trim() || form.appSecret.trim()
  const fuluAppKey = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const fuluSecret = form.apiKey?.trim() || form.appSecret.trim()
  const fengzhushouProjectCode = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const fengzhushouSignKey = form.apiKey?.trim() || form.appSecret.trim()
  const chengquanAppId = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const chengquanKey = form.apiKey?.trim() || form.appSecret.trim()
  const fanchenUserId = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const fanchenKey = form.apiKey?.trim() || form.appSecret.trim()
  const jingzhaoCustomerId = form.appId?.trim() || form.userId?.trim() || form.appKey.trim()
  const jingzhaoKey = form.apiKey?.trim() || form.appSecret.trim()
  const normalizedAppKey = isIntegratedSelected.value
    ? kasushouAppId || form.userId?.trim() || form.appKey.trim()
    : form.appKey.trim()
  const normalizedIdentity = isChengquanSelected.value
    ? chengquanAppId
    : (isFanchenSelected.value
        ? fanchenUserId
        : (isJingzhaoSelected.value
            ? jingzhaoCustomerId
            : (isFengzhushouSelected.value ? fengzhushouProjectCode : (isFuluSelected.value ? fuluAppKey : (isKakayunSelected.value ? kakayunUserId : normalizedAppKey)))))
  const normalizedSecret = isKasushouSelected.value
    ? kasushouSecret
    : (isKakayunSelected.value
        ? kakayunSecret
        : (isFuluSelected.value
            ? fuluSecret
            : (isFengzhushouSelected.value
                ? fengzhushouSignKey
                : (isChengquanSelected.value ? chengquanKey : (isFanchenSelected.value ? fanchenKey : (isJingzhaoSelected.value ? jingzhaoKey : form.appSecret.trim()))))))
  const normalizedTimeoutSeconds = Number(form.timeoutSeconds) || 15

  return {
    payload: {
      ...form,
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      appKey: normalizedIdentity,
      appSecret: normalizedSecret,
      userId: isIntegratedSelected.value ? normalizedIdentity : form.userId?.trim(),
      appId: normalizedIdentity,
      apiKey: isIntegratedSelected.value ? normalizedSecret : form.apiKey?.trim(),
      callbackUrl: isKasushouSelected.value ? '' : form.callbackUrl?.trim(),
      timeoutSeconds: normalizedTimeoutSeconds,
      remark: form.remark?.trim(),
      integrationConfig: isIntegratedSelected.value
        ? {
            platformType: form.platformType,
            baseUrl: form.baseUrl.trim(),
            userId: normalizedIdentity,
            appId: normalizedIdentity,
            callbackUrl: isKasushouSelected.value ? '' : form.callbackUrl?.trim(),
            timeoutSeconds: normalizedTimeoutSeconds
          }
        : undefined
    } as SupplierCreatePayload & { integrationConfig?: Record<string, unknown> },
    normalizedSecret
  }
}

async function submitSupplier() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写供应商名称')
    return
  }
  if (!form.baseUrl.trim()) {
    ElMessage.warning('请填写 API 地址')
    return
  }
  const { payload, normalizedSecret } = supplierPayload()

  if (isIntegratedSelected.value && !normalizedSecret && !editingSupplierId.value) {
    ElMessage.warning('请填写密钥')
    return
  }

  saving.value = true
  try {
    if (editingSupplierId.value) {
      await updateSupplier(editingSupplierId.value, payload)
      ElMessage.success('供应商已更新')
    } else {
      await createSupplier(payload)
      ElMessage.success('供应商已新增')
    }
    resetForm()
    await loadSuppliers()
  } catch {
    ElMessage.error(editingSupplierId.value ? '更新供应商失败' : '新增供应商失败')
  } finally {
    saving.value = false
  }
}

async function runOperation(row: Supplier, type: 'balance' | 'delete') {
  operatingId.value = `${type}:${row.id}`
  try {
    if (type === 'balance') {
      await refreshSupplierBalance(row.id)
      ElMessage.success('余额已刷新')
    }
    if (type === 'delete') {
      await deleteSupplier(row.id)
      if (String(editingSupplierId.value) === String(row.id)) resetForm()
      ElMessage.success('供应商已删除')
    }
    await loadSuppliers()
  } catch (error) {
    ElMessage.error(errorMessage(error, '操作失败'))
  } finally {
    operatingId.value = ''
  }
}

async function runGoodsSync(row: Supplier) {
  if (!isIntegratedSupplier(row)) return

  syncingId.value = row.id
  try {
    if (!goods.value.length) await loadBindingGoods()
    syncResult.value = null
    syncDialogVisible.value = false
    syncResult.value = await syncSupplierGoods(row.id, { page: 1, limit: 20, cateId: 0, keyword: '' })
    syncSupplierName.value = row.name
    syncSupplierId.value = row.id
    if (!bindingGoodsId.value) bindingGoodsId.value = directGoods.value[0]?.id ?? ''
    syncDialogVisible.value = true
    ElMessage.success('商品同步完成')
    await loadSuppliers()
  } catch (error) {
    syncResult.value = null
    syncDialogVisible.value = false
    ElMessage.error(errorMessage(error, '商品同步失败'))
  } finally {
    syncingId.value = ''
  }
}

async function bindRemoteGoods(row: RemoteGoods) {
  if (!syncSupplierId.value) {
    ElMessage.warning('请先同步供应商商品')
    return
  }
  if (!bindingGoodsId.value) {
    ElMessage.warning('请选择要绑定的本地直充商品')
    return
  }
  if (!row.supplierGoodsId) {
    ElMessage.warning('远端商品缺少商品ID')
    return
  }

  bindingKey.value = row.supplierGoodsId
  try {
    await createGoodsChannel(bindingGoodsId.value, {
      supplierId: syncSupplierId.value,
      supplierGoodsId: row.supplierGoodsId,
      priority: Number(bindForm.priority) || 10,
      timeoutSeconds: Number(bindForm.timeoutSeconds) || 30,
      status: bindForm.status
    })
    ElMessage.success('已绑定为直充渠道')
  } catch {
    ElMessage.error('绑定渠道失败')
  } finally {
    bindingKey.value = ''
  }
}

async function removeSupplier(row: Supplier) {
  try {
    await ElMessageBox.confirm(`确定删除供应商「${row.name}」吗？关联的直充渠道也会一并移除。`, '删除供应商', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'xiyiyun-glass-message-box'
    })
    await runOperation(row, 'delete')
  } catch {
    // canceled
  }
}
</script>

<template>
  <section class="supplier-grid">
    <article class="create-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>{{ formTitle }}</h2>
          <span>{{ formSubtitle }}</span>
        </div>
        <PlugZap :size="20" />
      </div>

      <el-form :model="form" label-position="top" class="supplier-form">
        <el-form-item label="平台类型">
          <el-select v-model="form.platformType" placeholder="请选择平台类型">
            <el-option v-for="option in platformOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商名称">
          <el-input v-model="form.name" placeholder="例如：星河直充" />
        </el-form-item>
        <el-form-item :label="baseUrlLabel">
          <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
        </el-form-item>
        <template v-if="isIntegratedSelected">
          <div class="kasushou-fields">
            <el-form-item :label="isJingzhaoSelected ? 'customer_id' : (isFanchenSelected ? 'userid' : (isChengquanSelected ? 'app_id' : (isFengzhushouSelected ? 'projectCode' : (isFuluSelected ? 'APP_KEY' : (isKakayunSelected ? '商户ID / userid' : 'APPID')))))">
              <el-input
                v-model="form.appId"
                :placeholder="isJingzhaoSelected ? '京兆云商家编号 customer_id' : (isFanchenSelected ? '浙江梵尘商户编号 userid' : (isChengquanSelected ? '鼎信橙券 app_id' : (isFengzhushouSelected ? '蜂助手合作项目编码，例如 LTZY' : (isFuluSelected ? '福禄 app_key' : (isKakayunSelected ? '卡卡云商户ID，例如 10052' : '卡速售 APPID')))))"
              />
            </el-form-item>
            <el-form-item :label="isJingzhaoSelected ? '商户密钥 key' : (isFanchenSelected ? '商户 KEY' : (isChengquanSelected ? '商户密钥 key' : (isFengzhushouSelected ? 'signKey' : (isFuluSelected ? 'APP_SECRET' : (isKakayunSelected ? '商户KEY' : '密钥')))))">
              <el-input v-model="form.apiKey" type="password" show-password placeholder="用于签名，保存后仅脱敏展示" />
            </el-form-item>
          </div>
          <el-form-item v-if="isKakayunSelected" label="订单回调地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：卡卡云订单状态回调地址" />
          </el-form-item>
          <el-form-item v-if="isFuluSelected" label="订单回调地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：例如 https://你的域名/api/upstream/fulu/callback/{供应商ID}" />
          </el-form-item>
          <el-form-item v-if="isFengzhushouSelected" label="发货结果通知地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：例如 https://你的域名/api/upstream/fengzhushou/callback/{供应商ID}" />
          </el-form-item>
          <el-form-item v-if="isChengquanSelected" label="订单通知地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：例如 https://你的域名/api/upstream/chengquan/callback/{供应商ID}" />
          </el-form-item>
          <el-form-item v-if="isFanchenSelected" label="订单回调地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：例如 https://你的域名/api/upstream/fanchen/callback/{供应商ID}" />
          </el-form-item>
          <el-form-item v-if="isJingzhaoSelected" label="订单通知地址">
            <el-input v-model="form.callbackUrl" placeholder="可选：例如 https://你的域名/api/upstream/jingzhao/callback/{供应商ID}" />
          </el-form-item>
          <p v-if="isKakayunSelected" class="form-tip">测试地址：{{ KAKAYUN_TEST_BASE_URL }}；正式地址默认使用上方固定网关，也可按你的卡卡云配置调整。</p>
          <p v-if="isFuluSelected" class="form-tip">沙箱地址：{{ FULU_SANDBOX_BASE_URL }}；福禄不提供商品列表，商品对接时请手动填写 product_id。</p>
          <p v-if="isFengzhushouSelected" class="form-tip">测试地址：{{ FENGZHUSHOU_TEST_BASE_URL }}；蜂助手不提供商品列表，商品对接时请手动填写 skuCode 和结算价。</p>
          <p v-if="isChengquanSelected" class="form-tip">鼎信橙券支持余额查询、品牌产品同步、直充下单与查单；直充商品对接使用 product_id。</p>
          <p v-if="isFanchenSelected" class="form-tip">浙江梵尘使用 GBK 表单请求，支持余额查询、商品价格同步、直充下单与查单；商品对接使用 productid。</p>
          <p v-if="isJingzhaoSelected" class="form-tip">京兆云支持商品列表、充值参数、余额查询、下单、外部订单查询；商品对接使用 product_id。</p>
        </template>
        <template v-else>
          <el-form-item label="AppKey">
            <el-input v-model="form.appKey" placeholder="用于鉴权的 Key" />
          </el-form-item>
          <el-form-item label="AppSecret">
            <el-input v-model="form.appSecret" type="password" show-password placeholder="保存后仅脱敏展示" />
          </el-form-item>
        </template>
        <div class="form-actions">
          <el-button type="primary" :icon="editingSupplierId ? Edit3 : Plus" :loading="saving" @click="submitSupplier">
            {{ editingSupplierId ? '保存供应商' : '新增供应商' }}
          </el-button>
          <el-button v-if="editingSupplierId" :icon="X" @click="resetForm">取消编辑</el-button>
        </div>
      </el-form>

      <section v-if="isIntegratedSelected" class="integration-card">
        <div class="integration-title">
          <ShieldCheck :size="17" />
          <strong>{{ integrationTitle }}</strong>
        </div>
        <div class="capability-groups">
          <div v-for="group in currentCapabilityGroups" :key="group.group" class="capability-group">
            <span>{{ group.group }}</span>
            <p>{{ group.items.join(' / ') }}</p>
          </div>
        </div>
        <div class="signature-note">
          <div>
            <KeyRound :size="16" />
            <strong>签名规则</strong>
          </div>
          <p>{{ signatureNote }}</p>
          <p>{{ headerNote }}</p>
        </div>
      </section>
    </article>

    <article class="table-panel liquid-admin-panel">
      <div class="panel-head">
        <div>
          <h2>供应商列表</h2>
          <span>{{ suppliers.length }} 个上游</span>
        </div>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadSuppliers">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="suppliers" height="640" style="width: 100%">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="供应商" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="supplier-name">
              <strong>{{ row.name }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="平台" width="130">
          <template #default="{ row }">
            <span class="platform-pill" :data-platform="row.platformType || platformLabel(row)">{{ platformLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="API 地址" min-width="220" show-overflow-tooltip />
        <el-table-column label="鉴权标识" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ isIntegratedSupplier(row) ? row.appId || row.userId || row.appKey || '-' : row.appKey || '-' }}</template>
        </el-table-column>
        <el-table-column label="密钥" width="130">
          <template #default="{ row }">{{ isIntegratedSupplier(row) ? row.apiKeyMasked || '-' : row.appSecretMasked || '-' }}</template>
        </el-table-column>
        <el-table-column label="余额" width="130">
          <template #default="{ row }">
            <span class="balance" :data-low="Number(row.balance) < 300">{{ formatMoney(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="status-pill" :data-enabled="row.status === 'ENABLED'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最后同步" width="180">
          <template #default="{ row }">{{ formatTime(row.lastSyncAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="300">
          <template #default="{ row }">
            <div class="action-buttons">
              <el-button size="small" :icon="Edit3" @click="fillSupplierForm(row)">编辑</el-button>
              <el-button
                v-if="supportsGoodsSync(row)"
                size="small"
                :icon="PackageCheck"
                :loading="syncingId === row.id"
                @click="runGoodsSync(row)"
              >
                同步商品
              </el-button>
              <el-button
                size="small"
                :icon="WalletCards"
                :loading="operatingId === `balance:${row.id}`"
                @click="runOperation(row, 'balance')"
              >
                余额
              </el-button>
              <el-button
                size="small"
                type="danger"
                :icon="Trash2"
                :loading="operatingId === `delete:${row.id}`"
                @click="removeSupplier(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </article>

    <el-dialog v-model="syncDialogVisible" class="xiyiyun-glass-dialog sync-result-dialog" width="980px" align-center>
      <template #header>
        <div class="sync-dialog-title">
          <PackageCheck :size="18" />
          <span>{{ syncSupplierName || '供应商' }}商品同步结果</span>
        </div>
      </template>

      <section class="sync-summary">
        <article>
          <span>同步时间</span>
          <strong>{{ formatTime(syncResult?.syncedAt) }}</strong>
        </article>
        <article>
          <span>商品总数</span>
          <strong>{{ syncResult?.total ?? 0 }}</strong>
        </article>
        <article>
          <span>本次展示</span>
          <strong>{{ syncGoods.length }}</strong>
        </article>
      </section>

      <section class="remote-bind-bar">
        <div>
          <span>绑定到本地直充商品</span>
          <strong>{{ directGoods.length ? '选择后可把远端商品加入订单渠道' : '暂无直充商品' }}</strong>
        </div>
        <el-select v-model="bindingGoodsId" filterable placeholder="选择本地直充商品" :disabled="!directGoods.length">
          <el-option
            v-for="item in directGoods"
            :key="item.id"
            :label="`${item.name} · ID ${item.id}`"
            :value="item.id"
          />
        </el-select>
        <el-input-number v-model="bindForm.priority" :min="1" :step="10" controls-position="right" />
        <el-input-number v-model="bindForm.timeoutSeconds" :min="5" :step="5" controls-position="right" />
        <el-select v-model="bindForm.status">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </section>

      <el-table :data="syncGoods" max-height="520" style="width: 100%">
        <el-table-column prop="supplierGoodsId" label="供应商商品ID" min-width="130" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="190" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column label="价格" width="100">
          <template #default="{ row }">{{ formatMoney(row.price) }}</template>
        </el-table-column>
        <el-table-column label="面值" width="100">
          <template #default="{ row }">{{ row.faceValue ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="库存" width="90">
          <template #default="{ row }">{{ row.stock ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="可售渠道" min-width="150">
          <template #default="{ row }">
            <div class="channel-tags">
              <el-tag v-for="channel in row.availablePlatforms" :key="channel" size="small" effect="dark">{{ channel }}</el-tag>
              <span v-if="!row.availablePlatforms.length" class="muted-text">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="禁售渠道" min-width="150">
          <template #default="{ row }">
            <div class="channel-tags">
              <el-tag v-for="channel in row.forbiddenPlatforms" :key="channel" size="small" type="warning" effect="dark">{{ channel }}</el-tag>
              <span v-if="!row.forbiddenPlatforms.length" class="muted-text">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              :loading="bindingKey === row.supplierGoodsId"
              :disabled="!bindingGoodsId"
              @click="bindRemoteGoods(row)"
            >
              绑定渠道
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.supplier-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 14px;
}

.create-panel,
.table-panel {
  padding: 18px;
  overflow: hidden;
  border-radius: 22px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.45);
  font-size: 13px;
}

.panel-head svg {
  color: #00ffc3;
  filter: drop-shadow(0 0 18px rgba(0, 255, 195, 0.35));
}

.supplier-form :deep(.el-input-number),
.supplier-form :deep(.el-select) {
  width: 100%;
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.form-tip {
  margin: -4px 0 14px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  line-height: 1.7;
}

.kasushou-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
  padding: 12px;
  margin-bottom: 16px;
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  border-radius: 14px;
  background: rgba(0, 255, 195, 0.045);
}

.integration-card {
  margin-top: 16px;
  padding: 14px;
  border: 0.5px solid rgba(255, 255, 255, 0.09);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
}

.integration-title,
.signature-note div {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.88);
}

.integration-title svg,
.signature-note svg {
  color: #00ffc3;
}

.capability-groups {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.capability-group {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.capability-group span {
  display: inline-flex;
  justify-content: center;
  padding: 3px 0;
  border-radius: 8px;
  color: #00ffc3;
  font-size: 12px;
  background: rgba(0, 255, 195, 0.08);
}

.capability-group p,
.signature-note p {
  margin: 0;
  color: rgba(255, 255, 255, 0.58);
  font-size: 12px;
  line-height: 1.7;
}

.signature-note {
  display: grid;
  gap: 6px;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.08);
}

.supplier-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.supplier-name strong {
  min-width: 0;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.86);
  text-overflow: ellipsis;
}

.status-pill,
.balance,
.platform-pill {
  display: inline-flex;
  padding: 4px 9px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.62);
  background: rgba(255, 255, 255, 0.06);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
}

.status-pill[data-enabled="true"],
.balance {
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border-color: rgba(0, 255, 195, 0.2);
}

.balance[data-low="true"] {
  color: #ffab00;
  background: rgba(255, 171, 0, 0.1);
  border-color: rgba(255, 171, 0, 0.22);
  box-shadow: 0 0 20px rgba(255, 171, 0, 0.12);
}

.platform-pill {
  flex: 0 0 auto;
  font-size: 12px;
}

.platform-pill[data-platform="KASUSHOU_2"] {
  color: #071614;
  font-weight: 700;
  background: #00ffc3;
  border-color: rgba(0, 255, 195, 0.45);
  box-shadow: 0 0 18px rgba(0, 255, 195, 0.24);
}

.platform-pill[data-platform="KAKAYUN"] {
  color: #170b00;
  font-weight: 700;
  background: #ffb84d;
  border-color: rgba(255, 184, 77, 0.46);
  box-shadow: 0 0 18px rgba(255, 184, 77, 0.24);
}

.platform-pill[data-platform="FULU"] {
  color: #08111f;
  font-weight: 700;
  background: #7dd3fc;
  border-color: rgba(125, 211, 252, 0.46);
  box-shadow: 0 0 18px rgba(125, 211, 252, 0.24);
}

.platform-pill[data-platform="FENGZHUSHOU"] {
  color: #08111f;
  font-weight: 700;
  background: #fef08a;
  border-color: rgba(254, 240, 138, 0.48);
  box-shadow: 0 0 18px rgba(254, 240, 138, 0.22);
}

.platform-pill[data-platform="CHENGQUAN"] {
  color: #08111f;
  font-weight: 700;
  background: #fb923c;
  border-color: rgba(251, 146, 60, 0.48);
  box-shadow: 0 0 18px rgba(251, 146, 60, 0.22);
}

.platform-pill[data-platform="FANCHEN_RJ"] {
  color: #071614;
  font-weight: 700;
  background: #86efac;
  border-color: rgba(134, 239, 172, 0.48);
  box-shadow: 0 0 18px rgba(134, 239, 172, 0.22);
}

.platform-pill[data-platform="JINGZHAO"] {
  color: #101728;
  font-weight: 700;
  background: #c4b5fd;
  border-color: rgba(196, 181, 253, 0.5);
  box-shadow: 0 0 18px rgba(196, 181, 253, 0.22);
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.action-buttons :deep(.el-button) {
  margin-left: 0;
}

.table-panel :deep(.action-buttons .el-button) {
  background: rgba(255, 255, 255, 0.055);
  border-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.78);
}

.table-panel :deep(.action-buttons .el-button.el-button--danger) {
  color: #ff8b8b;
  background: rgba(255, 78, 78, 0.09);
  border-color: rgba(255, 78, 78, 0.24);
}

.sync-dialog-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.92);
  font-weight: 700;
}

.sync-dialog-title svg {
  color: #00ffc3;
}

.sync-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.sync-summary article {
  display: grid;
  gap: 7px;
  padding: 12px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.045);
}

.sync-summary span,
.muted-text {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.sync-summary strong {
  color: rgba(255, 255, 255, 0.88);
  font-size: 16px;
}

.channel-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.remote-bind-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 1.2fr) 120px 120px 100px;
  gap: 12px;
  align-items: center;
  padding: 12px;
  margin-bottom: 14px;
  border: 0.5px solid rgba(0, 255, 195, 0.13);
  border-radius: 12px;
  background: rgba(0, 255, 195, 0.045);
}

.remote-bind-bar div {
  display: grid;
  gap: 5px;
}

.remote-bind-bar span {
  color: rgba(255, 255, 255, 0.88);
  font-size: 13px;
  font-weight: 700;
}

.remote-bind-bar strong {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
  font-weight: 500;
}

.remote-bind-bar :deep(.el-select),
.remote-bind-bar :deep(.el-input-number) {
  width: 100%;
}

:global(.sync-result-dialog) {
  --el-dialog-bg-color: rgba(14, 18, 26, 0.96);
  color: rgba(255, 255, 255, 0.84);
  background:
    radial-gradient(circle at 12% 0%, rgba(0, 255, 195, 0.12), transparent 34%),
    radial-gradient(circle at 92% 10%, rgba(88, 166, 255, 0.14), transparent 28%),
    rgba(14, 18, 26, 0.96) !important;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.42);
}

:global(.sync-result-dialog .el-dialog__header),
:global(.sync-result-dialog .el-dialog__body) {
  color: rgba(255, 255, 255, 0.84);
}

:global(.sync-result-dialog .el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(255, 255, 255, 0.05);
  --el-table-row-hover-bg-color: rgba(0, 255, 195, 0.08);
  --el-table-border-color: rgba(255, 255, 255, 0.08);
  --el-table-text-color: rgba(255, 255, 255, 0.78);
  --el-table-header-text-color: rgba(255, 255, 255, 0.58);
  background: transparent;
}

:global(.sync-result-dialog .el-table th.el-table__cell),
:global(.sync-result-dialog .el-table tr),
:global(.sync-result-dialog .el-table td.el-table__cell) {
  background: transparent;
}

@media (max-width: 1180px) {
  .supplier-grid {
    grid-template-columns: 1fr;
  }

  .remote-bind-bar {
    grid-template-columns: 1fr;
  }
}
</style>
