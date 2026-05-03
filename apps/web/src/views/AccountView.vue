<template>
  <WebShell>
    <section class="account-grid">
      <div class="member-card">
        <p>会员中心</p>
        <h1>{{ session.displayName }}</h1>
        <div class="balance-large">¥{{ (session.profile?.balance || 0).toFixed(2) }}</div>
        <span>{{ session.profile?.groupName || '默认会员' }}</span>
        <RouterLink class="primary-button recharge-button" to="/account/recharge">账户充值</RouterLink>
      </div>

      <RouterLink class="account-tile" to="/account/recharge">
        <em>TOPUP</em>
        <strong>余额充值</strong>
        <span>提交充值金额与付款方式</span>
      </RouterLink>
      <RouterLink class="account-tile" to="/orders">
        <em>ORDER</em>
        <strong>订单中心</strong>
        <span>查看购买记录与发货信息</span>
      </RouterLink>
      <RouterLink class="account-tile" to="/account/api">
        <em>API</em>
        <strong>API 管理</strong>
        <span>淘宝、抖音等平台一键发货凭证</span>
      </RouterLink>
      <RouterLink class="account-tile" to="/account/profile">
        <em>USER</em>
        <strong>个人资料</strong>
        <span>账号资料与基础设置</span>
      </RouterLink>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import WebShell from '../components/WebShell.vue'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
</script>
