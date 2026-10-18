import request from './request'
import { paths } from './paths'
import type { PageResult } from '@/types'

/**
 * 管理端收件箱（2026-10-18）。
 *
 * <p><b>为什么从 /agent/inbox 切到 /inbox</b>：前者语义是「我（Agent）的收件箱」，
 * 用 {@code @RequestAttribute("_authId")} 取身份；admin 不是 Agent ⇒ 恒返回空数组
 * （实测 agent_inbox 有 53 条，页面却是空）。管理端改用 /inbox：可看全部、可按 Agent 过滤。</p>
 */

/** 收件箱行（管理端投影：agentName 已由后端补全，Long ID 为字符串）。 */
export interface InboxRow {
  id: string
  agentId: string | null
  /** Agent 名称；Agent 已删除时为 null，前端回退短 ID */
  agentName: string | null
  eventType: string
  eventId: string
  title: string
  summary: string | null
  refType: string | null
  refId: string | null
  isRead: number
  isArchived: number
  priority: string
  readTime: string | null
  createTime: string
}

export const inboxApi = {
  /** 分页查询：agentId 不传 = 全部；isRead 不传 = 不限（管理端默认看全部） */
  list(params?: {
    agentId?: string | number
    isRead?: number
    includeArchived?: boolean
    page?: number
    pageSize?: number
  }): Promise<PageResult<InboxRow>> {
    return request.get<any, PageResult<InboxRow>>(paths.inbox.list, { params })
  },
  count(agentId?: string | number): Promise<{ total_unread: number }> {
    return request.get<any, { total_unread: number }>(paths.inbox.unreadCount, { params: { agentId } })
  },
  markRead(id: string | number) {
    return request.post(paths.inbox.markRead(id))
  },
  markArchived(id: string | number) {
    return request.post(paths.inbox.archive(id))
  }
}
