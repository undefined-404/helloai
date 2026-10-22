import request from './request'
import { paths } from './paths'
import type { ActivityLogItem } from '@/types'

/**
 * 活动流列表。
 *
 * <p>2026-10-22：后端 `/api/activity/list` 已为每条记录补充
 * `agentName` / `agentAccessType`（列表页展示「哪个 Agent + 接入类型」），
 * 返回类型对齐页面消费的 {@link ActivityLogItem}。</p>
 */
export const activityApi = {
  list(params?: { agentId?: number; subTaskId?: number; page?: number }) {
    return request.get<any, ActivityLogItem[]>(paths.activity.list, { params: { page: 0, ...params } })
  }
}
