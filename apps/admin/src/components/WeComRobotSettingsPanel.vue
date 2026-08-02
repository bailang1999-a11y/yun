<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Bot, CheckCircle2, Clock3, RefreshCw, Send, ShieldAlert, XCircle } from 'lucide-vue-next'
import { fetchWeComRobotDeliveries, sendWeComRobotTest } from '../api/operations'
import type {
  WeComNotificationEvent,
  WeComRobotDelivery,
  WeComRobotSetting
} from '../types/operations'

const props = defineProps<{ modelValue: WeComRobotSetting }>()
const emit = defineEmits<{ 'update:modelValue': [value: WeComRobotSetting] }>()

const testing = ref(false)
const loadingDeliveries = ref(false)
const deliveries = ref<WeComRobotDelivery[]>([])

const eventOptions: Array<{
  value: WeComNotificationEvent
  label: string
  description: string
}> = [
  { value: 'ORDER_CREATED', label: '新订单', description: '订单进入系统' },
  { value: 'PAYMENT_SUCCEEDED', label: '支付成功', description: '支付状态确认' },
  { value: 'DELIVERY_SUCCEEDED', label: '发货成功', description: '订单完成交付' },
  { value: 'DELIVERY_FAILED', label: '发货失败', description: '交付进入失败状态' },
  { value: 'ORDER_REFUNDED', label: '退款 / 取消', description: '订单退款或取消' },
  { value: 'UPSTREAM_EXCEPTION', label: '上游异常', description: '采购或查单异常' }
]

const eventLabels: Record<string, string> = Object.fromEntries(
  eventOptions.map((item) => [item.value, item.label])
)

const enabled = computed({
  get: () => props.modelValue.enabled,
  set: (value: boolean) => updateSetting({ enabled: value })
})

const webhookUrl = computed({
  get: () => props.modelValue.webhookUrl,
  set: (value: string) => updateSetting({ webhookUrl: value })
})

const selectedEvents = computed({
  get: () => props.modelValue.events,
  set: (value: WeComNotificationEvent[]) => updateSetting({ events: value })
})

const webhookValid = computed(() => isOfficialWebhook(props.modelValue.webhookUrl))
const testDisabled = computed(() => !props.modelValue.enabled || !webhookValid.value)

onMounted(() => {
  void loadDeliveries()
})

function updateSetting(patch: Partial<WeComRobotSetting>) {
  emit('update:modelValue', {
    ...props.modelValue,
    ...patch,
    events: patch.events ? [...patch.events] : [...props.modelValue.events]
  })
}

function isOfficialWebhook(value: string) {
  if (!value.trim()) return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:'
      && url.hostname === 'qyapi.weixin.qq.com'
      && url.pathname === '/cgi-bin/webhook/send'
      && /^[A-Za-z0-9_-]{16,128}$/.test(url.searchParams.get('key') || '')
      && [...url.searchParams.keys()].every((key) => key === 'key')
  } catch {
    return false
  }
}

async function sendTest() {
  if (!webhookValid.value) {
    ElMessage.warning('请输入企业微信官方机器人 Webhook')
    return
  }
  testing.value = true
  try {
    await sendWeComRobotTest()
    ElMessage.success('测试消息已发送')
    await loadDeliveries()
  } catch {
    ElMessage.error('测试发送失败')
  } finally {
    testing.value = false
  }
}

async function loadDeliveries() {
  loadingDeliveries.value = true
  try {
    deliveries.value = await fetchWeComRobotDeliveries()
  } catch {
    deliveries.value = []
  } finally {
    loadingDeliveries.value = false
  }
}

function statusMeta(status: string) {
  const normalized = status.toUpperCase()
  if (normalized === 'SUCCESS') return { label: '已送达', className: 'success', icon: CheckCircle2 }
  if (normalized === 'FAILED') return { label: '失败', className: 'failed', icon: XCircle }
  return { label: '发送中', className: 'pending', icon: Clock3 }
}
</script>

<template>
  <article class="panel wecom-panel">
    <div class="panel-head">
      <div class="panel-title">
        <span class="panel-icon"><Bot :size="19" /></span>
        <div>
          <h2>企业微信群机器人</h2>
          <span>企业微信官方群机器人</span>
        </div>
      </div>
      <div class="enable-control">
        <span :class="['enable-state', { active: enabled }]">{{ enabled ? '运行中' : '已停用' }}</span>
        <el-switch v-model="enabled" aria-label="启用企业微信群机器人" />
      </div>
    </div>

    <div class="wecom-layout">
      <el-form label-position="top" class="settings-form config-column">
        <el-form-item label="机器人 Webhook">
          <el-input
            v-model="webhookUrl"
            type="password"
            show-password
            autocomplete="off"
            placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
          />
          <div :class="['webhook-state', { valid: webhookValid }]">
            <CheckCircle2 v-if="webhookValid" :size="14" />
            <ShieldAlert v-else :size="14" />
            <span>{{ webhookValid ? '官方地址格式正确' : '仅支持企业微信官方 Webhook' }}</span>
          </div>
        </el-form-item>

        <el-form-item label="通知事件">
          <el-checkbox-group v-model="selectedEvents" class="event-grid">
            <el-checkbox
              v-for="item in eventOptions"
              :key="item.value"
              :value="item.value"
              class="event-option"
            >
              <span class="event-copy">
                <strong>{{ item.label }}</strong>
                <small>{{ item.description }}</small>
              </span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <div class="security-note">
          <ShieldAlert :size="16" />
          <span>订单数据完整通知，卡密正文不发送</span>
        </div>
      </el-form>

      <section class="delivery-column" aria-label="最近发送记录">
        <div class="delivery-head">
          <div>
            <strong>最近发送</strong>
            <span>保留最近 5 条记录</span>
          </div>
          <el-button
            circle
            size="small"
            :icon="RefreshCw"
            :loading="loadingDeliveries"
            aria-label="刷新发送记录"
            @click="loadDeliveries"
          />
        </div>

        <div v-if="deliveries.length" class="delivery-list">
          <div v-for="item in deliveries" :key="item.id" class="delivery-item">
            <component :is="statusMeta(item.status).icon" :class="statusMeta(item.status).className" :size="16" />
            <div class="delivery-copy">
              <strong>{{ eventLabels[item.event] || (item.event === 'TEST' ? '测试消息' : item.event) }}</strong>
              <span>{{ item.orderNo || '测试消息' }} · {{ item.createdAt || '刚刚' }}</span>
              <small v-if="item.errorMessage">{{ item.errorMessage }}</small>
            </div>
            <span :class="['delivery-status', statusMeta(item.status).className]">
              {{ statusMeta(item.status).label }}
            </span>
          </div>
        </div>
        <div v-else class="empty-deliveries">
          <Send :size="22" />
          <strong>暂无发送记录</strong>
          <span>测试发送或订单触发后展示</span>
        </div>

        <el-button
          class="test-button"
          :icon="Send"
          :loading="testing"
          :disabled="testDisabled"
          @click="sendTest"
        >
          发送测试消息
        </el-button>
      </section>
    </div>
  </article>
