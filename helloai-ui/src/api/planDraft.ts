import request from './request'
import { paths } from './paths'
import type { PlanDraft, LongId } from '@/types'

/** 草案暂存区读接口（`AD-5a-3`）。 */
export const planDraftApi = {
  /** 当前轮 ACTIVE 草案，按 code 升序；空列表 = 暂存区无行（用方回落旧读法） */
  listByTask(taskId: LongId) {
    return request.get<any, PlanDraft[]>(paths.planDrafts.listByTask(taskId))
  }
}