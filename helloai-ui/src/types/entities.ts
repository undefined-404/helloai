// ============================================================
// HelloAI 实体类型 — 对齐后端 com.helloai 实体
// ============================================================

import type { LongId } from './common'
import type {
  SubTaskStatus,
  TaskStatus,
  FinalReportStatus,
  AgentRole,
  AgentStatus,
  ReviewResult,
  AttachmentStatus,
  RequirementConversationStatus,
  TaskFallbackPolicy,
  TaskDifficulty,
} from './enums'

// --- 任务 ---
export interface Task {
  id: LongId
  title: string
  description: string
  status: TaskStatus
  createTime: string
  updateTime: string
  // V41: 报告生成状态（生成中时列表状态列显示"报告生成中"，报告按钮禁用）
  finalReportStatus?: FinalReportStatus
  // A0-7: 任务 SLA 分钟数（null=无时限；confirmPlan 时按确认时刻+SLA 下发子任务 deadline）
  slaMinutes?: number | null
  // V47: 任务级 Agent 指定策略（A1 起前端可编辑；键缺省即回落默认）
  agentPolicy?: TaskAgentPolicy | null
  // V47: 任务要求的能力列表（AND 语义；空=不限制）
  requiredSkills?: string[]
}

// V47 任务级 Agent 指定策略（task.agent_policy JSONB；全空=不指定，各键缺省即回落默认）
export interface TaskAgentPolicy {
  // 指定拆解/澄清 Planner（失效回退自动选择）
  plannerAgentId?: LongId | null
  // 执行者白名单（为空=不限定）
  executorAgentIds?: LongId[]
  // 指定自动核验 Reviewer（失效回退自动选择）
  reviewerAgentId?: LongId | null
  // N11 回退策略：AUTO（默认）/ RESTRICTED / NONE
  fallbackPolicy?: TaskFallbackPolicy | null
  // 任务难度：LOW / MEDIUM（默认）/ HIGH（HIGH 视为禁止 N11 自动回退）
  difficulty?: TaskDifficulty | null
}

// 任务删除前风险提示 + 删除结果回显共用（对应后端 TaskRelatedCounts）
export interface TaskRelatedCounts {
  taskId: string
  taskTitle: string
  subTaskCount: number
  // 处于 ASSIGNED/IN_PROGRESS 的子任务数，删除会丢弃其在途执行结果
  activeSubTaskCount: number
  deadLetterCount: number
  moduleCount: number
  reviewCount: number
  executionCount: number
  unreadInboxCount: number
  timelineCount: number
}

// V32 任务最终整合报告（对应后端 TaskFinalReportResponse；V41 增加生成状态）
export interface TaskFinalReport {
  taskId: LongId
  // 报告正文 Markdown；null 表示尚未生成
  content: string | null
  agentId: LongId | null
  agentName: string | null
  generatedAt: string | null
  // V41: 报告生成状态 NONE/GENERATING/DONE/FAILED
  status?: FinalReportStatus
}

// V42 任务执行迭代记录
export interface TaskIteration {
  id: LongId
  taskId: LongId
  taskCode: string         // #1, #2 ...
  taskName: string
  taskType: string         // DEVELOPMENT / TESTING / PLANNING / OTHER
  parentTaskId: LongId | null
  dependsOn: LongId[] | null
  roundNum: number
  prevTaskResult: string | null
  currentRequirement: string | null
  outputSummary: string | null  // V44 执行摘要（≤200 字）
  lastResult: string | null
  rejectionHistory: TaskIterationRejection[] | null
  llmResponse: string | null
  reviewResult: string | null  // PASSED / REJECTED / null
  executorAgent: string | null
  createTime: string
}

export interface TaskIterationRejection {
  round: number
  comment: string
  issues: string
  score: number
}

