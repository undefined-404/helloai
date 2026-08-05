// ============================================================
// HelloAI 类型定义 — 对齐后端 com.helloai 枚举和实体
// ============================================================

// --- 分页 ---
export interface PageResult<T> {
  list: T[]
  total: number
  pages: number
  current: number
}

// --- 统一返回 ---
export interface R<T> {
  code: number
  msg: string
  data: T
  traceId: string | null
}

// --- 枚举 ---
export type SubTaskStatus = 'PENDING' | 'ASSIGNED' | 'IN_PROGRESS' | 'PAUSED'
  | 'REVIEW' | 'DONE' | 'REWORK' | 'BLOCKED' | 'CANCELLED' | 'DEAD_LETTER'
  // V26 Planner 拆解草案态：确认后转 PENDING，拒绝后转 CANCELLED
  | 'PENDING_PLAN_REVIEW'

export type AgentRole = 'PLANNER' | 'EXECUTOR' | 'REVIEWER'

export type AgentStatus = 'ACTIVE' | 'DISABLED'

export type ReviewResult = 'APPROVED' | 'REJECTED'

// PLANNING: V26 拆解草案已生成待审阅（confirm→IN_PROGRESS / reject→回 PENDING）
export type TaskStatus = 'PENDING' | 'PLANNING' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'

// V41: 任务最终整合报告生成状态（与 TaskStatus 解耦，报告生成是增值物）
export type FinalReportStatus = 'NONE' | 'GENERATING' | 'DONE' | 'FAILED'

export type AttachmentStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED'

// --- 实体 ---
// v1.1 修复: Long ID 后端已序列化为 string, 前端类型用 string | number 兼容
// 任何对外使用的 Long 主键 / 外键字段（如 id、taskId、subTaskId、agentId、userId 等）均为 string
export type LongId = string | number
export type IntCount = number  // 普通计数字段仍按 number

export interface Task {
  id: LongId
  title: string
  description: string
  status: TaskStatus
  createTime: string
  updateTime: string
  // V41: 报告生成状态（生成中时列表状态列显示"报告生成中"，报告按钮禁用）
  finalReportStatus?: FinalReportStatus
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

export interface Agent {
  id: string
  name: string
  role: AgentRole
  // M4.5: 接入类型，用于前端过滤可派发 Agent（CLI_CLIENT / API_KEY_LLM / WEB_BROWSER）
  accessType?: 'CLI_CLIENT' | 'API_KEY_LLM' | 'WEB_BROWSER' | string
  modelType: string | null
  modelConfig: Record<string, any> | null
  specializationSlug: string | null
  status: AgentStatus
  score: number
  remark: string | null
  createTime: string
}

export interface AgentListItem {
  id: string
  name: string
  role: AgentRole
  // 接入类型：内部 LLM Agent（API_KEY_LLM）不展示接入内容入口
  accessType?: 'CLI_CLIENT' | 'API_KEY_LLM' | 'WEB_BROWSER' | string
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
  specializationSlug: string | null
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

// --- 角色颜色映射 ---
export const ROLE_COLOR_MAP: Record<AgentRole, { bar: string; bg: string; text: string; border: string; tagType: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PLANNER:  { bar: '#7C3AED', bg: '#F5F3FF', text: '#6D28D9', border: '#EDE9FE', tagType: '' },
  EXECUTOR: { bar: '#3B82F6', bg: '#EFF6FF', text: '#2563EB', border: '#DBEAFE', tagType: 'primary' },
  REVIEWER: { bar: '#F59E0B', bg: '#FFFBEB', text: '#D97706', border: '#FEF3C7', tagType: 'warning' },
}

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

// --- 请求 DTO ---
export interface ChangeStatusRequest {
  subTaskId: LongId
  newStatus: SubTaskStatus
  agentId: LongId | null
}

// --- M4.5 派发控制台新增类型 ---
export interface CreateSubTaskPayload {
  taskId: LongId
  moduleId?: LongId
  title: string
  description?: string
  deliverable?: string
  acceptance?: string
  priority?: string
  assignedAgent?: LongId
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

export interface ModuleItem {
  id: LongId
  taskId: LongId
  name: string
  sortOrder?: number
}

export interface CreateReviewRequest {
  subTaskId: LongId
  result: ReviewResult
  score: number
  issues: string
  comment: string
  reworkAgentId: LongId | null
}

export interface AdjustScoreRequest {
  agentId: LongId
  scoreDelta: number
  reason: string
  subTaskId: LongId | null
}

// --- 状态标签映射 ---
export const SUB_TASK_STATUS_MAP: Record<SubTaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PENDING:     { label: '待分配',   type: 'info' },
  ASSIGNED:    { label: '已分配',   type: '' },
  IN_PROGRESS: { label: '执行中',   type: 'primary' },
  PAUSED:      { label: '已暂停',   type: 'warning' },
  REVIEW:      { label: '审查中',   type: 'warning' },
  DONE:        { label: '已完成',   type: 'success' },
  REWORK:      { label: '返工',     type: 'danger' },
  BLOCKED:     { label: '阻塞',     type: 'danger' },
  CANCELLED:   { label: '已取消',   type: 'info' },
  DEAD_LETTER: { label: '死信待人工', type: 'danger' },
  PENDING_PLAN_REVIEW: { label: '草案待审', type: 'warning' }
}

export const TASK_STATUS_MAP: Record<TaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PENDING:     { label: '待规划',   type: 'info' },
  PLANNING:    { label: '拆解中',   type: 'warning' },
  IN_PROGRESS: { label: '进行中',   type: 'primary' },
  DONE:        { label: '已完成',   type: 'success' },
  CANCELLED:   { label: '已取消',   type: 'info' }
}

export const SCORE_GRADE_MAP: Record<string, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  S: { label: 'S 卓越', type: 'success' },
  A: { label: 'A 优秀', type: 'success' },
  B: { label: 'B 良好', type: 'warning' },
  C: { label: 'C 不足', type: 'danger' },
  D: { label: 'D 差',   type: 'danger' }
}

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
}

