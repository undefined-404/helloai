import request from './request'

export const rewardApi = {
  getMyScore(agentId: number) {
    return request.get<any, Record<string, any>>('/scores/me', { params: { agentId } })
  },
  leaderboard() {
    return request.get<any, Record<string, any>[]>('/scores/leaderboard')
  },
  adjust(data: { agentId: number; scoreDelta: number; reason: string; subTaskId?: number | null }) {
    return request.post('/scores/adjust', data)
  }
}