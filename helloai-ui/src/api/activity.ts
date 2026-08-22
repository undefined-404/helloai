import request from './request'
import { paths } from './paths'
import type { ActivityLog } from '@/types'

export const activityApi = {
  list(params?: { agentId?: number; subTaskId?: number; page?: number }) {
    return request.get<any, ActivityLog[]>(paths.activity.list, { params: { page: 0, ...params } })
  }
}
