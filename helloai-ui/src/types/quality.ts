// Phase 5 质量度量看板类型定义（对齐后端 review/agent 域统计 DTO）。

/** 全局质量概览（GET /admin/quality/overview）。 */
export interface QualityOverview {
  totalReviewed: number
  totalApproved: number
  firstPassRate: number
  avgReworkRounds: number
  activeExecutors: number
}

/** Agent 质量排行点（GET /admin/quality/agents?limit=）。 */
export interface AgentQualityRank {
  agentId: string
  agentName: string
  reviewedCount: number
  firstPassRate: number
  qualityScore: number | null
}

/** 质量趋势点（按天分组，period = YYYY-MM-DD）。 */
export interface QualityTrendPoint {
  period: string
  reviewedCount: number
  approvedCount: number
  avgScore: number
}

/** 驳回原因分布点（[defect] 标签计数）。 */
export interface DefectDistribution {
  defectTag: string
  count: number
}

/** 返工轮次分布点（round 分组计数）。 */
export interface ReworkRoundPoint {
  round: number
  subTaskCount: number
}

/** Reviewer 放水率点（审查者维度通过率）。 */
export interface ReviewerLeniency {
  reviewerAgentId: string
  reviewerName: string
  reviewedCount: number
  approveRate: number
  avgScore: number
}

/** 质量看板聚合响应（GET /admin/quality/dashboard?days=）。 */
export interface QualityDashboardResponse {
  overview: QualityOverview
  trends: QualityTrendPoint[]
  defectDistributions: DefectDistribution[]
  reworkRounds: ReworkRoundPoint[]
  reviewers: ReviewerLeniency[]
}
