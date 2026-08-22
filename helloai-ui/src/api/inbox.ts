import request from './request'
import { paths } from './paths'
import type { AgentInbox } from '@/types'

export const inboxApi = {
  list(limit?: number) {
    return request.get<any, AgentInbox[]>(paths.inbox.list, { params: { limit: limit || 20 } })
  },
  count() {
    return request.get<any, { total_unread: number }>(paths.inbox.unreadCount)
  },
  markRead(id: number) {
    return request.post(paths.inbox.markRead(id))
  },
  markArchived(id: number) {
    return request.post(paths.inbox.archive(id))
  }
}
