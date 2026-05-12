import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authWeb, fetchMe, loginWeb } from '../api/web'
import { tokenKey } from '../api/client'
import type { AuthPayload, UserProfile } from '../types/web'

export const useSessionStore = defineStore('session', () => {
  const token = ref('')
  const profile = ref<UserProfile | null>(null)
  const profileLoading = ref(false)
  const restored = ref(false)

  const isLoggedIn = computed(() => Boolean(token.value))
  const displayName = computed(() => profile.value?.nickname || profile.value?.mobile || '会员')

  function restore() {
    if (restored.value) return
    token.value = localStorage.getItem(tokenKey) || ''
    restored.value = true
  }

  function persist(nextToken: string, nextProfile: UserProfile) {
    token.value = nextToken
    profile.value = nextProfile
    localStorage.setItem(tokenKey, nextToken)
  }

  async function login(account: string, code: string) {
    const session = await loginWeb(account, code)
    persist(session.token, session.profile)
    return session.profile
  }

  async function auth(payload: AuthPayload) {
    const session = await authWeb(payload)
    persist(session.token, session.profile)
    return session.profile
  }

  async function ensureProfile(options: { force?: boolean } = {}) {
    if (!token.value || profileLoading.value) return profile.value
    if (profile.value && !options.force) return profile.value
    profileLoading.value = true
    try {
      const nextProfile = await fetchMe()
      profile.value = nextProfile
      return nextProfile
    } catch {
      logout()
      return null
    } finally {
      profileLoading.value = false
    }
  }

  function logout() {
    token.value = ''
    profile.value = null
    localStorage.removeItem(tokenKey)
  }

  return { token, profile, profileLoading, isLoggedIn, displayName, restore, login, auth, ensureProfile, logout }
})
