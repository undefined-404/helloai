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
