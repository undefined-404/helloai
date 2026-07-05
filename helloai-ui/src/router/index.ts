import { createRouter, createWebHashHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

const routes = [
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
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard',  component: () => import('@/views/Dashboard.vue'),  meta: { title: '概览' } },
      { path: 'tasks',      component: () => import('@/views/task/TaskList.vue'), meta: { title: '任务管理' } },
      { path: 'sub-tasks',  component: () => import('@/views/subtask/SubTaskList.vue'), meta: { title: '子任务' } },
      { path: 'sub-tasks/:id', component: () => import('@/views/subtask/SubTaskDetail.vue'), meta: { title: '子任务详情' } },
      { path: 'agents',     component: () => import('@/views/agent/AgentList.vue'), meta: { title: 'Agent管理' } },
      { path: 'agents/:id', component: () => import('@/views/agent/AgentDetail.vue'), meta: { title: 'Agent详情' } },
      { path: 'reviews',    component: () => import('@/views/review/ReviewList.vue'), meta: { title: '审查中心' } },
      { path: 'rewards',    component: () => import('@/views/reward/RewardList.vue'), meta: { title: '积分流水' } },
      { path: 'activity',   component: () => import('@/views/activity/ActivityList.vue'), meta: { title: '活动流' } },
      { path: 'rules',      component: () => import('@/views/rule/RuleList.vue'), meta: { title: '规则配置' } },
      { path: 'prompts',    component: () => import('@/views/prompt/PromptList.vue'), meta: { title: 'Prompt 管理' } },
      { path: 'inbox',      component: () => import('@/views/inbox/AgentInbox.vue'), meta: { title: '收件箱' } },
      { path: 'attachments', component: () => import('@/views/attachment/AttachmentList.vue'), meta: { title: '附件管理' } },
      { path: 'settings',   component: () => import('@/views/Settings.vue'),  meta: { title: '系统设置' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const hasAdminToken = !!sessionStorage.getItem('adminToken')
  const hasAgentKey = !!sessionStorage.getItem('agentKey')

  if (to.path !== '/login' && to.path !== '/setup' && !hasAdminToken && !hasAgentKey) {
    return '/login'
  }

  if (to.path === '/settings' && hasAgentKey && !hasAdminToken) {
    return '/inbox'
  }
})

export default router
