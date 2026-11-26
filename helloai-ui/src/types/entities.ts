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
  // TD-1: 交付物原型（拆解维度）——决定「按什么维度拆」；与 GRANULARITY（拆多细）正交。
  // 默认 BUILD；未走澄清链路或判不出时回落 BUILD，人工可改。
  taskArchetype?: TaskArchetype
  // V148 派生血缘（ADR-008）：本任务是某个子任务二次拆分出来的 ⇒ 指向来源子任务
  parentSubTaskId?: LongId | null
  // V148 派生血缘：草案期派生的父任务 id（父任务物化后清空，转到 parentSubTaskId）
  parentTaskId?: LongId | null
  parentDraftCode?: string | null
  // V148 根链 id（根任务 = 自身 id）与派生层级（根 0，全局 ≤2）
  rootTaskId?: LongId | null
  depth?: number | null
  // V148 派生原因：PLAN（草案期派生）/ RESTRUCTURE（执行期重构派生）
  derivationReason?: string | null
}

// TD-1 交付物原型六类（对齐后端 TaskArchetype）
export type TaskArchetype = 'BUILD' | 'DOCUMENT' | 'RESEARCH' | 'DATA' | 'OPERATE' | 'DECIDE'

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
  // V41/V12.2: 报告生成状态 NONE/GENERATING/REVIEWING/DONE/FAILED
  status?: FinalReportStatus
  // §12.1 上一版槽：true=存在可恢复的上一版，驱动「恢复上一版」按钮显隐
  hasPrev?: boolean
  // §12.1 上一版报告生成时间；无上一版为 null
  prevGeneratedAt?: string | null
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
  // TD-2 验收判据：谁来判、怎么判（默认 AUTOMATED）。落库前按此分流校验 acceptance 写法。
  acceptanceKind?: AcceptanceKind | null
  priority?: string | null
  // G-010 能力感知：子任务级技能标签（拆解侧指派/草案编辑修订）与执行约束
  requiredSkills?: string[] | null
  constraints?: string | null
  // G-011 不确定性申报：拆解侧识别出的假设与待确认项（草案编辑/任务详情展示）
  uncertainties?: Uncertainty[] | null
  // V27 依赖编排：前置子任务 id 列表（全部 DONE 才分发），旧数据为空数组
  dependsOn?: LongId[]
  /**
   * 执行上下文（JSONB）。
   *
   * <p><b>MA-1 定性：内部状态，客户端不得依赖</b>。客户端读取执行结果请用
   * {@code getExecutionResultById/{id}}（执行结果结构化契约端点）；本字段仍整包下发，
   * 属软约束（无机制强制不读）。待迁读点：{@code derivedTaskId}（派生血缘下钻，读已迁至
   * {@code SubTaskDetail} 的 executionResult 前先经时间线兜底）/ {@code noCandidate} /
   * {@code manualIntervention}（人工介入面板），移动端开工前必须完成迁移。</p>
   */
  context: Record<string, any> | null
  scoreFactors: Record<string, any> | null
  compositeScore: number | null
  scoreGrade: string | null
  deadline: string | null
  reworkCount: number
  timeoutCount: number
  /**
   * 本批拆解内的草案序号（1-based；仅拆解路径非空，展开/重生成/人工创建为 null）。
   *
   * 与 `Uncertainty.blockedBy` 是**同一套数字**：草案列表按依赖拓扑排序，行号不等于草案序号，
   * 没有它就「卡住 #2」找不到对应行。注意它与幂等键 `decomposeAttemptId` 无关，后者不进视图契约。
   */
  draftSeq?: number | null
  /**
   * 草案位置编号（`TD-16d-4` d-3b-3）：根层 `yyyyMMdd+4位`、子层 `父code-3位`。
   *
   * 这是**位置身份**，`id` 只是行身份——展开会删父行重建，同一个位置可能换了一行，
   * 但 code 不变。草案写路径（订 / 展开 / 重生成）以它为定位口径，
   * 行 key 也用它，避免展开后整表按 id 重挂导致展开态/编辑态错位。
   *
   * 存量草案（`V150` 之前拆解、从未双写暂存区）为 null，此时前端回落 `id` 与旧端点，
   * 待 `V153` 存量迁入后该回落分支可删。
   */
  draftCode?: string | null
  /** 暂存区草案的依赖 code 数组（AD-5a-3 读侧新契约；旧子任务行无此字段） */
  dependsOnCodes?: string[] | null
  createTime: string
  updateTime: string
}

