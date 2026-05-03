<template>
  <WebShell>
    <section class="api-page">
      <div class="page-heading">
        <div>
          <p>会员 API</p>
          <h1>一键发货接口</h1>
        </div>
        <StatusBadge :status="credential?.status" />
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
            <button class="ghost-button" type="button">重置密钥</button>
            <button class="primary-button" type="button">保存白名单</button>
          </div>
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
import { fetchApiCredential } from '../api/web'
import { useSessionStore } from '../stores/session'
import type { ApiCredential } from '../types/web'

const session = useSessionStore()
const credential = ref<ApiCredential | null>(null)
const ipWhitelist = ref('')

onMounted(async () => {
  credential.value = await fetchApiCredential(session.profile)
  ipWhitelist.value = credential.value.ipWhitelist.join('\n')
})
</script>
