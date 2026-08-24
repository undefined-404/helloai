import request from './request'
import { paths } from './paths'
import type { AgentQualityRank, QualityDashboardResponse, QualityOverview } from '@/types/quality'

/**
 * Phase 5 质量度量看板 API（对齐后端 AdminQualityController）。
 *
 * <p>全部端点受 sys_config 键 admin.quality.enabled 门控（§6.151 起默认开放，
 * 仅显式 false 关闭）；关闭时后端返回业务码 403，前端由 request 拦截器统一
 * 提示但不登出（403 不再触发 logout）。</p>
 */
export const qualityApi = {
  /** 全局质量概览（画像表存量聚合）。 */
  overview() {
    return request.get<any, QualityOverview>(paths.admin.qualityOverview)
  },

  /** Agent 质量排行（一次通过率降序；limit 缺省返回全部）。 */
  agentRankings(limit?: number) {
    return request.get<any, AgentQualityRank[]>(paths.admin.qualityAgents, { params: { limit } })
  },

  /** 质量看板全量数据（趋势/驳回原因/返工轮次/放水率 + 概览；days 缺省按 30 兜底）。 */
  dashboard(days?: number) {
    return request.get<any, QualityDashboardResponse>(paths.admin.qualityDashboard, { params: { days } })
  }
}
