import request from './request'
import { paths } from './paths'
import type { PageResult } from '@/types'

/**
 * 人侧站内消息（MSG-5，设计 §3.9）。
 *
 * 登录可读，恒按当前登录用户归属过滤（后端 CurrentUser.userId()）;
 * notification.enabled 开关 OFF 时读取返回空 / 未读 0（停发 + 隐藏）。
 * Long ID 已由后端序列化为字符串（前端 LongId 契约）。
 */

/** 消息行投影（对应后端 NotificationVO）。 */
export interface NotificationRow {
  id: string
  /** 消息类型名（NotificationType 枚举名） */
  type: string
  /** 严重度：NORMAL / HIGH / URGENT */
  severity: string
  title: string
  summary: string | null
  /** 深链归属类型：TASK / SUB_TASK / CONVERSATION / AGENT / RELEASE */
  refType: string | null
  refId: string | null
  /** 渲染数据（仅作列表摘要，不作事实依据） */
  payload: Record<string, unknown> | null
  isRead: number
  createTime: string
  readAt: string | null
}

/** 平台开关态（对应后端 NotificationFeatures）。 */
export interface NotificationFeatures {
  notificationEnabled: boolean
  announcementEnabled: boolean
}

export const notificationApi = {
  /** 平台开关态：前端铃铛 / 公告条闸门用。 */
  features(): Promise<NotificationFeatures> {
    return request.get<any, NotificationFeatures>(paths.notification.features)
  },
  /** 我的消息分页（未读优先，可按类型筛）。 */
  page(params?: {
    type?: string
    page?: number
    pageSize?: number
  }): Promise<PageResult<NotificationRow>> {
    return request.get<any, PageResult<NotificationRow>>(paths.notification.page, { params })
  },
  /** 我的未读数（铃铛角标的消息部分）。 */
  unreadCount(): Promise<number> {
    return request.get<any, number>(paths.notification.unreadCount)
  },
  markRead(id: string | number) {
    return request.post(paths.notification.markRead(id))
  },
  markAllRead() {
    return request.post(paths.notification.markAllRead)
  },
  archive(id: string | number) {
    return request.post(paths.notification.archive(id))
  }
}