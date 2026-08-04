import request from './request'
import type { ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, Task, LongId } from '@/types'

// V29 对话式需求澄清: 每轮走一次 LLM，耗时远超全局 30s，create/send/retry 单请求覆盖 timeout
// v1.1 修复: 澄清会话 ID 为 Snowflake 长整型，URL/比较一律按 string 处理防 JS Number 精度丢失
// V39: create 支持 initialMode（'CHAT' 自由对话缺省 / 'CLARIFY' 方案澄清快捷直达）
// V40: 「转为方案」按钮已移除，转方案改为意图词触发 + 对话内二次确认（后端状态机）；
//      切换端点按代码规范 8.2 整改为 POST /toClarifyById/{id}、/toChatById/{id}
export const clarifyApi = {
  // 新建会话（首条用户消息触发一轮 LLM；plannerAgentId 空=系统自动选择；initialMode 缺省 'CHAT'）
  create(message: string, plannerAgentId?: LongId | null, webSearchEnabled?: boolean | null,
    initialMode: 'CHAT' | 'CLARIFY' = 'CHAT') {
    return request.post<any, ClarifyConversationDetail>(
      '/requirement-conversations',
      { message, plannerAgentId: plannerAgentId ?? null, webSearchEnabled: webSearchEnabled ?? null, initialMode },
      { timeout: 120_000 })
  },
  // 追加用户消息并走一轮 LLM 澄清（可附 V33 结构化选项回答快照）
  send(id: LongId, message: string, selectedOptions?: ClarifySelection[] | null) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/sendMessageById/${String(id)}`,
      { message, selectedOptions: selectedOptions?.length ? selectedOptions : null },
      { timeout: 120_000 })
  },
  // 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）
  retry(id: LongId) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/retryById/${String(id)}`, null, { timeout: 120_000 })
  },
  // V39 切换到方案澄清模式：置位 + 一轮 LLM 基于全量历史产终稿草案/结构化追问（V40 起前端无按钮入口，保留供内部/测试）
  // V40.2 /planner 斜杠命令入口：message 为命令后附加文本（落库进上下文后再切，不传则为纯切换）
  toClarify(id: LongId, message?: string | null) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/toClarifyById/${String(id)}`,
      { message: message ?? null }, { timeout: 120_000 })
  },
  // V39 切回自由对话模式：仅置位，不调用 LLM（V40 起前端无按钮入口，保留供内部/测试）
  toChat(id: LongId) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/toChatById/${String(id)}`)
  },
  // Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）
  plannerOptions() {
    return request.get<any, PlannerOption[]>('/requirement-conversations/listPlannerOptions')
  },
  // 会话列表（按创建时间倒序，LIMIT 50）
  list() {
    return request.get<any, RequirementConversation[]>('/requirement-conversations')
  },
  // 会话详情（含全部消息按 seq 升序）
  detail(id: LongId) {
    return request.get<any, ClarifyConversationDetail>(`/requirement-conversations/getById/${String(id)}`)
  },
  // 终稿确认: 创建任务（PENDING）并回填会话 FINALIZED
  finalize(id: LongId) {
    return request.post<any, Task>(`/requirement-conversations/finalizeById/${String(id)}`)
  },
  // 重新生成: FINALIZED 会话原任务已删除时，复用终稿重建任务
  regenerate(id: LongId) {
    return request.post<any, Task>(`/requirement-conversations/regenerateById/${String(id)}`)
  },
  // 放弃会话: ACTIVE → ABANDONED
  abandon(id: LongId) {
    return request.post<any, void>(`/requirement-conversations/abandonById/${String(id)}`)
  }
}
