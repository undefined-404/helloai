// 后端 API 路径常量收口（对照 CODE_STYLE §10.2 路由风格）。
// 所有 api/*.ts 一律引用本文件，禁止内联路径字符串。

/** 路径参数编码：LongId 传 string 防 JS Number 精度丢失；冒号等特殊字符 encodeURIComponent。 */
const enc = (s: string | number) => encodeURIComponent(String(s))

export const paths = {
  auth: {
    login: '/auth/login',
    logout: '/auth/logout',
    changePassword: '/auth/changePassword',
    me: '/auth/me'
  },
  tasks: {
    list: '/tasks/list',
    getById: (id: string | number) => `/tasks/getById/${enc(id)}`,
    create: '/tasks',
    update: (id: string | number) => `/tasks/updateById/${enc(id)}`,
    republish: (id: string | number) => `/tasks/republishById/${enc(id)}`,
    relatedCounts: (id: string | number) => `/tasks/listRelatedCountsByTaskId/${enc(id)}`,
    // 级联删除: 需 confirmTitle body 确认（DELETE 带 body 不符合语义，特例用 POST）
    deleteById: (id: string | number) => `/tasks/deleteById/${enc(id)}`,
    updateStatusById: (id: string | number) => `/tasks/updateStatusById/${enc(id)}`,
    plan: (id: string | number) => `/tasks/planById/${enc(id)}`,
    planDrafts: (id: string | number) => `/tasks/findPlanByTaskId/${enc(id)}`,
    confirmPlan: (id: string | number) => `/tasks/confirmPlanByTaskId/${enc(id)}`,
    rejectPlan: (id: string | number) => `/tasks/rejectPlanByTaskId/${enc(id)}`,
    downloadDeliverables: (id: string | number) => `/tasks/downloadDeliverablesByTaskId/${enc(id)}`,
    finalReport: (id: string | number) => `/tasks/findFinalReportByTaskId/${enc(id)}`,
    generateFinalReport: (id: string | number) => `/tasks/generateFinalReportByTaskId/${enc(id)}`,
    iterations: (id: string | number) => `/tasks/findTaskIterationsByTaskId/${enc(id)}`,
    backfillIterations: '/tasks/backfillTaskIterations'
  },
  subTasks: {
    list: '/sub-tasks/list',
    getById: (id: string | number) => `/sub-tasks/getById/${enc(id)}`,
    create: '/sub-tasks',
    batch: '/sub-tasks/batch',
    timeline: (id: string | number) => `/sub-tasks/listTimelineBySubTaskId/${enc(id)}`,
    conversation: (id: string | number) => `/sub-tasks/listConversationBySubTaskId/${enc(id)}`,
    redispatchDeadLetter: (id: string | number) => `/sub-tasks/redispatchDeadLetterById/${enc(id)}`,
    reassign: (id: string | number) => `/sub-tasks/reassignById/${enc(id)}`,
    changeStatus: '/sub-tasks/changeStatus',
    claim: (id: string | number) => `/sub-tasks/claimById/${enc(id)}`,
    start: (id: string | number) => `/sub-tasks/startById/${enc(id)}`,
    submit: (id: string | number) => `/sub-tasks/submitById/${enc(id)}`,
    block: (id: string | number) => `/sub-tasks/blockById/${enc(id)}`,
    mine: '/sub-tasks/listMine',
    available: '/sub-tasks/listAvailable',
    pause: (id: string | number) => `/sub-tasks/pauseById/${enc(id)}`,
    resume: (id: string | number) => `/sub-tasks/resumeById/${enc(id)}`
  },
  agents: {
    list: '/agents/list',
    listAvailableModels: '/agents/listAvailableModels',
    getById: (id: string | number) => `/agents/getById/${enc(id)}`,
    register: '/agents/register',
    skillOptions: (modelType: string) => `/admin/llm-providers/${enc(modelType)}/skill-options`,
    adminList: '/admin/agents/list',
    adminDetail: (id: string | number) => `/admin/agents/getById/${enc(id)}`,
    updateProfile: (id: string | number) => `/admin/agents/updateById/${enc(id)}`,
    updateStatus: (id: string | number) => `/admin/agents/updateStatusById/${enc(id)}`,
    resetKey: (id: string | number) => `/admin/agents/resetKeyById/${enc(id)}`,
    relatedCounts: (id: string | number) => `/admin/agents/listRelatedCountsByAgentId/${enc(id)}`,
    deleteById: (id: string | number) => `/admin/agents/deleteById/${enc(id)}`,
    scoreLogs: (id: string | number) => `/admin/agents/listScoreLogsByAgentId/${enc(id)}`,
    activityLogs: (id: string | number) => `/admin/agents/listActivityLogsByAgentId/${enc(id)}`,
    onboarding: (id: string | number) => `/admin/agents/getOnboardingContentByAgentId/${enc(id)}`
  },
  setup: {
    getStatus: '/setup/getStatus',
    initialize: '/setup/initialize'
  },
  admin: {
    config: '/admin/config',
    configByKey: (key: string) => `/admin/config/getByKey/${enc(key)}`,
    configUpdateByKey: (key: string) => `/admin/config/updateByKey/${enc(key)}`,
    configBatch: '/admin/config/batch',
    // 旧平台 Provider 端点（保留兼容）
    platformProviders: '/admin/platform/providers/list',
    platformProviderApiKey: (provider: string) => `/admin/platform/providers/${enc(provider)}/api-key`,
    platformProviderSettings: (provider: string) => `/admin/platform/providers/${enc(provider)}/settings`,
    // 新方案B LLM Provider 端点
    llmProviders: '/admin/llm-providers/list',
    llmProviderById: (id: string | number) => `/admin/llm-providers/getById/${enc(id)}`,
    llmProviderCreate: '/admin/llm-providers',
    llmProviderUpdate: (id: string | number) => `/admin/llm-providers/updateById/${enc(id)}`,
    llmProviderDelete: (id: string | number) => `/admin/llm-providers/deleteById/${enc(id)}`,
    llmProviderToggle: (id: string | number) => `/admin/llm-providers/toggleById/${enc(id)}`,
    llmProviderApiKey: (id: string | number) => `/admin/llm-providers/${enc(id)}/api-key`,
    llmProviderModels: (id: string | number) => `/admin/llm-providers/${enc(id)}/models/list`,
    llmProviderModelCreate: (id: string | number) => `/admin/llm-providers/${enc(id)}/models`,
    llmProviderModelsSaveAll: (id: string | number) => `/admin/llm-providers/${enc(id)}/models/saveAll`,
    llmProviderModelDelete: (id: string | number, name: string) => `/admin/llm-providers/${enc(id)}/models/deleteByName/${enc(name)}`,
    llmProviderModelToggle: (id: string | number, name: string) => `/admin/llm-providers/${enc(id)}/models/toggleByName/${enc(name)}`,
    llmProviderModelSetDefault: (id: string | number, name: string) => `/admin/llm-providers/${enc(id)}/models/setDefaultByName/${enc(name)}`,
    prompts: '/admin/prompts',
    promptById: (id: string | number) => `/admin/prompts/getById/${enc(id)}`,
    promptDefault: '/admin/prompts/getDefaultByRole',
    promptUpdate: (id: string | number) => `/admin/prompts/updateById/${enc(id)}`,
    promptDelete: (id: string | number) => `/admin/prompts/deleteById/${enc(id)}`,
    promptCompose: '/admin/prompts/compose',
    dutyLeases: '/admin/duty-leases',
    dutyLeasesByAgent: '/admin/duty-leases/listByAgent',
    dutyLeasesOverview: '/admin/duty-leases/getOverview'
  },
  activity: {
    list: '/activity/list'
  },
  attachments: {
    list: '/attachments',
    getById: (id: string | number) => `/attachments/getById/${enc(id)}`,
    download: (id: string | number) => `/attachments/downloadById/${enc(id)}`,
    preview: (id: string | number) => `/attachments/previewById/${enc(id)}`
  },
  clarifications: {
    create: '/requirement-conversations',
    send: (id: string | number) => `/requirement-conversations/sendMessageById/${enc(id)}`,
    retry: (id: string | number) => `/requirement-conversations/retryById/${enc(id)}`,
    toClarify: (id: string | number) => `/requirement-conversations/toClarifyById/${enc(id)}`,
    toChat: (id: string | number) => `/requirement-conversations/toChatById/${enc(id)}`,
    plannerOptions: '/requirement-conversations/listPlannerOptions',
    list: '/requirement-conversations',
    detail: (id: string | number) => `/requirement-conversations/getById/${enc(id)}`,
    finalize: (id: string | number) => `/requirement-conversations/finalizeById/${enc(id)}`,
    regenerate: (id: string | number) => `/requirement-conversations/regenerateById/${enc(id)}`,
    abandon: (id: string | number) => `/requirement-conversations/abandonById/${enc(id)}`
  },
  dashboard: {
    stats: '/dashboard/getStats'
  },
  inbox: {
    list: '/agent/inbox',
    unreadCount: '/agent/inbox/getUnreadCount',
    markRead: (id: string | number) => `/agent/inbox/markReadById/${enc(id)}`,
    archive: (id: string | number) => `/agent/inbox/archiveById/${enc(id)}`
  },
  modules: {
    list: (taskId: string | number) => `/modules/findModulesByTaskId/${enc(taskId)}`,
    set: (taskId: string | number) => `/modules/setModulesByTaskId/${enc(taskId)}`
  },
  reviews: {
    list: '/reviews',
    getById: (id: string | number) => `/reviews/getById/${enc(id)}`,
    create: '/reviews'
  },
  scores: {
    me: '/scores/me',
    leaderboard: '/scores/getLeaderboard',
    logs: '/scores/listLogs',
    adjust: '/scores/adjust'
  },
  rules: {
    list: '/rules',
    getById: (id: string | number) => `/rules/getById/${enc(id)}`,
    create: '/rules',
    update: (id: string | number) => `/rules/updateById/${enc(id)}`,
    delete: (id: string | number) => `/rules/deleteById/${enc(id)}`
  }
}

