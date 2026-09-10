import request from './request'
import { paths } from './paths'
import type { AgentEventAuditQuery, AgentEventItem, LongId, PageResult } from '@/types'

// G-006：agent_event 读侧消费面（Replay 轨迹 / Audit 分页），纯只读
export const agentEventApi = {
  // Replay：按 runId 重建 Run 级完整执行轨迹（run-{taskId}-{roundNum}，createTime+id 有序）
  trace(runId: string) {
    return request.get<any, AgentEventItem[]>(paths.agentEvents.traceByRunId(runId))
  },
  // Replay：按 taskId 重建 Run 级轨迹（免传 runId，runId 规则收敛在后端 service，前端不感知）
  traceByTaskId(taskId: LongId) {
    return request.get<any, AgentEventItem[]>(paths.agentEvents.traceByTaskId(taskId))
  },
  // Replay：按 subTaskId 聚焦单个子任务的执行轨迹（子任务选择器过滤）
  traceBySubTaskId(subTaskId: LongId) {
    return request.get<any, AgentEventItem[]>(paths.agentEvents.traceBySubTaskId(subTaskId))
  },
  // Audit：按 taskId 分页查执行事实，eventType 可选过滤，按时间正序（执行顺序）
  audit(query: AgentEventAuditQuery) {
    return request.get<any, PageResult<AgentEventItem>>(paths.agentEvents.pageAuditByTaskId(query.taskId), {
      params: {
        eventType: query.eventType || undefined,
        page: query.page,
        pageSize: query.pageSize ?? 20
      }
    })
  }
}