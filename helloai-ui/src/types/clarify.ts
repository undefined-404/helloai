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
}
