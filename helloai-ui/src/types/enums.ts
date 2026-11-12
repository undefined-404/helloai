// ============================================================
// HelloAI 枚举类型（与后端 com.helloai 枚举值对齐）
// ============================================================

// --- 任务 / 子任务状态 ---
export type SubTaskStatus = 'PENDING' | 'ASSIGNED' | 'IN_PROGRESS' | 'PAUSED'
  | 'REVIEW' | 'DONE' | 'REWORK' | 'BLOCKED' | 'CANCELLED' | 'DEAD_LETTER'
  // ADR-008 派生：工作整体移交独立派生 Task，完成/失败后由完成态传播推进
  | 'AWAITING_DERIVED' | 'RESTRUCTURING'

// 任务主状态（2026-11-08 起 PLANNING 与 AWAITING_PLAN_CONFIRM 分开）：
// PLANNING = 机器正在拆解（可按租约超时回收）；AWAITING_PLAN_CONFIRM = 草案已产出等用户处理（不回收）
export type TaskStatus = 'PENDING' | 'PLANNING' | 'AWAITING_PLAN_CONFIRM'
  | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'

// V41/V12.2: 任务最终整合报告生成状态（与 TaskStatus 解耦，报告生成是增值物；
// REVIEWING = 已生成待自动审查收敛，审查链各出口收敛 DONE）
export type FinalReportStatus = 'NONE' | 'GENERATING' | 'REVIEWING' | 'DONE' | 'FAILED'

// --- Agent 相关 ---
export type AgentRole = 'PLANNER' | 'EXECUTOR' | 'REVIEWER'

export type AgentStatus = 'ACTIVE' | 'DISABLED'

// --- 审查 / 附件 ---
// INCONCLUSIVE = 判定不可采信（如驳回却给满分）：不计入统计分母、不触发返工、停 REVIEW 等人工裁决
export type ReviewResult = 'APPROVED' | 'REJECTED' | 'INCONCLUSIVE'

export type AttachmentStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED'

// --- 对话澄清 ---
export type RequirementConversationStatus = 'ACTIVE' | 'FINALIZED' | 'ABANDONED'

// --- TaskAgentPolicy 嵌套枚举 ---
// N11 回退策略：AUTO（默认）/ RESTRICTED / NONE
export type TaskFallbackPolicy = 'AUTO' | 'RESTRICTED' | 'NONE'
// 任务难度：LOW / MEDIUM（默认）/ HIGH（HIGH 视为禁止 N11 自动回退）
export type TaskDifficulty = 'LOW' | 'MEDIUM' | 'HIGH'
