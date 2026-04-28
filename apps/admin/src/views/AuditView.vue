<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import {
  fetchMemberApiCredentials,
  fetchOpenApiLogs,
  fetchOperationLogs,
  fetchPayments,
  fetchRefunds,
  fetchSmsLogs
} from '../api/admin'
import type { MemberApiCredential, OpenApiLog, OperationLog, PaymentRecord, RefundRecord, SmsLog } from '../types/operations'

const loading = ref(false)
const activeTab = ref('payments')
const payments = ref<PaymentRecord[]>([])
const refunds = ref<RefundRecord[]>([])
const smsLogs = ref<SmsLog[]>([])
const operationLogs = ref<OperationLog[]>([])
const credentials = ref<MemberApiCredential[]>([])
const openApiLogs = ref<OpenApiLog[]>([])

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [nextPayments, nextRefunds, nextSmsLogs, nextOperationLogs, nextCredentials, nextOpenApiLogs] = await Promise.all([
      fetchPayments(),
      fetchRefunds(),
      fetchSmsLogs(),
      fetchOperationLogs(),
      fetchMemberApiCredentials(),
      fetchOpenApiLogs()
    ])
    payments.value = nextPayments
    refunds.value = nextRefunds
    smsLogs.value = nextSmsLogs
    operationLogs.value = nextOperationLogs
    credentials.value = nextCredentials
    openApiLogs.value = nextOpenApiLogs
  } catch {
    ElMessage.error('审计数据加载失败')
  } finally {
    loading.value = false
  }
}

function formatMoney(value: number | string) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? `¥${numberValue.toFixed(2)}` : '-'
}
</script>

<template>
  <article class="panel" v-loading="loading">
    <div class="panel-head">
      <div>
        <h2>审计与开放平台</h2>
        <span>支付、退款、短信、操作日志和会员 API 调用</span>
      </div>
      <el-button :icon="RefreshCw" :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <nav class="tabs" aria-label="审计类型">
      <button type="button" :class="{ active: activeTab === 'payments' }" @click="activeTab = 'payments'">支付流水</button>
      <button type="button" :class="{ active: activeTab === 'refunds' }" @click="activeTab = 'refunds'">退款流水</button>
      <button type="button" :class="{ active: activeTab === 'sms' }" @click="activeTab = 'sms'">短信日志</button>
      <button type="button" :class="{ active: activeTab === 'ops' }" @click="activeTab = 'ops'">操作日志</button>
      <button type="button" :class="{ active: activeTab === 'api' }" @click="activeTab = 'api'">会员 API</button>
    </nav>

    <el-table v-if="activeTab === 'payments'" :data="payments" height="620" style="width: 100%">
      <el-table-column prop="paymentNo" label="支付单号" min-width="210" show-overflow-tooltip />
      <el-table-column prop="orderNo" label="订单号" min-width="210" show-overflow-tooltip />
      <el-table-column prop="method" label="方式" width="110" />
      <el-table-column label="金额" width="110"><template #default="{ row }">{{ formatMoney(row.amount) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="paidAt" label="支付时间" min-width="180" />
    </el-table>

    <el-table v-else-if="activeTab === 'refunds'" :data="refunds" height="620" style="width: 100%">
      <el-table-column prop="refundNo" label="退款单号" min-width="210" show-overflow-tooltip />
      <el-table-column prop="orderNo" label="订单号" min-width="210" show-overflow-tooltip />
      <el-table-column label="金额" width="110"><template #default="{ row }">{{ formatMoney(row.amount) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
      <el-table-column prop="refundedAt" label="退款时间" min-width="180" />
    </el-table>

    <el-table v-else-if="activeTab === 'sms'" :data="smsLogs" height="620" style="width: 100%">
      <el-table-column prop="orderNo" label="订单号" min-width="210" show-overflow-tooltip />
      <el-table-column prop="mobile" label="手机号" width="140" />
      <el-table-column prop="templateType" label="模板" width="120" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="errorMessage" label="说明" min-width="160" />
      <el-table-column prop="createdAt" label="时间" min-width="180" />
    </el-table>

    <el-table v-else-if="activeTab === 'ops'" :data="operationLogs" height="620" style="width: 100%">
      <el-table-column prop="action" label="动作" min-width="180" />
      <el-table-column prop="resourceType" label="资源" width="120" />
      <el-table-column prop="resourceId" label="资源ID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="remark" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="时间" min-width="180" />
    </el-table>

    <template v-else>
      <section class="api-grid">
        <el-table :data="credentials" height="260" style="width: 100%">
          <el-table-column prop="appKey" label="AppKey" min-width="180" />
          <el-table-column prop="userId" label="用户ID" width="100" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="dailyLimit" label="日限额" width="110" />
        </el-table>
        <el-table :data="openApiLogs" height="340" style="width: 100%">
          <el-table-column prop="appKey" label="AppKey" min-width="160" />
          <el-table-column prop="path" label="接口" min-width="210" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="message" label="说明" min-width="180" />
          <el-table-column prop="createdAt" label="时间" min-width="180" />
        </el-table>
      </section>
    </template>
  </article>
</template>

<style scoped>
.panel {
  position: relative;
  padding: 18px;
  overflow: hidden;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-head span {
  color: rgba(255, 255, 255, 0.48);
}

.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.tabs button {
  height: 34px;
  padding: 0 13px;
  color: rgba(255, 255, 255, 0.66);
  border-radius: 999px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.045);
}

.tabs button.active {
  color: #06100e;
  background: #00ffc3;
}

.api-grid {
  display: grid;
  gap: 14px;
}
</style>
