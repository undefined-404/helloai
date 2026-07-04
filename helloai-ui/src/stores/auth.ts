import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 认证状态管理。
 * 管理 admin token / agent key 的存储、读取、清除。
 */
export const useAuthStore = defineStore('auth', () => {
  const adminToken = ref(sessionStorage.getItem('adminToken') || '')
  const agentKey = ref(sessionStorage.getItem('agentKey') || '')
  const adminUser = ref(sessionStorage.getItem('adminUser') || '')

  const isLoggedIn = computed(() => !!adminToken.value || !!agentKey.value)
  const isAdmin = computed(() => !!adminToken.value)

  function setAdmin(token: string, username: string) {
    adminToken.value = token
    adminUser.value = username
    sessionStorage.setItem('adminToken', token)
    sessionStorage.setItem('adminUser', username)
    sessionStorage.removeItem('agentKey')
  }

  function setAgent(key: string) {
    agentKey.value = key
    sessionStorage.setItem('agentKey', key)
    sessionStorage.removeItem('adminToken')
    sessionStorage.removeItem('adminUser')
  }

  function logout() {
    adminToken.value = ''
    agentKey.value = ''
    adminUser.value = ''
    sessionStorage.clear()
    window.location.hash = '#/login'
  }

  return { adminToken, agentKey, adminUser, isLoggedIn, isAdmin, setAdmin, setAgent, logout }
})
