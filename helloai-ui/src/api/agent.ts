import request from './request'
import type { Agent, AgentListItem, AgentDetail, AgentOnboardingResponse, AgentRelatedCounts, AgentDeleteResult, ScoreLogItem, ActivityLogItem, PageResult } from '@/types'

export const agentApi = {
  // ── 现有 ──
  list(params?: { role?: string; status?: string }) {
    return request.get<any, Agent[]>('/agents', { params })
  },
  getById(id: string) {
    return request.get<any, Agent>(`/agents/${id}`)
  },
  register(data: { name: string; role: string; description?: string; specializationSlug?: string }) {
    return request.post('/agents/register', data)
  },

  // ── 管理端分页列表（含 enrichment）──
  adminList(params?: {
    page?: number
    pageSize?: number
    role?: string
    status?: string
    keyword?: string
    sortBy?: string
    sortOrder?: string
  }) {
    return request.get<any, PageResult<AgentListItem>>('/admin/agents', { params })
  },

  // ── 管理端详情 ──
  adminDetail(id: string) {
    return request.get<any, AgentDetail>(`/admin/agents/${id}`)
  },

  // ── 更新 Agent 信息 ──
  updateProfile(id: string, data: { name?: string; modelType?: string; specializationSlug?: string; remark?: string }) {
    return request.put<any, void>(`/admin/agents/${id}`, data)
  },

  // ── 切换状态 ──
  updateStatus(id: string, status: 'ACTIVE' | 'DISABLED') {
    return request.put<any, void>(`/admin/agents/status/${id}`, { status })
  },

  // ── 重置 Key ──
  resetKey(id: string) {
    return request.post<any, { apiKey: string; message: string }>(`/admin/agents/reset-key/${id}`)
  },

  // ── 关联数据统计 ──
  relatedCounts(id: string) {
    return request.get<any, AgentRelatedCounts>(`/admin/agents/${id}/related-counts`)
  },

  // ── 级联删除 ──
  deleteAgent(id: string, confirmName: string) {
    return request.delete<any, AgentDeleteResult>(`/admin/agents/${id}`, { data: { confirmName } })
  },

  // ── 积分明细 ──
  scoreLogs(id: string, params?: { page?: number; pageSize?: number }) {
    return request.get<any, PageResult<ScoreLogItem>>(`/admin/agents/${id}/score-logs`, { params })
  },

  // ── 活动日志 ──
  activityLogs(id: string, params?: { page?: number; pageSize?: number; action?: string }) {
    return request.get<any, PageResult<ActivityLogItem>>(`/admin/agents/${id}/activity-logs`, { params })
  },

  // ── 接入内容生成 ──
  getOnboardingContent(id: string) {
    return request.get<any, AgentOnboardingResponse>(`/admin/agents/${id}/onboarding-content`)
  },
}
