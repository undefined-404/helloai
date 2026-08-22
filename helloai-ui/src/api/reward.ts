import request from './request'

import { paths } from './paths'

export const rewardApi = {
  getMyScore(agentId: number) {
    return request.get<any, Record<string, any>>(paths.scores.me, { params: { agentId } })
  },
  leaderboard() {
    return request.get<any, Record<string, any>[]>(paths.scores.leaderboard)
  },
  logs(params?: { page?: number; pageSize?: number }) {
    return request.get<any, any>(paths.scores.logs, { params })
  },
  adjust(data: { agentId: number; scoreDelta: number; reason: string; subTaskId?: number | null }) {
    return request.post(paths.scores.adjust, data)
  }
}