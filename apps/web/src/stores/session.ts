import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchMe, loginWeb } from '../api/web'
import { tokenKey } from '../api/client'
import type { UserProfile } from '../types/web'

const profileKey = 'xiyiyun_web_profile'

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
    const cachedProfile = localStorage.getItem(profileKey)
    if (cachedProfile) {
      try {
        profile.value = JSON.parse(cachedProfile) as UserProfile
      } catch {
        localStorage.removeItem(profileKey)
      }
    }
    restored.value = true
  }

  function persist(nextToken: string, nextProfile: UserProfile) {
    token.value = nextToken
    profile.value = nextProfile
    localStorage.setItem(tokenKey, nextToken)
    localStorage.setItem(profileKey, JSON.stringify(nextProfile))
  }

  async function login(account: string, code: string) {
    const session = await loginWeb(account, code)
    persist(session.token, session.profile)
    return session.profile
  }

  async function ensureProfile() {
    if (!token.value || profileLoading.value) return profile.value
    profileLoading.value = true
    try {
      const nextProfile = await fetchMe()
      profile.value = nextProfile
      localStorage.setItem(profileKey, JSON.stringify(nextProfile))
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
    localStorage.removeItem(profileKey)
  }

  return { token, profile, profileLoading, isLoggedIn, displayName, restore, login, ensureProfile, logout }
})
