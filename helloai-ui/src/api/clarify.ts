import request from './request'
import { paths } from './paths'
import type { AxiosResponse } from 'axios'
import type { ChatAttachmentItem, ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, Task, LongId } from '@/types'

// V29 对话式需求澄清: 每轮走一次 LLM，耗时远超全局 30s，create/send/retry 单请求覆盖 timeout
// v1.1 修复: 澄清会话 ID 为 Snowflake 长整型，URL/比较一律按 string 处理防 JS Number 精度丢失
// V39: create 支持 initialMode（'CHAT' 自由对话缺省 / 'CLARIFY' 方案澄清快捷直达）
// V46: initialMode 已废弃，新会话始终 CHAT 模式

/**
 * 单轮「走 LLM」请求的超时。
 *
 * <p>2026-10-27 由 120s 提到 300s：选技能的请求会同步跑完整条渲染链
 * （建沙箱 → 注入技能包 → node 渲染 → 回读产物）。布局门失败时后端会
 * 重新生成 spec 再渲染、仍失败再降档 standard 重渲（见 ConversationSkillRunner），
 * 最坏 85~170s。继续用 120s 会在重试中途 abort —— 后端成功落库、前端却报超时，
 * 用户看不到结果，比直接失败更糟。</p>
 *
 * <p>为什么抽成常量：此前 5 个接口各写一个 120_000，改一处漏一处必然分叉。
 * 上限约束在 nginx（nginx.server.conf 对对话类接口配了 proxy_read_timeout
 * 300s / 3600s），所以 300s 不会先被网关掐断。</p>
 */
const LLM_TIMEOUT_MS = 300_000

/** 上传不涉及 LLM，维持短超时（大文件另由后端限制）。 */
const UPLOAD_TIMEOUT_MS = 120_000