// --- v1.1 新增类型 ---

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

// V29 对话式需求澄清
export type RequirementConversationStatus = 'ACTIVE' | 'FINALIZED' | 'ABANDONED'

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

// V33 结构化选项式需求澄清
export interface ClarifyOption {
  label: string
  value: string
  // 权重预留字段（当前无业务消费）
  weight?: number | null
  recommended?: boolean | null
}

export interface ClarifyQuestion {
  id: string
  text: string
  multiple?: boolean | null
  allowCustom?: boolean | null
  customPlaceholder?: string | null
  options: ClarifyOption[]
}

/** assistant 消息 payload：{mode, progress, questions} */
export interface ClarifyAssistantPayload {
  mode: 'structured' | 'freeform'
  // LLM 对澄清程度的 0~100 自评（仅展示）
  progress?: number | null
  questions?: ClarifyQuestion[]
}

/** 用户选项回答快照（user 消息 payload.selections 元素） */
export interface ClarifySelection {
  questionId: string
  questionText: string
  values: string[]
  labels: string[]
  custom?: boolean
  customText?: string | null
}

/** 会话 + 全部消息（create / send / detail 统一返回） */
export interface ClarifyConversationDetail {
  conversation: RequirementConversation
  messages: RequirementMessage[]
  // 会话关联任务是否仍存在（仅 detail 返回）；FINALIZED 且为 false 时可重新生成
  taskExists?: boolean
}

/** Planner 下拉选项（selectable=false 时 disabledReason 说明置灰原因） */
export interface PlannerOption {
  id: LongId
  name: string
  role: string
  accessType: string
  modelType: string | null
  onDuty: boolean
  selectable: boolean
  disabledReason: string | null
}

export const PROMPT_CATEGORY_MAP: Record<string, string> = {
  ROLE_TEMPLATE: '角色模板',
  AGENT_SPECIALIZATION: 'Agent 专业化',
  SKILL: '技能文档'
}

export const INBOX_EVENT_TYPE_MAP: Record<string, string> = {
  'sub_task.assigned': '新任务',
  'sub_task.submitted': '待审查',
  'sub_task.rejected': '需返工',
  'sub_task.blocked': '任务阻塞',
  'sub_task.paused': '已暂停',
  'sub_task.resumed': '已恢复',
  'sub_task.cancelled': '已取消',
  'task.completed': '任务完成'
}
