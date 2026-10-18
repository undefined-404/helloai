import request from './request'

/**
 * 事件码目录（REF-6.8，事件码服务端单一来源）。
 *
 * 前端经 `/api/events/catalog` 拉取 code → label/desc 字典，作为唯一事实源，
 * 不再保留 EVENT_META / sequenceFlow.LABEL 硬编码副本。鉴权头由 request 层统一注入。
 */
export interface EventCatalogItem {
  code: string
  label: string
  desc: string
}

export const eventApi = {
  /** 全量用户可见事件码目录（code → label/desc）。 */
  catalog(): Promise<EventCatalogItem[]> {
    return request.get<any, EventCatalogItem[]>('/events/catalog')
  }
}
/** 工具名目录项（2026-10-18）：toolName → 中文短名，供时间线/时序图渲染。 */
export interface ToolCatalogItem {
  toolName: string
  label: string
  desc: string
}

/** 工具名目录 API（服务端单一事实源：ToolDisplayName 枚举）。 */
export const toolApi = {
  catalog(): Promise<ToolCatalogItem[]> {
    return request.get<any, ToolCatalogItem[]>('/tools/catalog')
  }
}
