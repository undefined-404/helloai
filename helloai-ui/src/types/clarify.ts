// ============================================================
// HelloAI 需求澄清类型 — 结构化选项 / Planner 选择
// ============================================================

import type { LongId } from './common'

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

/**
 * 对话内技能产物引用（assistant payload.artifact，2026-10-27）。
 *
 * 选技能（如 archify）后由平台在对话轮内直接跑出文件并登记为会话附件，
 * 前端据此渲染「产物卡片」——`attachmentId` 同时可用于内联预览与下载。
 */
export interface ClarifyArtifactRef {
  /** 会话附件 ID（chat_attachment.id），用于 previewById / downloadById */
  attachmentId: string
  fileName: string
  fileSize: number
  /** SHA-256（与技能 CLI 回执对账用；缺省为空串） */
  sha256?: string
  /** 图表类型（lifecycle / workflow / …；其它技能可能为空） */
  chartType?: string
  /** 实际产出用的质量档：showcase（严格门通过）/ standard（降档） */
  qualityProfile?: string
  /** 是否降档产出（严格布局门未通过，退回标准档才出的图） */
  degraded?: boolean
  /** 降档原因（如 composition=fail, errors=2） */
  degradeReason?: string
}

/** assistant 消息 payload：{mode, progress, questions, webSearch, process, artifact} */
export interface ClarifyAssistantPayload {
  // mode 缺失 = 普通文本回复（技能产物消息、纯答疑都不带 mode）
  mode?: 'structured' | 'freeform'
  // LLM 对澄清程度的 0~100 自评（仅展示）
  progress?: number | null
  questions?: ClarifyQuestion[]
  // V41 联网搜索查验轨迹（仅本轮实际发起过搜索时存在）
  webSearch?: WebSearchTrace
  // 2026-10-18 过程数据：思考全文 + 平台动作步骤（done 后/刷新后仍可回看）
  process?: RoundProcessTrace
  // 2026-10-27 技能产物（选「产出型技能」时由后端直接执行技能生成）
  artifact?: ClarifyArtifactRef
  /** payload 类型标记（当前仅 'artifact'） */
  type?: string
}

/** 一轮对话的过程数据（assistant payload.process，对齐 DeepSeek「已深度思考」/ ChatGPT「Thinking」） */
export interface RoundProcessTrace {
  /** 模型思考过程全文（无推理模型时缺省） */
  thinking?: string | null
  /** 平台动作步骤（「正在联网检索：xxx」「已检索 N 条 · 21.5s」），按发生顺序 */
  steps?: string[] | null
}

/** V41 联网搜索单条来源（webSearch payload 的 results 元素） */
export interface WebSearchSource {
  title: string
  url?: string | null
  snippet?: string | null
  siteName?: string | null
}

/** V41 联网搜索查验轨迹（assistant 消息 payload.webSearch，对齐 DeepSeek/Kimi 折叠查验条） */
export interface WebSearchTrace {
  provider?: string | null
  query?: string | null
  // 本轮实际尝试过的搜索词（多候选词顺序降级时多条；旧消息无此键时回退 query）
  queries?: string[]
  costMs?: number
  total?: number
  // 搜索异常降级时为 true，reason 附异常摘要（主流程不阻断）
  failed?: boolean
  reason?: string | null
  results?: WebSearchSource[]
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
  conversation: import('./entities').RequirementConversation
  messages: import('./entities').RequirementMessage[]
  // 会话关联任务是否仍存在（仅 detail 返回）；FINALIZED 且为 false 时可重新生成
  taskExists?: boolean
  /**
   * SKX-13：技能执行的运行记录 id。非空 ⇒ 本轮是技能执行且**尚未出结果**，
   * 前端据此启动轮询（skillRunStatus / skillRunPhase 显示进度）。
   * 非技能轮为 undefined。
   */
  skillRunId?: LongId
  /** 技能执行状态：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED */
  skillRunStatus?: string
  /** 技能执行阶段：SPEC / RENDER / CHECK / EXPORT / DONE */
  skillRunPhase?: string
}

/**
 * SKX-13：技能执行状态（轮询端点返回）。
 *
 * <p>只回答「还在跑吗、跑到哪一步、失败原因是什么」——
 * 产物本身走会话详情里那条 assistant 消息，不在这里回。</p>
 */
