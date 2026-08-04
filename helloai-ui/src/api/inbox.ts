import request from './request'
import type { AgentInbox } from '@/types'

export const inboxApi = {
  list(limit?: number) {
    return request.get<any, AgentInbox[]>('/agent/inbox', { params: { limit: limit || 20 } })
  },
  count() {
    return request.get<any, { total_unread: number }>('/agent/inbox/getUnreadCount')
  },
  markRead(id: number) {
    return request.post(`/agent/inbox/markReadById/${id}`)
  },
  markArchived(id: number) {
    return request.post(`/agent/inbox/archiveById/${id}`)
  }
}