export const clarifyApi = {
  // 新建会话（首条用户消息触发一轮 LLM；plannerAgentId 空=系统自动选择；新会话始终 CHAT 模式）
  create(message: string, plannerAgentId?: LongId | null, webSearchEnabled?: boolean | null,
         clientToken?: string | null) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.create,
      // clientToken：新会话首句前上传的附件临时归属，建会时后端按 token 认领（可空）
      { message, plannerAgentId: plannerAgentId ?? null, webSearchEnabled: webSearchEnabled ?? null,
        clientToken: clientToken ?? null },
      { timeout: LLM_TIMEOUT_MS })
  },
  // 追加用户消息并走一轮 LLM 澄清（可附 V33 结构化选项回答快照）
  send(id: LongId, message: string, selectedOptions?: ClarifySelection[] | null) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.send(id),
      { message, selectedOptions: selectedOptions?.length ? selectedOptions : null },
      { timeout: LLM_TIMEOUT_MS })
  },
  // 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）
  retry(id: LongId) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.retry(id), null, { timeout: LLM_TIMEOUT_MS })
  },
  // V50 编辑已发送的用户消息并重跑（对齐 ChatGPT/DeepSeek/Kimi：编辑 → 截断 → 从该点重答）
  editMessage(id: LongId, messageId: LongId, newContent: string) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.editMessage(id, messageId),
      { message: newContent }, { timeout: LLM_TIMEOUT_MS })
  },
  // V39 切换到方案澄清模式：置位 + 一轮 LLM 基于全量历史产终稿草案/结构化追问（V40 起前端无按钮入口，保留供内部/测试）
  // V40.2 /planner 斜杠命令入口：message 为命令后附加文本（落库进上下文后再切，不传则为纯切换）
  toClarify(id: LongId, message?: string | null) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.toClarify(id),
      { message: message ?? null }, { timeout: LLM_TIMEOUT_MS })
  },
  // V39 切回自由对话模式：仅置位，不调用 LLM（V40 起前端无按钮入口，保留供内部/测试）
  toChat(id: LongId) {
    return request.post<any, ClarifyConversationDetail>(
      paths.clarifications.toChat(id))
  },
  // Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）
  plannerOptions() {
    return request.get<any, PlannerOption[]>(paths.clarifications.plannerOptions)
  },
  // 会话列表（按创建时间倒序，LIMIT 50）
  list() {
    return request.get<any, RequirementConversation[]>(paths.clarifications.list)
  },

  // ── 会话附件（V121，需求对话「添加文件」）─────────────────────────────
  /** 上传附件（1~5 个）；新会话首句前不传 conversationId，改传前端生成的 clientToken。 */
  uploadAttachments(form: FormData) {
    return request.post<any, ChatAttachmentItem[]>(
      paths.chatAttachments.upload, form,
      { timeout: UPLOAD_TIMEOUT_MS, headers: { 'Content-Type': 'multipart/form-data' } })
  },
  /** 会话附件清单（解析状态轮询用；新会话首句前按 clientToken 查临时批次）。 */
  listAttachments(conversationId?: LongId | null, clientToken?: string | null) {
    const params: Record<string, string> = {}
    if (conversationId != null) params.conversationId = String(conversationId)
    else if (clientToken) params.clientToken = clientToken
    return request.get<any, ChatAttachmentItem[]>(paths.chatAttachments.list, { params })
  },
  /** 删除附件（软删，幂等）。 */
  deleteAttachment(id: LongId) {
    return request.post<any, void>(paths.chatAttachments.deleteById(id))
  },
  /**
   * ⚠️ 裸直链地址：浏览器导航请求不带 X-Admin-Token → 一律 401。
   * 仅保留给「确需分享 URL」等极少数场景；产物打开/下载一律走下方 blob 通道
   * （经 request 拦截器补鉴权头）。
   * @deprecated 产物交互请用 attachmentDownloadBlob / attachmentPreviewBlob / attachmentPreviewText
   */
  attachmentPreviewUrl(id: LongId) {
    return `${import.meta.env.VITE_API_BASE ?? '/api'}${paths.chatAttachments.previewById(id)}`
  },
  /** @deprecated 产物交互请用 attachmentDownloadBlob（裸直链带不了鉴权头，会 401）。 */
  attachmentDownloadUrl(id: LongId) {
    return `${import.meta.env.VITE_API_BASE ?? '/api'}${paths.chatAttachments.downloadById(id)}`
  },
  /** 下载 blob 通道（attachment 语义）：经 request 拦截器带鉴权头拉取，
   *  文件名从 Content-Disposition（RFC 5987）解析，落盘前生成临时 <a download>。 */
  attachmentDownloadBlob(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(paths.chatAttachments.downloadById(id), { responseType: 'blob' })
  },
  /** 内嵌预览二进制通道（图片 / PDF → blob URL 交给 iframe；token 在请求头，不能裸 <a href> 直链）。 */
  attachmentPreviewBlob(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(paths.chatAttachments.previewById(id), { responseType: 'blob' })
  },
  /** 内嵌预览文本通道（md / 源码 / html 等 → 项目 MarkdownView / <pre> 渲染；超限或不可预览由后端 413 兜底）。 */
  attachmentPreviewText(id: LongId) {
    return request.get<any, AxiosResponse<string>>(paths.chatAttachments.previewById(id), { responseType: 'text' })
  },
  // 会话详情（含全部消息按 seq 升序）
  detail(id: LongId) {
    return request.get<any, ClarifyConversationDetail>(paths.clarifications.detail(id))
  },
  // 终稿确认: 创建任务（PENDING）并回填会话 FINALIZED
  finalize(id: LongId) {
    return request.post<any, Task>(paths.clarifications.finalize(id))
  },
  // 重新生成: FINALIZED 会话原任务已删除时，复用终稿重建任务
  regenerate(id: LongId) {
    return request.post<any, Task>(paths.clarifications.regenerate(id))
  },
  // 放弃会话: ACTIVE → ABANDONED
  abandon(id: LongId) {
    return request.post<any, void>(paths.clarifications.abandon(id))
  },
  // 删除已放弃会话: 仅 ABANDONED 可删（软删，列表自动隐藏; ACTIVE/FINALIZED 后端拒绝）
  deleteConversation(id: LongId) {
    return request.post<any, void>(paths.clarifications.deleteById(id))
  }
}