export interface SkillRunStatus {
  id: LongId
  conversationId: LongId
  skillName: string
  status: string
  phase?: string
  phaseDetail?: string
  errorMessage?: string
  attachmentId?: LongId
  /** 是否已终态（前端据此停止轮询） */
  terminal: boolean
  startedAt?: string
  finishedAt?: string
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

/**
 * 工作台 Composer「选模型」候选（工作台选的是**无角色**的纯 LLM 模型，2026-11-05）。
 *
 * <p>与 {@link PlannerOption} 是两套候选集，不能互相替代：这里来自 LLM 模型注册表
 * （{@code llm_provider_model}），Planner 下拉来自 Agent 名册（带角色）。</p>
 *
 * <p><b>只含当前可用的模型</b>：无 API Key / 供应商已下架 / 模型已停用的由服务端在
 * 候选集里滤掉，不下发。前端因此不需要「置灰 + 原因」分支——列表底部的维护指引统一
 * 回答「为什么少了某个模型」。</p>
 */
export interface LlmModelOption {
  providerCode: string
  providerName: string
  modelName: string
  /** 传回服务端的模型标识：`provider:model`（与服务端 AgentModelPin 同口径） */
  modelType: string
  /** 是否为该供应商的默认模型 */
  defaultModel: boolean
}

/** PA-3 助理身份（读取面；vo 由 AssistantProfileService.getActiveByUser 按优先级兜底给出） */
export interface AssistantProfile {
  name: string
  identity: string
  model: string
  skillIds: LongId[]
  greeting: string
}

/** PA-4 工作台「最近任务」条目 */
export interface WorkbenchRecentTask {
  id: LongId
  title: string
  status: string | null
  createTime: string
}

/** PA-4 工作台任务概况第一屏（执行中任务 + 子任务三态） */
export interface WorkbenchTaskOverview {
  /** 执行中任务数（task.status = IN_PROGRESS） */
  inProgressTasks: number
  /** 待分配子任务数 */
  pendingDispatch: number
  /** 待审查子任务数 */
  pendingReview: number
  /** 待人工子任务数（阻塞 + 人工死信） */
  pendingManual: number
}

/** PA-4 工作台活跃 Agent 条目：Agent 名册 + 该 Agent 名下工作量的实时状态 */
export interface WorkbenchActiveAgent {
  agentId: LongId
  name: string
  /** true = 内部 LLM；false = 外部 AI Agent */
  internal: boolean
  /** Agent 角色：EXECUTOR（执行）| REVIEWER（复核） */
  role: string
  /** 运行状态：RUNNING（运行中）| REVIEWING（审核中）| STANDBY（待命中） */
  status: string
  runningCount: number
}

/** PA-4 个人工作台聚合响应（任务概况 + 最近任务 + 最近产物） */
export interface Workbench {
  profile: AssistantProfile
  taskOverview: WorkbenchTaskOverview
  activeAgents: WorkbenchActiveAgent[]
  /**
   * 被隐藏的外部 Agent 条数（心跳离线 / 休眠，不进候选）。
   *
   * <p>由后端单独给数，**不能**用「名册总数 - 可见数」推导：名册里还混着内部 LLM，
   * 做减法会把常驻在线的内部 LLM 算成「被隐藏的外部 Agent」。</p>
   */
  hiddenOfflineAgents: number
  recentTasks: WorkbenchRecentTask[]
  artifacts: ArtifactItem[]
}

/** PA-5 我的产物聚合条目（两源归一；前端据此分栏 + 路由下载/预览端点） */
export interface ArtifactItem {
  /** conversation | execution */
  source: string
  id: LongId
  fileName: string
  fileType?: string | null
  mimeType?: string | null
  fileSize?: number | null
  /** 上下文标题（会话标题或任务标题） */
  contextTitle?: string | null
  /** 上下文 id（会话 id 或任务 id） */
  contextId?: LongId | null
  createdAt?: string | null
}

/** PA-12 上下文窗口占用（五项 token 拆分 + 比率） */
export interface ContextUsage {
  systemPromptTokens: number
  toolTokens: number
  skillTokens: number
  messageTokens: number
  otherTokens: number
  totalTokens: number
  contextSize: number
  ratio: number
  compactedUntilSeq: number
  summaryVersion: number
  lastCompactAt?: string | null
}

/** PA-12 压缩结果 */
export interface CompactResult {
  compacted: boolean
  evictedMessages: number
  compactedUntilSeq: number
  summaryVersion: number
  beforeRatio: number
  afterRatio: number
  reason: string
}

/**
 * 会话附件（V121 / 需求对话「添加文件」）。
 *
 * <p>与子任务附件（`Attachment`）是两套：这里挂会话、随对话生命周期回收。
 * `parseStatus` 是胶囊状态的核心：PENDING/PARSING 显示转圈，READY 显示字数，
 * FAILED 可点重试，SKIPPED 显式标「未识别」——**不能**把 SKIPPED 画成加载中，
 * 否则用户会一直等一个永远不会好的文件。</p>
 */
export interface ChatAttachmentItem {
  id: LongId
  conversationId: LongId | null
  fileName: string
  fileType: string
  mimeType: string
  fileSize: number | null
  /** PENDING | PARSING | READY | FAILED | SKIPPED */
  parseStatus: string | null
  /** 失败/跳过原因（SKIPPED 时用于展示「未识别：图片…」） */
  parseError: string | null
  extractedChars: number | null
  pageCount: number | null
  parser: string | null
  createTime?: string | null
  /**
   * 附件归属：`user` = 用户上传（uploader_user_id 非空）；`platform` = 平台产物
   * （saveArtifact 写入时 uploader_user_id 为 null，2026-10-27）。
   * 胶囊条只展示 user；平台产物进右侧「产物」侧栏。
   */
  source?: 'user' | 'platform' | string | null
}
