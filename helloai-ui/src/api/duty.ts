import request from './request'
import type { DutyLeaseResponse, DutyOverviewResponse, PageResult } from '@/types/duty'

/**
 * AgentHub V1 P1 值班报表前端 API（对齐后端 AgentDutyLeaseController）。
 *
 * <p>全部为只读接口：写入语义（checkIn/checkOut/续约/过期扫描）仍归属 MCP 工具、
 * AgentDutyLeaseService 与 DutyLeaseExpirationTask。</p>
 */
export const dutyApi = {
  /** 分页查询值班租约，可按 agentId / status 过滤。 */
  list(params?: {
    agentId?: number | null
    status?: 'ACTIVE' | 'CLOSED' | 'EXPIRED' | null
    page?: number
    size?: number
  }) {
    return request.get<any, PageResult<DutyLeaseResponse>>('/admin/duty-leases', { params })
  },

  /** 值班租约状态概览（Dashboard 顶部卡片数据源）。 */
  overview() {
    return request.get<any, DutyOverviewResponse>('/admin/duty-leases/overview')
  }
}