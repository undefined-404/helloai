// ============================================================
// HelloAI AgentHub V1 P1 值班报表前端类型
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
  /** 值班会话标识。 */
  sessionId: string
  /** 工作模式。 */
  workMode: string
  /** 最大并发子任务数。 */
  maxConcurrent: number | null
  /** 租约状态。 */
  status: DutyLeaseStatus
  /** 值班开始时间。 */
  startedAt: string | null
  /** 最近一次续约时间。 */
  lastRenewedAt: string | null
  /** 租约过期时间。 */
  expiresAt: string | null
  /** 关闭原因（仅 CLOSED / EXPIRED 时有值）。 */
  closeReason: string | null
}

export interface DutyOverviewResponse {
  /** 当前值班中（ACTIVE）租约条数，等于在岗 Agent 数。 */
  activeCount: number
  /** 已签退（CLOSED）租约条数。 */
  closedCount: number
  /** 已过期（EXPIRED）租约条数。 */
  expiredCount: number
  /** 全部租约条数（历史累计，未删除）。 */
  totalCount: number
}

// --- 状态标签映射（看板 / 列表统一展示） ---
export const DUTY_LEASE_STATUS_MAP: Record<DutyLeaseStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  ACTIVE:  { label: '值班中', type: 'success' },
  CLOSED:  { label: '已签退', type: 'info' },
  EXPIRED: { label: '已过期', type: 'warning' }
}