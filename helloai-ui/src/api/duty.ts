import request from './request'
import { paths } from './paths'
import type { DutyLeaseResponse, DutyAgentLatestResponse, DutyOverviewResponse } from '@/types/duty'
import type { PageResult, LongId } from '@/types'

/**
 * AgentHub V1 P1 打卡上班报表前端 API（对齐后端 AgentDutyLeaseController）。
 *
 * <p>全部为只读接口：写入语义（checkIn/checkOut/续约/过期扫描）仍归属 MCP 工具、
 * AgentDutyLeaseService 与 DutyLeaseExpirationTask。</p>
 */
export const dutyApi = {
  /** 分页查询打卡记录，可按 agentId / status 过滤。 */
  list(params?: {
    agentId?: LongId | null
    status?: 'ACTIVE' | 'CLOSED' | 'EXPIRED' | null
    page?: number
    size?: number
  }) {
    return request.get<any, PageResult<DutyLeaseResponse>>(paths.admin.dutyLeases, { params })
  },

  /** Agent 维度分页：每个 Agent 只返回最新一条打卡记录 + 记录总数。 */
  listByAgent(params?: { page?: number; size?: number }) {
    return request.get<any, PageResult<DutyAgentLatestResponse>>(paths.admin.dutyLeasesByAgent, { params })
  },

  /** 今日打卡概览（Dashboard 顶部卡片数据源，Agent 维度去重）。 */
  overview() {
    return request.get<any, DutyOverviewResponse>(paths.admin.dutyLeasesOverview)
  }
}