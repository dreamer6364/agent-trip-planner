import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import type { UserProfile } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('tf_token') || '')
  const refreshToken = ref(localStorage.getItem('tf_refresh') || '')
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const userName = computed(() => user.value?.name || '')
  const userInitial = computed(() => userName.value.charAt(0).toUpperCase() || '?')

  function saveTokens(access: string, refresh: string) {
    token.value = access
    refreshToken.value = refresh
    localStorage.setItem('tf_token', access)
    localStorage.setItem('tf_refresh', refresh)
  }

  function clearTokens() {
    token.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem('tf_token')
    localStorage.removeItem('tf_refresh')
  }

  async function login(email: string, password: string, rememberMe = false) {
    loading.value = true
    try {
      const data = await authApi.login({ email, password, rememberMe })
      saveTokens(data.accessToken, data.refreshToken)
      user.value = data.user
      return data
    } finally {
      loading.value = false
    }
  }

  async function register(name: string, email: string, password: string, confirmPassword: string) {
    loading.value = true
    try {
      const data = await authApi.register({ name, email, password, confirmPassword })
      saveTokens(data.accessToken, data.refreshToken)
      user.value = data.user
      return data
    } finally {
      loading.value = false
    }
  }

  async function fetchUser() {
    if (!token.value) return
    try {
      user.value = await authApi.getProfile()
    } catch {
      clearTokens()
    }
  }

  async function updateProfile(data: { name?: string; avatarUrl?: string }) {
    user.value = await authApi.updateProfile(data)
    return user.value
  }

  function logout() {
    authApi.logout().catch(() => {})
    clearTokens()
  }

  return {
    token, refreshToken, user, loading,
    isLoggedIn, userName, userInitial,
    login, register, fetchUser, updateProfile, logout, saveTokens, clearTokens,
  }
})