</template>

<style scoped>
.panel {
  position: relative;
  padding: 18px;
  overflow: hidden;
  border: 0.5px solid rgba(255, 255, 255, 0.11);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(28px) saturate(180%);
}

.wecom-panel {
  grid-column: 1 / -1;
}

.panel-head,
.panel-title,
.enable-control,
.delivery-head,
.delivery-head > div,
.webhook-state,
.security-note,
.delivery-item {
  display: flex;
  align-items: center;
}

.panel-head {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-title {
  gap: 10px;
}

.panel-icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid rgba(141, 247, 224, 0.24);
  border-radius: 8px;
  color: #8df7e0;
  background: rgba(0, 255, 195, 0.1);
}

h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 18px;
}

.panel-title > div > span,
.delivery-head span,
.empty-deliveries span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
}

.enable-control {
  gap: 10px;
}

.enable-state {
  color: rgba(255, 255, 255, 0.42);
  font-size: 12px;
  font-weight: 700;
}

.enable-state.active {
  color: #8df7e0;
}

.wecom-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(300px, 0.8fr);
  gap: 18px;
}

.config-column {
  min-width: 0;
}

.webhook-state {
  gap: 5px;
  margin-top: 7px;
  color: rgba(255, 190, 104, 0.88);
  font-size: 12px;
}

.webhook-state.valid {
  color: #8df7e0;
}

.event-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}

.event-option {
  width: 100%;
  height: 58px;
  margin: 0 !important;
  padding: 9px 11px;
  border: 1px solid rgba(183, 215, 244, 0.12);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.035);
  transition: border-color 160ms ease, background 160ms ease;
}

.event-option:hover,
.event-option.is-checked {
  border-color: rgba(0, 255, 195, 0.3);
  background: rgba(0, 255, 195, 0.075);
}

.event-option :deep(.el-checkbox__label) {
  min-width: 0;
  padding-left: 8px;
}

.event-copy {
  display: grid;
  gap: 1px;
}

.event-copy strong {
  color: rgba(244, 249, 255, 0.88);
  font-size: 13px;
}

.event-copy small {
  overflow: hidden;
  color: rgba(210, 225, 242, 0.46);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.security-note {
  gap: 7px;
  min-height: 34px;
  padding: 7px 10px;
  border: 1px solid rgba(255, 190, 104, 0.14);
  border-radius: 8px;
  color: rgba(255, 210, 148, 0.78);
  background: rgba(255, 168, 61, 0.055);
  font-size: 12px;
}

.delivery-column {
  display: flex;
  min-height: 244px;
  flex-direction: column;
  padding: 13px;
  border: 1px solid rgba(122, 204, 255, 0.14);
  border-radius: 8px;
  background: rgba(5, 12, 24, 0.34);
}

.delivery-head {
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.delivery-head > div {
  gap: 8px;
}

.delivery-head strong,
.empty-deliveries strong {
  color: rgba(244, 249, 255, 0.84);
  font-size: 13px;
}

.delivery-list {
  display: grid;
  gap: 5px;
}

.delivery-item {
  gap: 8px;
  min-height: 39px;
  padding: 5px 7px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.028);
}

.delivery-copy {
  display: grid;
  flex: 1;
  min-width: 0;
}

.delivery-copy strong {
  color: rgba(244, 249, 255, 0.78);
  font-size: 12px;
}

.delivery-copy span,
.delivery-copy small {
  overflow: hidden;
  color: rgba(210, 225, 242, 0.42);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.delivery-copy small {
  color: #ff9aa5;
}

.delivery-status {
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 10px;
  font-weight: 700;
}

.success {
  color: #57e7a2;
}

.failed {
  color: #ff7d8a;
}

.pending {
  color: #58a6ff;
}

.delivery-status.success {
  background: rgba(87, 231, 162, 0.1);
}

.delivery-status.failed {
  background: rgba(255, 125, 138, 0.1);
}

.delivery-status.pending {
  background: rgba(88, 166, 255, 0.1);
}

.empty-deliveries {
  display: grid;
  flex: 1;
  place-content: center;
  place-items: center;
  gap: 4px;
  color: rgba(141, 247, 224, 0.44);
}

.test-button {
  width: 100%;
  margin-top: 10px;
}

@media (max-width: 1180px) {
  .wecom-layout {
    grid-template-columns: 1fr;
  }

  .event-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
