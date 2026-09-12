// ============================================================
// 动态路由构建（BASE-1.3）：权限 = 路由可达性
// 菜单树接口（/api/admin/menus/tree）下发用户可见菜单，
// 本模块据此生成 Vue Router 路由并 addRoute 到 MainLayout 根下。
// - 叶子菜单（有 component）→ 独立路由（path 绝对，与原静态路由一致）
// - 聚合父菜单（无 component，如 settings）→ redirect 到第一个可见子
// - path 含 '?' 的菜单（如死信池 /sub-tasks?status=...）仅侧边栏入口，不注册路由
// - 依赖父菜单的详情路由（/sub-tasks/:id 等）跟随父注册，无权限则一并不可达
// ============================================================

import type { RouteRecordRaw, Router } from 'vue-router'
import type { SysPermission } from '@/types'
import { lazyLoad } from './views'

/** 动态路由 name 前缀（用于登出/切换登录类型时 removeRoute 清理） */
export const DYNAMIC_ROUTE_PREFIX = 'dyn-'

/** 依赖父菜单的详情路由：仅当父菜单 path 已在动态路由中注册后注入 */
const DETAIL_ROUTES: Array<{ parentPath: string; routes: RouteRecordRaw[] }> = [
  {
    parentPath: '/sub-tasks',
    routes: [
      {
        path: '/sub-tasks/:id',
        component: () => import('@/views/subtask/SubTaskDetail.vue'),
        name: `${DYNAMIC_ROUTE_PREFIX}sub-tasks-id`,
        meta: { title: '子任务详情', hidden: true }
      }
    ]
  },
  {
    parentPath: '/agents',
    routes: [
      {
        path: '/agents/:id',
        component: () => import('@/views/agent/AgentDetail.vue'),
        name: `${DYNAMIC_ROUTE_PREFIX}agents-id`,
        meta: { title: 'Agent 详情', hidden: true }
      }
    ]
  }
]

/** 外部 Agent（API Key 登录）的固定业务路由：与原静态业务页面等价，不随菜单过滤 */
export const AGENT_STATIC_ROUTES: RouteRecordRaw[] = [
  { path: '/dashboard', component: () => import('@/views/Dashboard.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-dashboard`, meta: { title: '概览' } },
  { path: '/tasks', component: () => import('@/views/task/TaskList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-tasks`, meta: { title: '任务管理' } },
  { path: '/requirement-chat', component: () => import('@/views/requirement/RequirementChat.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-requirement-chat`, meta: { title: '对话新建' } },
  { path: '/sub-tasks', component: () => import('@/views/subtask/SubTaskList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-sub-tasks`, meta: { title: '子任务' } },
  { path: '/sub-tasks/:id', component: () => import('@/views/subtask/SubTaskDetail.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-sub-tasks-id`, meta: { title: '子任务详情' } },
  { path: '/agents', component: () => import('@/views/agent/AgentList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-agents`, meta: { title: 'Agent 管理' } },
  { path: '/agents/:id', component: () => import('@/views/agent/AgentDetail.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-agents-id`, meta: { title: 'Agent 详情' } },
  { path: '/teams', component: () => import('@/views/team/TeamList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-teams`, meta: { title: 'Team 组合' } },
  { path: '/browser-sessions', component: () => import('@/views/browser/BrowserSessionList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-browser-sessions`, meta: { title: 'Browser 会话' } },
  { path: '/reviews', component: () => import('@/views/review/ReviewList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-reviews`, meta: { title: '审查中心' } },
  { path: '/event-stream', component: () => import('@/views/event/EventStreamWorkbench.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-event-stream`, meta: { title: '事件流' } },
  { path: '/quality-dashboard', component: () => import('@/views/quality/QualityDashboard.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-quality`, meta: { title: '质量看板' } },
  { path: '/rewards', component: () => import('@/views/reward/RewardList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-rewards`, meta: { title: '积分流水' } },
  { path: '/activity', component: () => import('@/views/activity/ActivityList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-activity`, meta: { title: '活动流' } },
  { path: '/rules', component: () => import('@/views/rule/RuleList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-rules`, meta: { title: '规则配置' } },
  { path: '/duty-leases', component: () => import('@/views/duty/DutyLeaseList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-duty`, meta: { title: '打卡上班' } },
  { path: '/inbox', component: () => import('@/views/inbox/AgentInbox.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-inbox`, meta: { title: '收件箱' } },
  { path: '/attachments', component: () => import('@/views/attachment/AttachmentList.vue'), name: `${DYNAMIC_ROUTE_PREFIX}agent-attachments`, meta: { title: '附件管理' } }
]

/**
 * 由用户可见菜单树生成动态路由列表（admin 场景）。
 *
 * @returns 需 addRoute 到 '/'（MainLayout 根）下的路由数组
 */
export function buildDynamicRoutes(menus: SysPermission[]): RouteRecordRaw[] {
  const routes: RouteRecordRaw[] = []
  const registered = new Set<string>()

  // 递归：返回该子树已注册的叶子 path 列表（供聚合父节点 redirect 使用）
  const walk = (nodes: SysPermission[]): string[] => {
    const leafPaths: string[] = []
    for (const n of nodes) {
      // path 含 '?'（死信池 /sub-tasks?status=...）仅侧边栏入口，不注册路由
      if (!n.path || n.path.includes('?')) continue
      const childLeafs = n.children?.length ? walk(n.children) : []
      // 外链菜单（BASE-3.1）：点击新窗口打开，不注册前端路由；子节点若有仍照常注册
      if (n.externalLink) {
        leafPaths.push(...childLeafs)
        continue
      }
      if (n.component) {
        const loader = lazyLoad(n.component)
        if (loader) {
          const name = `${DYNAMIC_ROUTE_PREFIX}${n.path.replace(/\W+/g, '-')}`
          routes.push({
            path: n.path,
            name,
            component: loader,
            meta: {
              title: n.name,
              icon: n.icon,
              code: n.code,
              // 隐藏菜单：路由可达但侧边栏不显示；keepAlive：页面缓存
              hidden: n.hidden === 1,
              keepAlive: n.keepAlive === 1
            }
          })
          registered.add(n.path)
          leafPaths.push(n.path)
        }
      } else if (n.children?.length && childLeafs.length > 0) {
        // 聚合父节点：redirect 到第一个可见子（如 /settings → /system/users）
        const name = `${DYNAMIC_ROUTE_PREFIX}group-${n.path.replace(/\W+/g, '-')}`
        routes.push({
          path: n.path,
          name,
          redirect: childLeafs[0],
          meta: { title: n.name, icon: n.icon, hidden: n.hidden === 1 }
        })
        registered.add(n.path)
        leafPaths.push(n.path)
      }
    }
    return leafPaths
  }
  walk(menus)

  // 详情路由跟随父菜单注入
  for (const d of DETAIL_ROUTES) {
    if (registered.has(d.parentPath)) {
      routes.push(...d.routes)
    }
  }
  return routes
}

/**
 * 清理动态路由（登录类型切换 / 登出时调用）。
 * 只移除 name 带 DYNAMIC_ROUTE_PREFIX 的路由，保留静态白名单与 '/' 根路由。
 */
export function removeDynamicRoutes(router: Router): void {
  router.getRoutes().forEach((r) => {
    if (typeof r.name === 'string' && r.name.startsWith(DYNAMIC_ROUTE_PREFIX)) {
      router.removeRoute(r.name)
    }
  })
}
