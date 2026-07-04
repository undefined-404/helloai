import request from './request'
import type { AgentInbox } from '@/types'

export const inboxApi = {
  list(limit?: number) {
    return request.get<any, AgentInbox[]>('/agent/inbox', { params: { limit: limit || 20 } })
  },
  count() {
    return request.get<any, { total_unread: number }>('/agent/inbox/count')
  },
  markRead(id: number) {
    return request.put(`/agent/inbox/${id}/read`)
  },
  markArchived(id: number) {
    return request.put(`/agent/inbox/${id}/archive`)
  }
}