// --- 子任务 ---
export interface SubTask {
  id: LongId
  taskId: LongId
  // 主任务标题冗余字段（后端 Controller 批量回填，列表展示归属任务用）
  taskTitle?: string | null
  moduleId: LongId | null
  title: string
  status: SubTaskStatus
  assignedAgent: LongId | null
  assignedAgentName?: string
  content: string
  // V26 拆解产出字段（后端已返回，草案审阅展示用）
  deliverable?: string | null
  acceptance?: string | null
  priority?: string | null
  // V27 依赖编排：前置子任务 id 列表（全部 DONE 才分发），旧数据为空数组
  dependsOn?: LongId[]
  context: Record<string, any> | null
  scoreFactors: Record<string, any> | null
  compositeScore: number | null
  scoreGrade: string | null
  deadline: string | null
  reworkCount: number
  timeoutCount: number
  createTime: string
  updateTime: string
}

// --- Agent ---
export interface Agent {
  id: string
  name: string
  role: AgentRole
  // M4.5: 接入类型，用于前端过滤可派发 Agent（CLI_CLIENT / API_KEY_LLM / WEB_BROWSER）
  accessType?: 'CLI_CLIENT' | 'API_KEY_LLM' | 'WEB_BROWSER' | string
  modelType: string | null
  modelConfig: Record<string, any> | null
  status: AgentStatus
  score: number
  remark: string | null
  createTime: string
  // §6.52 人工介入面板：在线状态（ONLINE/IDLE/OFFLINE/SLEEPING，后端可能不返回则缺省）
  onlineStatus?: string | null
  // V47: 能力声明列表（shell / docker / code-review / web-search 等，任务 required_skills 匹配用）
  skills?: string[]
}

export interface AgentListItem {
  id: string
  name: string
  role: AgentRole
  // 接入类型：内部 LLM Agent（API_KEY_LLM）不展示接入内容入口
  accessType?: 'CLI_CLIENT' | 'API_KEY_LLM' | 'WEB_BROWSER' | string
  // V52: 内部 LLM Agent 绑定的 provider:model（编辑弹窗技能区按模型能力渲染），外部 Agent 为 null
  modelType?: string | null
  apiKey: string
  description: string
  remark?: string | null
  status: AgentStatus
  totalScore: number
  rank: number
  // workload
  assignedCount: number
  inProgressCount: number
  doneCount: number
  blockedCount: number
  reviewCount: number
  // V47/A2: 能力声明列表（任务 required_skills 匹配用，编辑弹窗回显）
  skills?: string[]
  // timeline
  lastRequestAt: string | null
  lastActivityAt: string | null
  createdAt: string
}

export interface AgentDetail extends AgentListItem {
  totalAgents: number
  rewardCount: number
  penaltyCount: number
  totalRewardRecords: number
  apiKey: string
  modelType: string | null
}

export interface AgentRelatedCounts {
  agentId: string
  agentName: string
  subTaskCount: number
  reviewCount: number
  rewardCount: number
  activityCount: number
}

export interface AgentDeleteResult {
  agentName: string
  subTaskCount: number
  reviewCount: number
  rewardCount: number
  activityCount: number
}

export interface ScoreLogItem {
  id: LongId
  agentId: string
  subTaskId: LongId | null
  reason: string
  delta: number
  balance: number
  createTime: string
}

export interface ActivityLogItem {
  id: LongId
  agentId: string
  subTaskId: LongId | null
  action: string
  summary: string
  level: string
  createTime: string
}

/** Agent 接入内容生成响应 */
export interface AgentOnboardingResponse {
  agentId: string | number
  agentName: string
  role: string
  apiKey: string
  baseUrl: string
  title: string
  content: string
  skillContent: string
}

// --- 审查 / 积分 / 活动 ---
export interface ReviewRecord {
  id: LongId
  subTaskId: LongId
  reviewerAgent: LongId
  result: ReviewResult
  score: number
  issues: string | null
  comment: string | null
  round: number
  createTime: string
}

export interface RewardLog {
  id: LongId
  agentId: LongId
  subTaskId: LongId | null
  reason: string
  delta: number
  balance: number
  createTime: string
}

