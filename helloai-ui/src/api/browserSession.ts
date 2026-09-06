import request from './request'
import { paths } from './paths'
import type { BrowserSession, PageResult } from '@/types'

/**
 * Browser 会话展示 API（N-003，C3 配套）。
 * 会话为登记/展示语义（BEGIN/ACTIVE/CLOSED/FAILED），无反向写 task/sub_task 端点。
 */
export const browserSessionApi = {
  page(params?: { page?: number; size?: number; agentId?: number; status?: string }) {
    return request.get<any, PageResult<BrowserSession>>(paths.admin.browserSessions, { params })
  },
  getById(id: string | number) {
    return request.get<any, BrowserSession>(paths.admin.browserSessionById(id))
  }
}
