import request from './request'

/**
 * 知识库（RAG，REF-4.x）前端 API。
 *
 * 引用溯源（REF-4.3）：报告/核验文本内嵌 `[kb:{source}#{chunkIdx}]` 标记，前端经
 * {@link kbApi.chunk} 取对应分片正文渲染「可点开来源」卡片。鉴权头由 request 层
 * （X-Admin-Token）统一注入。调用方自行处理失败（捕获抛出的 Error）。
 */
export interface KbChunk {
  source: string
  chunkIdx: number
  content: string
  /** 会话附件来源时的原始文件名（弹窗标题用，避免把 `chat:{conv}:{att}` 摆给用户）；其它来源为 null */
  fileName?: string | null
}

export interface KbHit {
  source: string
  chunkIdx: number
  content: string
}

export interface KbSearchResult {
  query: string
  hits: KbHit[]
  totalChars: number
  truncated: boolean
}

export const kbApi = {
  /** 按来源 + 分片键取单个分片（引用溯源卡片点击展示来源原文）。
   *
   * <p>★路径不带 `/api` 前缀：`request` 实例的 baseURL 已是 `/api`，再写一遍会拼成
   * `/api/api/kb/chunk/...` 直接 404（2026-10-24 用户点引用卡片实测）。</p> */
  chunk(source: string, chunkIdx: number): Promise<KbChunk> {
    return request.get<any, KbChunk>(
      `/kb/chunk/${encodeURIComponent(source)}/${encodeURIComponent(chunkIdx)}`
    )
  },

  /** 检索知识库（管理端数据源 / 排查用）。 */
  search(query: string, maxResults?: number): Promise<KbSearchResult> {
    return request.get<any, KbSearchResult>('/kb/search', {
      params: { query, maxResults }
    })
  }
}