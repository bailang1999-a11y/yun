<template>
  <WebShell>
    <section class="api-page">
      <div class="page-heading">
        <div>
          <p>会员 API</p>
          <h1>一键发货接口</h1>
        </div>
        <div class="button-row">
          <StatusBadge :status="credential?.status" />
          <button class="ghost-button" type="button" :disabled="saving" @click="toggleEnabled">{{ credential?.status === 'ENABLED' ? '停用接口' : '启用接口' }}</button>
        </div>
      </div>

      <div class="api-grid">
        <section class="info-panel">
          <dl class="detail-list">
            <div><dt>App Key</dt><dd>{{ credential?.appKey || '-' }}</dd></div>
            <div><dt>App Secret</dt><dd>{{ credential?.appSecretMasked || '-' }}</dd></div>
            <div><dt>每日额度</dt><dd>{{ credential?.dailyLimit || 0 }} 次</dd></div>
            <div><dt>最近调用</dt><dd>{{ credential?.lastUsedAt || '-' }}</dd></div>
          </dl>
          <div class="button-row">
            <button class="ghost-button" type="button" :disabled="saving" @click="resetSecret">重置密钥</button>
            <button class="primary-button" type="button" :disabled="saving" @click="saveWhitelist">保存白名单</button>
          </div>
          <p v-if="message" class="success-line">{{ message }}</p>
          <p v-if="error" class="alert-line">{{ error }}</p>
        </section>

        <section class="info-panel">
          <label>
            <span>IP 白名单</span>
            <textarea v-model="ipWhitelist" rows="8" placeholder="每行一个 IP" />
          </label>
          <div class="endpoint-list">
            <strong>常用接口</strong>
            <code>POST /api/open/orders</code>
            <code>GET /api/open/orders/:orderNo</code>
            <code>POST /api/open/delivery/callback</code>
          </div>
        </section>
      </div>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import WebShell from '../components/WebShell.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { fetchApiCredential, saveApiCredential } from '../api/web'
import { getApiErrorMessage } from '../api/client'
import { useSessionStore } from '../stores/session'
import type { ApiCredential } from '../types/web'

const session = useSessionStore()
const credential = ref<ApiCredential | null>(null)
const ipWhitelist = ref('')
const saving = ref(false)
const message = ref('')
const error = ref('')

onMounted(async () => {
  await session.ensureProfile({ force: true })
  credential.value = await fetchApiCredential()
  ipWhitelist.value = credential.value.ipWhitelist.join('\n')
})

async function saveCredential(payload: { enabled?: boolean; resetSecret?: boolean; ipWhitelist?: string[] }) {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    credential.value = await saveApiCredential(payload)
    ipWhitelist.value = credential.value.ipWhitelist.join('\n')
    message.value = payload.resetSecret ? '密钥已重置。' : '接口配置已保存。'
  } catch (err) {
    error.value = getApiErrorMessage(err)
  } finally {
    saving.value = false
  }
}

function whitelistRows() {
  return ipWhitelist.value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
}

function saveWhitelist() {
  void saveCredential({ ipWhitelist: whitelistRows(), enabled: credential.value?.status === 'ENABLED' })
}

function resetSecret() {
  void saveCredential({ resetSecret: true, ipWhitelist: whitelistRows(), enabled: credential.value?.status === 'ENABLED' })
}

function toggleEnabled() {
  void saveCredential({ enabled: credential.value?.status !== 'ENABLED', ipWhitelist: whitelistRows() })
}
</script>
