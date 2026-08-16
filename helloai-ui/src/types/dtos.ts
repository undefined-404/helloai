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
