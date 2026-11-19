// ============================================================
// HelloAI 请求 DTO — 前端 → 后端的请求体定义
// ============================================================

import type { LongId } from './common'
import type { SubTaskStatus, ReviewResult } from './enums'

export interface ChangeStatusRequest {
  subTaskId: LongId
  newStatus: SubTaskStatus
  agentId: LongId | null
}

// M4.5 派发控制台新增类型
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

// Planner Chat 输入优化（PromptEnhancer）
export interface PromptEnhanceRequest {
  /** 用户当前输入（待优化的原始内容） */
  prompt: string
}

export interface PromptEnhanceResult {
  /** 原文（服务端 trim 后原样返回） */
  originalPrompt: string
  /** 优化后的结构化表达 */
  optimizedPrompt: string
}

// ============================================================
// MA-1 执行结果结构化契约（getExecutionResultById 只读端点）
// ============================================================

/** 执行产物分片（对应后端 core `ExecutionPart`）。type 非空；text/data 依 type 互斥；file 分片当前为占位。 */
export interface ExecutionResultPart {
  /** text / data / file（第一批只收 text、data；file 为附件卡占位） */
  type: string
  /** text 分片正文 */
  text?: string
  /** data 分片的 JSON 文本（前端按 JSON 尝试解析，失败降级纯文本） */
  data?: string
  /** 可选 MIME 标注（file 分片预留） */
  mimeType?: string
  /** 可选文件名标注（file 分片预留） */
  fileName?: string
}

/** 执行结果读模型：format 二选一（markdown | parts）；sections 恒 null 为演进位，本期不实现。 */
export interface SubTaskExecutionResult {
  subTaskId: LongId
  /** format 可选：老后端/老数据缺失时前端回落 legacy 结构化文本路径 */
  format?: 'markdown' | 'parts'
  /** format=markdown 时有效：已剥离尾部 EXECUTION_RECORD 的 output 正文 */
  markdown?: string | null
  /** format=parts 时有效：原序分片列表 */
  parts?: ExecutionResultPart[] | null
  /** 演进位，恒 null，本期不实现 */
  sections?: unknown[] | null
  /** 恒 false（本期无截断语义） */
  truncated?: boolean
}
