// ============================================================
// HelloAI AgentHub V1 P1 打卡上班报表前端类型
// ------------------------------------------------------------
// 对齐后端:
//   - com.helloai.api.dto.duty.DutyLeaseResponse
//   - com.helloai.api.dto.duty.DutyOverviewResponse
//   - com.helloai.common.constant.AgentDutyLeaseStatus
//   - com.helloai.api.dto.PageResult
// ============================================================

import type { LongId } from './index'

// --- 枚举 ---
export type DutyLeaseStatus = 'ACTIVE' | 'CLOSED' | 'EXPIRED'

// --- 实体 ---
export interface DutyLeaseResponse {
  /** 租约主键。 */
  id: LongId
  /** 关联的 Agent ID。 */
  agentId: LongId
  /** Agent 名称（关联 agent 表冗余，Agent 已删除时为 null）。 */
  agentName: string | null
  /** 打卡会话标识。 */
  sessionId: string
  /** 工作模式。 */
  workMode: string
  /** 最大并发子任务数。 */
  maxConcurrent: number | null
  /** 租约状态。 */
  status: DutyLeaseStatus
  /** 上班开始时间。 */
  startedAt: string | null
  /** 最近一次续约时间。 */
  lastRenewedAt: string | null
  /** 租约过期时间。 */
  expiresAt: string | null
  /** 关闭原因（仅 CLOSED / EXPIRED 时有值）。 */
  closeReason: string | null
}

// Agent 维度打卡列表项：每个 Agent 一行（最新打卡记录 + 记录总数）
export interface DutyAgentLatestResponse extends DutyLeaseResponse {
  /** 该 Agent 的租约总条数。 */
  leaseCount: number
}

// 今日打卡概览：按 Agent 维度去重，每个 Agent 只按其最新租约状态计一次
export interface DutyOverviewResponse {
  /** 今日在线（最新租约 ACTIVE）的 Agent 数。 */
  activeCount: number
  /** 今日已下班（最新租约 CLOSED）的 Agent 数。 */
  closedCount: number
  /** 今日超时（最新租约 EXPIRED）的 Agent 数。 */
  expiredCount: number
}

// --- 状态标签映射（看板 / 列表统一展示） ---
export const DUTY_LEASE_STATUS_MAP: Record<DutyLeaseStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  ACTIVE:  { label: '在线', type: 'success' },
  CLOSED:  { label: '下班', type: 'info' },
  EXPIRED: { label: '超时', type: 'warning' }
}