import request from './request'
import { paths } from './paths'
import type { PageResult, Team, TeamMember } from '@/types'

/**
 * Team 组合管理 API（N-002，C2）。
 * Team = 命名可复用的 Agent 组合；任务 agent_policy.teamId 引用后由后端展开槽位快照。
 */
export const teamApi = {
  /** 分页列表（status 可选：DRAFT / ACTIVE / ARCHIVED） */
  page(params?: { page?: number; size?: number; status?: string }) {
    return request.get<any, PageResult<Team>>(paths.admin.teams, { params })
  },
  getById(id: string | number) {
    return request.get<any, Team>(paths.admin.teamById(id))
  },
  create(data: { name: string; description?: string }) {
    return request.post<any, Team>(paths.admin.teams, data)
  },
  update(id: string | number, data: { name?: string; description?: string }) {
    return request.put<any, Team>(paths.admin.teamById(id), data)
  },
  /** 发布（DRAFT → ACTIVE，需至少 1 名 EXECUTOR 且成员 ACTIVE） */
  publish(id: string | number) {
    return request.post<any, Team>(paths.admin.teamPublish(id))
  },
  archive(id: string | number) {
    return request.post<any, Team>(paths.admin.teamArchive(id))
  },
  members(id: string | number) {
    return request.get<any, TeamMember[]>(paths.admin.teamMembers(id))
  },
  addMember(id: string | number, data: { agentId: number; slotRole: string }) {
    return request.post<any, TeamMember>(paths.admin.teamMembers(id), data)
  },
  removeMember(id: string | number, agentId: number) {
    return request.delete<any, void>(paths.admin.teamMember(id, agentId))
  }
}
