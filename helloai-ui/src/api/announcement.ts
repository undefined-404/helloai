import request from './request'
import { paths } from './paths'

/**
 * 置顶公告（MSG-6，设计 §3.9）。
 *
 * 读取侧（published/seen/unreadCount）登录即可读，恒按当前登录用户归属过滤已读位;
 * announcement.enabled 开关 OFF 时 published 返回 null / unreadCount=0（停发 + 隐藏）。
 * 管理侧（manage/draft/publish/archive/history/schemaHistory）走 notice:view / notice:edit 动作码。
 * Long ID 已由后端序列化为字符串（前端 LongId 契约）。
 */

/** 当前置顶公告投影（对应后端 PublishedAnnouncementVO，内容取最新已发布快照）。 */
export interface PublishedAnnouncement {
  id: string
  title: string
  body: string
  /** 已发布版本号（每次发布 +1） */
  version: number
  publishedAt: string | null
  /** 当前用户已读到的版本（单调递增） */
  seenVersion: number
  /** seenVersion < version 即未读 */
  unread: boolean
}

/** 公告管理工作副本投影（对应后端 AnnouncementVO）。 */
export interface AnnouncementDraft {
  id: string
  title: string
  body: string
  /** DRAFT / PUBLISHED / ARCHIVED */
  status: string
  pinned: number
  version: number
  publishedAt: string | null
  createTime: string
  updateTime: string
}

/** 发布历史快照（对应后端 AnnouncementRevisionVO）。 */
export interface AnnouncementRevision {
  id: string
  version: number
  title: string
  body: string
  publishedBy: string
  publishedAt: string | null
}

/** 迁移历史辅助视图（对应后端 SchemaHistoryVO，公告撰写素材）。 */
export interface SchemaHistoryRow {
  version: string
  description: string
  installedOn: string | null
  success: boolean
}

export const announcementApi = {
  /** 当前置顶公告 + 我的已读位（登录可读）。开关 OFF / 无已发布 → null。 */
  published(): Promise<PublishedAnnouncement | null> {
    return request.get<any, PublishedAnnouncement | null>(paths.announcement.published)
  },
  /** 上报已读版本（登录，幂等单调递增）。 */
  markSeen(id: string | number, version: number) {
    return request.post(paths.announcement.seen(id), null, { params: { version } })
  },
  /** 公告未读数（登录可读，并入铃铛角标）。开关 OFF → 0。 */
  unreadCount(): Promise<number> {
    return request.get<any, number>(paths.announcement.unreadCount)
  },
  /** 管理侧：当前工作副本（notice:view）。无草稿 → null。 */
  manage(): Promise<AnnouncementDraft | null> {
    return request.get<any, AnnouncementDraft | null>(paths.announcement.manage)
  },
  /** 管理侧：保存草稿（notice:edit）。 */
  saveDraft(data: { title: string; body: string }) {
    return request.post(paths.announcement.draft, data)
  },
  /** 管理侧：发布（notice:edit，version+1 + 写 revision）。 */
  publish(): Promise<AnnouncementDraft> {
    return request.post<any, AnnouncementDraft>(paths.announcement.publish)
  },
  /** 管理侧：归档（notice:edit）。 */
  archive() {
    return request.post(paths.announcement.archive)
  },
  /** 管理侧：发布历史快照（notice:view，按版本倒序）。 */
  history(): Promise<AnnouncementRevision[]> {
    return request.get<any, AnnouncementRevision[]>(paths.announcement.history)
  },
  /** 管理侧：迁移历史只读辅助视图（notice:view）。 */
  schemaHistory(): Promise<SchemaHistoryRow[]> {
    return request.get<any, SchemaHistoryRow[]>(paths.announcement.schemaHistory)
  }
}