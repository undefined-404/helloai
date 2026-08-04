import request from './request'
import type { ActivityLog } from '@/types'

export const activityApi = {
  list(params?: { agentId?: number; subTaskId?: number; page?: number }) {
    return request.get<any, ActivityLog[]>('/activity/list', { params: { page: 0, ...params } })
  }
}