/**
 * 草案暂存区视图（`AD-5a-3`，后端 `PlanDraftView`）。
 *
 * <p>草案 id 与执行任务 id 是两套空间；这里只描述草案。`code` 是稳定位置身份，
 * 写路径（编辑/重生成/派生）优先用它。</p>
 */
export interface PlanDraft {
  id: LongId
  taskId: LongId
  code: string
  round?: number | null
  draftSeq?: number | null
  title: string
  content?: string | null
  deliverable?: string | null
  acceptance?: string | null
  acceptanceKind?: AcceptanceKind | null
  priority?: string | null
  contract?: number | null
  requiredSkills?: string[] | null
  constraints?: string | null
  uncertainties?: Uncertainty[] | null
  dependsOnCodes?: string[] | null
  context?: Record<string, any> | null
  updateTime?: string | null
}

// G-011 / TD-7 不确定性申报条目（对齐后端 Uncertainty 实体）：
//   ASSUMPTION  已申报假设 —— 可自行验证、推翻即上报
//   UNCONFIRMED 待确认缺口 —— 不知去哪查/怎么算，须先验证再动手，无法验证则 BLOCKED 上报
//   TO_LOOK_UP  待检索事实 —— 知道去哪查（联网/附件/知识库），先检索再动手
//   TO_DERIVE   待推导事实 —— 知道怎么算，先推导出中间结论再动手
// 草案编辑侧不做 kind 强校验（授权输入），非法值由服务端降级 UNCONFIRMED
export interface Uncertainty {
  kind: UncertaintyKind | string
  note: string
  /** 缺口引用标签（TD-11，形如 U1）；存量行可能缺该键 */
  ref?: string | null
  /**
   * 该缺口**除本草案外**还卡住哪几条草案（TD-13 S2，本批 1-based 序号）。
   * 缺键 = 未申报影响面；空数组 = 明确申报「只卡自己」——两者展示措辞不同，不要合并。
   */
  blockedBy?: number[] | null
}

// G-011 / TD-7 不确定性类别（对齐后端 UncertaintyKind）
export type UncertaintyKind = 'ASSUMPTION' | 'UNCONFIRMED' | 'TO_LOOK_UP' | 'TO_DERIVE'

// TD-2 验收判据（对齐后端 AcceptanceKind）
export type AcceptanceKind = 'AUTOMATED' | 'STRUCTURAL' | 'REVIEWED' | 'ATTESTED'

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
  detail: Record<string, any> | null
  level: string
  source: string
  createTime: string
  /** 后端 2026-10-22 增强：活动流展示「哪个 Agent 干的 + 接入类型标签」 */
  agentName?: string
  agentAccessType?: string
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

/** 验收点覆盖声明（RV-3）：reviewer 逐条说明「这条验收标准靠什么证据算过」。 */
export interface ReviewCoverageItem {
  /** 被覆盖的验收标准原文子串 */
  criterionRef: string
  /** covered=已覆盖；failed=未通过；not_verifiable=无法核验 */
  status: string
  /** 证据方式：attachment=物化附件；sandbox=沙箱一手执行；其余为自述（不计入覆盖） */
  method: string
  /** 证据指向：附件名或沙箱台账条目 */
  evidenceRef: string
  /** 补充说明 */
  note: string
}

