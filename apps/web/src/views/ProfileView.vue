<template>
  <WebShell>
    <section class="settings-page">
      <div class="page-heading">
        <div>
          <p>个人资料</p>
          <h1>账号设置</h1>
        </div>
      </div>
      <form class="settings-panel">
        <div class="settings-section-title">
          <strong>基础资料</strong>
          <span>当前接口仅展示账号资料</span>
        </div>
        <label>
          <span>昵称</span>
          <input :value="session.profile?.nickname || ''" readonly />
        </label>
        <label>
          <span>手机号</span>
          <input :value="session.profile?.mobile || ''" readonly />
        </label>
        <label>
          <span>邮箱</span>
          <input :value="session.profile?.email || ''" readonly />
        </label>
        <label>
          <span>会员组</span>
          <input :value="session.profile?.groupName || '默认会员'" readonly />
        </label>
      </form>

      <form class="settings-panel password-panel" @submit.prevent="submitPassword">
        <div class="settings-section-title">
          <strong>修改密码</strong>
          <span>建议使用 8 位以上字母、数字组合</span>
        </div>
        <label>
          <span>当前密码</span>
          <input v-model.trim="passwordForm.currentPassword" type="password" autocomplete="current-password" placeholder="请输入当前密码" />
        </label>
        <label>
          <span>新密码</span>
          <input v-model.trim="passwordForm.newPassword" type="password" autocomplete="new-password" placeholder="至少 6 位" />
        </label>
        <label>
          <span>确认新密码</span>
          <input v-model.trim="passwordForm.confirmPassword" type="password" autocomplete="new-password" placeholder="请再次输入新密码" />
        </label>
        <p v-if="passwordMessage" class="success-line">{{ passwordMessage }}</p>
        <p v-if="passwordError" class="alert-line">{{ passwordError }}</p>
        <button class="primary-button" type="submit" :disabled="passwordSaving">{{ passwordSaving ? '保存中...' : '保存新密码' }}</button>
      </form>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import WebShell from '../components/WebShell.vue'
import { changePassword } from '../api/web'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const passwordSaving = ref(false)
const passwordMessage = ref('')
const passwordError = ref('')
const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

async function submitPassword() {
  passwordSaving.value = true
  passwordMessage.value = ''
  passwordError.value = ''
  try {
    await changePassword(passwordForm)
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordMessage.value = '密码修改请求已提交。'
  } catch (err) {
    passwordError.value = err instanceof Error ? err.message : '密码修改失败，请稍后重试。'
  } finally {
    passwordSaving.value = false
  }
}
</script>