export interface ActivityLog {
  id: LongId
  agentId: LongId
  subTaskId: LongId | null
  action: string
  detail: Record<string, any> | null
  createTime: string
}

export interface AgentOutboxEvent {
  id: LongId
  eventId: string
  eventType: string
  routingKey: string
  payload: Record<string, any>
  status: number
  retryCount: number
  errorMsg: string | null
  createTime: string
}

// --- Dashboard ---
export interface DashboardStats {
  totalTasks: number
  activeSubTasks: number
  pendingReviews: number
  blockedTasks: number
  agentRanking: { name: string; role: string; score: number }[]
  throughput: { date: string; count: number }[]
}

// --- 任务模块 / 时间线 / 对话 ---
export interface ModuleItem {
  id: LongId
  taskId: LongId
  name: string
  sortOrder?: number
}

export interface TaskTimelineItem {
  id: LongId
  eventType: string
  role: string | null
  agentId: LongId | null
  payload: Record<string, any> | null
  createTime: string
}

// V28 子任务执行对话流（GET /sub-tasks/{id}/conversation），toolName 区分消息来源
export interface ConversationMessageItem {
  id: LongId
  role: string
  senderType: string
  senderId: LongId | null
  content: string
  contentType: string | null
  toolName: string | null
  seq: number
  createTime: string
}

// --- 规则 / 附件 ---
export interface Rule {
  id: LongId
  name: string
  ruleType: string
  priority: number
  content: string
  remark: string | null
  createTime: string
  updateTime: string
}

export interface Attachment {
  id: LongId
  subTaskId: LongId
  fileName: string
  fileType: string
  mimeType: string
  fileSize: number
  bucketName: string
  objectKey: string
  storageUrl: string
  previewUrl: string | null
  status: AttachmentStatus
  createTime: string
  // 回填字段：主任务 ID/标题、子任务标题（附件管理层级浏览展示用）
  taskId?: LongId | null
  taskTitle?: string | null
  subTaskTitle?: string | null
}

// --- Prompt / Inbox / 对话 ---
export interface PromptTemplate {
  id: LongId
  role: string
  category: string
  slug: string | null
  name: string
  description: string | null
  content: string
  isDefault: number
  isExample: number
  version: number
  remark: string | null
  createTime: string
  updateTime: string
}

export interface AgentInbox {
  id: LongId
  agentId: string
  eventId: string
  eventType: string
  title: string
  summary: string | null
  refType: string | null
  refId: LongId | null
  isRead: number
  isArchived: number
  readAt: string | null
  priority: string
  createTime: string
}

export interface ConversationMessage {
  id: LongId
  subTaskId: LongId
  messageId: string
  role: string
  senderType: string
  senderId: LongId | null
  content: string
  contentType: string
  seq: number
  createTime: string
}

// --- 需求澄清 ---
export interface RequirementConversation {
  id: LongId
  title: string
  status: RequirementConversationStatus
  taskId: LongId | null
  // 手动指定的 Planner Agent ID（null=系统自动选择）
  plannerAgentId: LongId | null
  finalTitle: string | null
  finalDescription: string | null
  roundCount: number
  // 会话级联网搜索开关（V34；null=默认开启 / true=开启 / false=关闭；
  // 首轮 LLM 调用前若为开启则服务端预检索行业资料注入 Prompt）
  webSearchEnabled?: boolean | null
  // 对话模式（V39）：CHAT=自由对话 / CLARIFY=方案澄清；null=老数据按 CLARIFY 语义
  mode?: 'CHAT' | 'CLARIFY' | null
  createTime: string
  updateTime: string
}

export interface RequirementMessage {
  id: LongId
  conversationId: LongId
  role: 'user' | 'assistant'
  content: string
  seq: number
  // V33 结构化附加数据（JSON 文本）：assistant=结构化问题，user=选择快照；null=纯文本
  payload?: string | null
  createTime: string
}