export interface ReviewRecord {
  id: LongId
  subTaskId: LongId
  reviewerAgent: LongId
  result: ReviewResult
  score: number
  issues: string | null
  comment: string | null
  /** 验收点覆盖清单；历史记录与模型未输出时为空 */
  coverage: ReviewCoverageItem[] | null
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

// G-006 Replay / Audit：agent_event 读侧投影（对齐后端 AgentEventItem，Long ID 序列化为 string）
export interface AgentEventItem {
  id: LongId
  eventId: string
  runId: string
  taskId: LongId | null
  subTaskId: LongId | null
  turn: number | null
  step: number | null
  eventType: string
  agentId: LongId | null
  payload: Record<string, any> | null
  createTime: string
}

// G-006 Audit 查询参数（GET /agent-events/pageAuditByTaskId/{taskId}）
export interface AgentEventAuditQuery {
  taskId: LongId
  eventType?: string
  /** 时间下界（含），格式 'yyyy-MM-dd HH:mm:ss'；与 auditTimeRange[0] 同口径 */
  timeStart?: string
  /** 时间上界（含），格式 'yyyy-MM-dd HH:mm:ss'；与 auditTimeRange[1] 同口径 */
  timeEnd?: string
  page: number
  pageSize?: number
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

// 重派结果（POST /sub-tasks/reassignById|redispatchInProgressById 响应体）：
// 被熔断 / 退避闸门拦截时 applied=false、任务状态不变，reason=backoff|circuit_open|terminal
export interface RedispatchResult {
  applied: boolean
  reason: string | null
  /** 退避窗口结束时刻（仅 reason=backoff 时非空） */
  nextAllowed: string | null
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
  /** 轮次快照（V157）：登记时点所属轮次，首轮 1；旧轮归档靠它区分，与 status 无关 */
  round?: number
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
  /**
   * 会话钉住的 LLM 模型（`provider:model`；V142）。
   *
   * <p>{@code plannerAgentId} 决定「谁扮演助理」，本字段只决定「用哪个模型答」。
   * null = 沿用 Planner Agent 绑定的模型（老数据口径）。</p>
   */
  modelType?: string | null
  finalTitle: string | null
  finalDescription: string | null
  roundCount: number
  // 会话级联网搜索开关（V34；null=默认开启 / true=开启 / false=关闭；
  // 首轮 LLM 调用前若为开启则服务端预检索行业资料注入 Prompt）
  webSearchEnabled?: boolean | null
  // 对话模式（V39）：CHAT=自由对话 / CLARIFY=方案澄清；null=老数据按 CLARIFY 语义
  mode?: 'CHAT' | 'CLARIFY' | null
  // 会话类型（V135）：REQUIREMENT=需求澄清 / ASSISTANT=助理对话；null=老数据按 REQUIREMENT
  kind?: 'REQUIREMENT' | 'ASSISTANT' | null
  // 建任务血缘（PA-11，V135）：来源助理会话 / 来源消息（软引用）
  parentConversationId?: LongId | null
  forkedFromMessageId?: LongId | null
  // 执行期重构派生的来源子任务（ADR-008 §19.6，V148，软引用）：非空表示本会话是
  // 「把某个 RESTRUCTURING 子任务拆成独立任务」而开的。与 parentConversationId 一样
  // 属于「种子会话」标记——两者都只有一条 user 种子消息、LLM 从未跑到过。
  sourceSubTaskId?: LongId | null
  // 草案期重构派生的来源草案编号（ADR-008 §5，V150）：与 taskId 组成父锚，表示本会话是
  // 「草案审阅页拆出去」开的。三个标记任一非空即种子会话。
  sourceDraftCode?: string | null
  // 派生会话产出的独立任务 Y（V150，软引用）：派生会话的 taskId 是父任务 X（ADR-008 §13.1
  // 「会话挂在父任务下」），产出任务在这列。读侧统一「derivedTaskId 优先，回退 taskId」——
  // 拿 taskId 当产出会让「查看任务」跳到父任务（因此在 X 上误点拒绝重拆）。
  derivedTaskId?: LongId | null
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

// --- Team 组合（N-002，C2） ---
export interface Team {
  id: LongId
  name: string
  description: string | null
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string
  createTime: string
  updateTime: string
}

export interface TeamMember {
  id: LongId
  teamId: LongId
  agentId: LongId
  slotRole: AgentRole
  weight: number
}

// --- Browser 会话（N-003，C3 配套） ---
export interface BrowserSession {
  id: LongId
  agentId: LongId
  taskId: LongId | null
  status: 'BEGIN' | 'ACTIVE' | 'CLOSED' | 'FAILED' | string
  currentUrl: string | null
  lastScreenshotRef: string | null
  beginTime: string | null
  closeTime: string | null
  createTime: string
}
