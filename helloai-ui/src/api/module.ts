import request from './request'
import { paths } from './paths'
import type { ModuleItem, LongId } from '@/types'

/**
 * 任务模块 API（v2.5 M4.5 派发控制台）。
 *
 * <p>对应后端 {@code GET /api/modules/findModulesByTaskId/{taskId}}、
 * {@code POST /api/modules/setModulesByTaskId/{taskId}}。
 * 注意：后端 {@code CreateModuleRequest} 接收 description 字段但 Module 实体未持久化该列，
 * 故前端只传 name；后端会忽略 description。</p>
 */
export const moduleApi = {
  /** 列出指定任务下的模块（按 sortOrder 升序） */
  list(taskId: LongId) {
    return request.get<any, ModuleItem[]>(paths.modules.list(taskId))
  },
  /** 行内新建模块（QuickDispatchDialog "+ 新建模块" 使用） */
  create(taskId: LongId, data: { name: string }) {
    return request.post<any, ModuleItem>(paths.modules.set(taskId), data)
  }
}