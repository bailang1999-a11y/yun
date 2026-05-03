<template>
  <WebShell>
    <section class="recharge-page">
      <div class="page-heading">
        <div>
          <p>账户充值</p>
          <h1>余额充值</h1>
        </div>
        <RouterLink class="ghost-button" to="/account">返回会员中心</RouterLink>
      </div>

      <div class="recharge-grid">
        <section class="member-card recharge-summary">
          <p>当前余额</p>
          <h1>¥{{ (session.profile?.balance || 0).toFixed(2) }}</h1>
          <span>{{ session.profile?.groupName || '默认会员' }}</span>
        </section>

        <form class="settings-panel recharge-panel" @submit.prevent="submit">
          <div class="settings-section-title">
            <strong>充值金额</strong>
            <span>选择预设金额或手动填写</span>
          </div>

          <div class="amount-grid">
            <button
              v-for="item in amounts"
              :key="item"
              type="button"
              class="amount-chip"
              :class="{ active: amount === item }"
              @click="amount = item"
            >
              ¥{{ item }}
            </button>
          </div>

          <label>
            <span>自定义金额</span>
            <input v-model.number="amount" type="number" min="1" step="1" placeholder="请输入充值金额" />
          </label>

          <div class="settings-section-title">
            <strong>付款方式</strong>
            <span>接口接入后生成支付单</span>
          </div>

          <div class="pay-methods">
            <button type="button" class="amount-chip" :class="{ active: payMethod === 'alipay' }" @click="payMethod = 'alipay'">支付宝</button>
            <button type="button" class="amount-chip" :class="{ active: payMethod === 'wechat' }" @click="payMethod = 'wechat'">微信支付</button>
            <button type="button" class="amount-chip" :class="{ active: payMethod === 'bank' }" @click="payMethod = 'bank'">线下转账</button>
          </div>

          <label>
            <span>备注</span>
            <textarea v-model.trim="remark" rows="3" placeholder="付款账号、转账备注等（选填）" />
          </label>

          <p v-if="result" class="success-line">充值请求 {{ result.requestNo }} 已创建，金额 ¥{{ result.amount.toFixed(2) }}。</p>
          <p v-if="error" class="alert-line">{{ error }}</p>
          <button class="primary-button wide" type="submit" :disabled="submitting">{{ submitting ? '提交中...' : '提交充值' }}</button>
        </form>
      </div>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import { createRechargeRequest } from '../api/web'
import { useSessionStore } from '../stores/session'
import type { RechargeRequestResult } from '../types/web'

const session = useSessionStore()
const amounts = [50, 100, 200, 500, 1000, 2000]
const amount = ref(100)
const payMethod = ref('alipay')
const remark = ref('')
const submitting = ref(false)
const error = ref('')
const result = ref<RechargeRequestResult | null>(null)

async function submit() {
  submitting.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await createRechargeRequest({ amount: Number(amount.value), payMethod: payMethod.value, remark: remark.value })
  } catch (err) {
    error.value = err instanceof Error ? err.message : '充值请求提交失败。'
  } finally {
    submitting.value = false
  }
}
</script>
