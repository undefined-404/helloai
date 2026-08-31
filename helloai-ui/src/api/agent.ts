import request from './request'
import { paths } from './paths'
import type { AxiosResponse } from 'axios'
import type { Agent, AgentListItem, AgentDetail, AgentOnboardingResponse, AgentRelatedCounts, AgentDeleteResult, ScoreLogItem, ActivityLogItem, PageResult } from '@/types'

/**
 * 可用模型分组（V49 后端 /api/agents/listAvailableModels）。
 * 仅含 available=true 的 Provider 与 enabled=1 的模型，供内部 LLM 注册时选择。
 */
export interface AvailableModelGroup {
  providerCode: string
  providerName: string
  defaultModel?: string
  models: string[]
}

/**
 * V52 skill-options 响应：capabilitySkills=模型能力锁定（不可取消，自动并入），
 * availableOptionalSkills=可扩展白名单，degraded=true 表示模型未识别（前端降级提示）。
 */
export interface SkillOptions {
  modelType: string
  capabilitySkills: string[]
  availableOptionalSkills: string[]
  degraded: boolean
}

export const agentApi = {
  // ── 现有 ──
  list(params?: { role?: string; status?: string }) {
    return request.get<any, Agent[]>(paths.agents.list, { params })
  },
  // V49：注册弹窗模型选择数据源（内部 LLM 用），仅返回可用 Provider + 启用模型
  listAvailableModels() {
    return request.get<any, AvailableModelGroup[]>(paths.agents.listAvailableModels)
  },
  getById(id: string) {
    return request.get<any, Agent>(paths.agents.getById(id))
  },
  register(data: {
    name: string
    role: string
    description?: string
    accessType?: string
    modelType?: string
    // V47/A2: 显式技能优先，不传则按接入类型+名称/描述关键词自动推导
    skills?: string[]
  }) {
    return request.post(paths.agents.register, data)
  },

  // V52：查询指定 modelType 的技能选项（能力锁定 + 可扩展白名单），供注册/编辑弹窗三段式渲染
  // 冒号已由 paths.enc 统一 encodeURIComponent（路径参数，如 deepseek%3Adeepseek-v4-flash）
  skillOptions(modelType: string) {
    return request.get<any, SkillOptions>(paths.agents.skillOptions(modelType))
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
    return request.get<any, PageResult<AgentListItem>>(paths.agents.adminList, { params })
  },

  // ── 管理端详情 ──
  adminDetail(id: string) {
    return request.get<any, AgentDetail>(paths.agents.adminDetail(id))
  },

  // ── 更新 Agent 信息 ──
  // V47/A2: skills 显式传入整体替换；不传（undefined）则后端保持现状
  // V52: modelType 显式传入则重新校验并切换模型绑定；不传则后端保留原值
  updateProfile(id: string, data: { name?: string; remark?: string; skills?: string[]; modelType?: string }) {
    return request.put<any, void>(paths.agents.updateProfile(id), data)
  },

  // ── 切换状态 ──
  updateStatus(id: string, status: 'ACTIVE' | 'DISABLED') {
    return request.post<any, void>(paths.agents.updateStatus(id), { status })
  },

  // ── 重置 Key ──
  resetKey(id: string) {
    return request.post<any, { apiKey: string; message: string }>(paths.agents.resetKey(id))
  },

  // ── 关联数据统计 ──
  relatedCounts(id: string) {
    return request.get<any, AgentRelatedCounts>(paths.agents.relatedCounts(id))
  },

  // ── 级联删除 ──
  deleteAgent(id: string, confirmName: string) {
    return request.delete<any, AgentDeleteResult>(paths.agents.deleteById(id), { data: { confirmName } })
  },

  // ── 积分明细 ──
  scoreLogs(id: string, params?: { page?: number; pageSize?: number }) {
    return request.get<any, PageResult<ScoreLogItem>>(paths.agents.scoreLogs(id), { params })
  },

  // ── 活动日志 ──
  activityLogs(id: string, params?: { page?: number; pageSize?: number; action?: string }) {
    return request.get<any, PageResult<ActivityLogItem>>(paths.agents.activityLogs(id), { params })
  },

  // ── 接入内容生成 ──
  getOnboardingContent(id: string) {
    return request.get<any, AgentOnboardingResponse>(paths.agents.onboarding(id))
  },

  // 技能包 zip 下载（SKILL.md + scripts/ 整体交付）；blob 响应在拦截器放行，返回完整 response
  getSkillZip(id: string) {
    return request.get<any, AxiosResponse<Blob>>(paths.agents.skillZip(id), { responseType: 'blob' })
  },
}
