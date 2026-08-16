import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 登录态类型：
 * - admin：平台管理员（账号密码登录）
 * - agent：外部 Agent（API Key 登录）
 */
export type LoginType = 'admin' | 'agent'

const KEY_ADMIN_TOKEN = 'adminToken'
const KEY_ADMIN_USER = 'adminUser'
const KEY_AGENT_KEY = 'agentKey'
const KEY_AGENT_NAME = 'agentName'
const KEY_LOGIN_TYPE = 'loginType'

/**
 * 认证状态管理。
 * 所有 sessionStorage 读写都集中在 store 里；调用方只通过 setAdmin / setAgent / logout 变更状态。
 * store 初始化时从 sessionStorage 同步一次历史值。
 */
export const useAuthStore = defineStore('auth', () => {
  const adminToken = ref(sessionStorage.getItem(KEY_ADMIN_TOKEN) || '')
  const adminUser = ref(sessionStorage.getItem(KEY_ADMIN_USER) || '')
  const agentKey = ref(sessionStorage.getItem(KEY_AGENT_KEY) || '')
  const agentName = ref(sessionStorage.getItem(KEY_AGENT_NAME) || '')
  // 兼容历史值：store 接管前已经写过 loginType / 仅凭 token/key 推断
  const loginType = ref<LoginType>(
    (sessionStorage.getItem(KEY_LOGIN_TYPE) as LoginType | null)
      || (agentKey.value ? 'agent' : 'admin')
  )

  const isLoggedIn = computed(() => !!adminToken.value || !!agentKey.value)
  const isAdmin = computed(() => !!adminToken.value)
  const isAgent = computed(() => !!agentKey.value && !adminToken.value)
  // 给 template 用的展示名：admin 取 adminUser / 'Admin'，agent 取 agentName / 'Agent'
  const displayName = computed(() => {
    if (isAdmin.value) return adminUser.value || 'Admin'
    if (isAgent.value) return agentName.value || 'Agent'
    return ''
  })

  function setAdmin(token: string, username: string) {
    adminToken.value = token
    adminUser.value = username
    sessionStorage.setItem(KEY_ADMIN_TOKEN, token)
    sessionStorage.setItem(KEY_ADMIN_USER, username)
    sessionStorage.removeItem(KEY_AGENT_KEY)
    sessionStorage.removeItem(KEY_AGENT_NAME)
    loginType.value = 'admin'
    sessionStorage.setItem(KEY_LOGIN_TYPE, 'admin')
  }

  function setAgent(key: string, name?: string) {
    agentKey.value = key
    agentName.value = name || ''
    sessionStorage.setItem(KEY_AGENT_KEY, key)
    if (name) sessionStorage.setItem(KEY_AGENT_NAME, name)
    sessionStorage.removeItem(KEY_ADMIN_TOKEN)
    sessionStorage.removeItem(KEY_ADMIN_USER)
    loginType.value = 'agent'
    sessionStorage.setItem(KEY_LOGIN_TYPE, 'agent')
  }

  function logout() {
    adminToken.value = ''
    adminUser.value = ''
    agentKey.value = ''
    agentName.value = ''
    loginType.value = 'admin'
    sessionStorage.clear()
    window.location.hash = '#/login'
  }

  return {
    adminToken,
    adminUser,
    agentKey,
    agentName,
    loginType,
    isLoggedIn,
    isAdmin,
    isAgent,
    displayName,
    setAdmin,
    setAgent,
    logout
  }
})

/**
 * 兼容层：极少数旧组件仍直接读 sessionStorage 时，用这些函数集中读写，
 * 不要再在业务代码里散落 sessionStorage.getItem('adminToken') 等。
 */
export const authStorage = {
  getAdminToken: () => sessionStorage.getItem(KEY_ADMIN_TOKEN),
  getAgentKey: () => sessionStorage.getItem(KEY_AGENT_KEY),
  getLoginType: () => sessionStorage.getItem(KEY_LOGIN_TYPE) as LoginType | null,
  clear: () => {
    sessionStorage.removeItem(KEY_ADMIN_TOKEN)
    sessionStorage.removeItem(KEY_ADMIN_USER)
    sessionStorage.removeItem(KEY_AGENT_KEY)
    sessionStorage.removeItem(KEY_AGENT_NAME)
    sessionStorage.removeItem(KEY_LOGIN_TYPE)
  }
}