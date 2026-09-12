import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import MainLayout from '@/layouts/MainLayout.vue'
import NotFound from '@/views/NotFound.vue'
import { useAuthStore } from '@/stores/auth'
import { rbacApi } from '@/api/rbac'
import { buildDynamicRoutes, AGENT_STATIC_ROUTES, removeDynamicRoutes } from './dynamic'

/**
 * 静态白名单路由（BASE-1.3）：初始化向导 / 登录 / 404 兜底。
 * 业务页面一律动态注册：admin 按菜单树（权限=路由可达性，无权限 URL → 404），
 * agent 注入固定业务路由集合。
 */
const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/setup',
    component: () => import('@/views/setup/SetupWizard.vue'),
    meta: { title: '初始化向导' }
  },
  {
    path: '/login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/404',
    component: NotFound,
    meta: { title: '页面不存在' }
  },
  // 兜底：未注册路径（含无权限 URL）渲染 404 页。
  // 注意：不能用 redirect——redirect 在路由解析阶段即触发，守卫看到的 to 会变成 /404，
  // 导致「首次导航 → 守卫构建动态路由 → 重入原目标」链路丢失（表现为登录后落 404）。
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFound,
    meta: { title: '页面不存在' }
  }
]

const routes: RouteRecordRaw[] = [
  ...staticRoutes,
  {
    path: '/',
    name: 'root',
    component: MainLayout,
    redirect: '/dashboard',
    // 业务页面动态注入到 MainLayout 根下
    children: []
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/** 动态路由构建状态：按登录 token 跟踪，登出/切换账号时重建（避免权限串用） */
let routesBuilt = false
let builtForType: 'admin' | 'agent' | null = null
let builtForToken = ''

async function ensureDynamicRoutes(auth: ReturnType<typeof useAuthStore>): Promise<boolean> {
  const type: 'admin' | 'agent' | null = !!auth.adminToken ? 'admin' : !!auth.agentKey ? 'agent' : null
  if (type === null) return false
  const token = auth.adminToken || auth.agentKey || ''
  // 同一登录态（类型 + token 一致）已构建 → 直接复用；否则重建（登出/切换账号）
  if (routesBuilt && builtForType === type && builtForToken === token) return true

  // 登录类型切换 / 登出后重建：先清理旧动态路由再注入
  if (routesBuilt) {
    removeDynamicRoutes(router)
  }
  if (type === 'admin') {
    // 权限 = 路由可达性：菜单树（已按权限码过滤）决定注册哪些路由
    const menus = await rbacApi.menuTree()
    const dynamic = buildDynamicRoutes(menus)
    // 挂到 MainLayout 根路由（name='root'）下；addRoute 第一参数须为父路由 name
    dynamic.forEach((r) => router.addRoute('root', r))
  } else {
    AGENT_STATIC_ROUTES.forEach((r) => router.addRoute('root', r))
  }
  routesBuilt = true
  builtForType = type
  builtForToken = token
  return true
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 白名单：初始化向导 / 登录
  if (to.path === '/login' || to.path === '/setup') {
    return true
  }
  // 未登录 → 登录页
  if (!auth.isLoggedIn) {
    return '/login'
  }

  // 已登录：确保动态路由就绪（首次进入 / 登录类型变化时构建）
  if (!routesBuilt || builtForType !== (!!auth.adminToken ? 'admin' : 'agent')) {
    try {
      const ok = await ensureDynamicRoutes(auth)
      if (ok) {
        // 构建完成需重入当前导航，让新增路由参与匹配。
        // 注意：只带 path/query/hash 重入——若用 {...to} 会携带兜底路由（catch-all）的 name，
        // 导致按 name 重新解析仍命中兜底路由（表现为登录后落 404）。
        return { path: to.path, query: to.query, hash: to.hash, replace: true }
      }
    } catch (e) {
      console.error('[router] 动态路由构建失败', e)
      // 会话失效（HTTP 401，request 拦截器已清登录态）或登录态已空：跳登录页，
      // 避免放行后目标路由未注册而落 404，让用户误以为“页面不存在/无权访问”。
      const status = (e as { response?: { status?: number } })?.response?.status
      if (status === 401 || !auth.isLoggedIn) {
        return '/login'
      }
      // 其他失败（网络抖动等）：重试一次，成功则正常重入
      try {
        await ensureDynamicRoutes(auth)
        return { path: to.path, query: to.query, hash: to.hash, replace: true }
      } catch (e2) {
        console.error('[router] 动态路由重试仍失败', e2)
        // 明确提示加载失败（区别于真正的 404 无权限），用户可刷新重试
        ElMessage.error('菜单加载失败，请刷新页面重试')
      }
    }
  }
  return true
})

export default router
