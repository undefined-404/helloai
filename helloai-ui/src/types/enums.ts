// ============================================================
// HelloAI 枚举类型（与后端 com.helloai 枚举值对齐）
// ============================================================

// --- 任务 / 子任务状态 ---
export type SubTaskStatus = 'PENDING' | 'ASSIGNED' | 'IN_PROGRESS' | 'PAUSED'
  | 'REVIEW' | 'DONE' | 'REWORK' | 'BLOCKED' | 'CANCELLED' | 'DEAD_LETTER'
  // V26 Planner 拆解草案态：确认后转 PENDING，拒绝后转 CANCELLED
  | 'PENDING_PLAN_REVIEW'

// PLANNING: V26 拆解草案已生成待审阅（confirm→IN_PROGRESS / reject→回 PENDING）
export type TaskStatus = 'PENDING' | 'PLANNING' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'

// V41: 任务最终整合报告生成状态（与 TaskStatus 解耦，报告生成是增值物）
export type FinalReportStatus = 'NONE' | 'GENERATING' | 'DONE' | 'FAILED'

// --- Agent 相关 ---
export type AgentRole = 'PLANNER' | 'EXECUTOR' | 'REVIEWER'

export type AgentStatus = 'ACTIVE' | 'DISABLED'

// --- 审查 / 附件 ---
export type ReviewResult = 'APPROVED' | 'REJECTED'

export type AttachmentStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED'

// --- 对话澄清 ---
export type RequirementConversationStatus = 'ACTIVE' | 'FINALIZED' | 'ABANDONED'

// --- TaskAgentPolicy 嵌套枚举 ---
// N11 回退策略：AUTO（默认）/ RESTRICTED / NONE
export type TaskFallbackPolicy = 'AUTO' | 'RESTRICTED' | 'NONE'
// 任务难度：LOW / MEDIUM（默认）/ HIGH（HIGH 视为禁止 N11 自动回退）
export type TaskDifficulty = 'LOW' | 'MEDIUM' | 'HIGH'
