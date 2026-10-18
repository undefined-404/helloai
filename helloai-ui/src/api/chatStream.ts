import { paths } from './paths'
import { useAuthStore } from '@/stores/auth'
import type { ClarifySelection, LongId } from '@/types'

// Chat SSE 流式发送（S1 最小闭环）：fetch + ReadableStream 逐帧消费，替代 axios（其拦截器面向
// JSON R 包裹体，对流不适用）。鉴权头与 request.ts 同源（X-Admin-Token / Bearer），手动携带。
//
// 事件协议（与服务端 RequirementConversationController.streamSendById 对齐）：
//   event: token     -> data: 增量正文文本块（服务端已按 ≥50 字符或 ≥100ms 聚合）
//   event: reasoning -> data: 增量思考过程块（推理模型，2026-10-18 新增；只做展示，不并入正文）
//   event: done      -> data: [DONE]（主回复已落库，调用方随后拉 detail 收敛）
//   event: error     -> data: 错误消息（user 消息已落库，可走 retry 重试链路）
// 流非正常结束（连接断开且未收到 done/error）由本层兜底转 error。

export interface ChatStreamHandlers {
  /** 会话首帧（仅 streamCreate 会触发）：新会话 id，调用方据此在 done 后拉 detail */
  onSession?: (conversationId: string) => void
  /** 增量文本块（按序拼接即完整回复，与落库全文一致） */
  onToken: (token: string) => void
  /** 增量思考过程块（可选；推理模型才有，非推理模型永不触发） */
  onReasoning?: (text: string) => void
  /** 主回复生成完毕并落库（成功语义） */
  onDone: () => void
  /** 流失败（含服务端业务拒绝与连接中断），message 可直接提示 */
  onError: (message: string) => void
}

/**
 * 发送 CHAT 流式消息并消费 SSE 帧。Promise 在流收尾（done/error/断开）后 resolve，
 * 不会 reject——所有失败路径都收敛到 onError，调用方 await 后统一清理发送态。
 * @param signal 可选 AbortSignal（调用方主动中断，如切换会话）
 */
export async function streamSendConversation(
  id: LongId,
  message: string,
  selectedOptions: ClarifySelection[] | null,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  return consumeSse(
    `/api${paths.clarifications.streamSend(id)}`,
    { message, selectedOptions: selectedOptions?.length ? selectedOptions : null },
    handlers,
    signal
  )
}

/**
 * 新建会话 + 首轮流式（2026-10-18）：与 {@link streamSendConversation} 共用同一 SSE 消费内核，
 * 仅端点与请求体不同。首帧 `session` 事件回传新会话 id（通过 {@code handlers.onSession}）。
 */
export async function streamCreateConversation(
  message: string,
  plannerAgentId: string | null,
  webSearchEnabled: boolean | null,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  return consumeSse(
    `/api${paths.clarifications.streamCreate}`,
    { message, plannerAgentId, webSearchEnabled },
    handlers,
    signal
  )
}

/** SSE 消费内核（两个入口共用）：建连 → 逐帧解析 → 分派到 handlers。 */
async function consumeSse(
  url: string,
  payload: unknown,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const auth = useAuthStore()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    // 显式声明 SSE 媒体类型：①语义正确（fetch 默认 */* 会被服务端按 produces 兜底协商）；
    // ②服务端 GlobalExceptionHandler 据此识别 SSE 场景，异常时写 event:error 帧而非 JSON R 体
    Accept: 'text/event-stream'
  }
  if (auth.adminToken) {
    headers['X-Admin-Token'] = auth.adminToken
  }
  if (auth.agentKey) {
    headers['Authorization'] = `Bearer ${auth.agentKey}`
  }

  let settled = false
  const finish = (kind: 'done' | 'error', message?: string) => {
    if (settled) return
    settled = true
    if (kind === 'done') {
      handlers.onDone()
    } else {
      handlers.onError(message || '回复连接中断，请重试')
    }
  }

  // 单帧解析（SSE 标准：event:/data: 行 + 空行分隔，数据行可能多行拼 data）
  const handleFrame = (frame: string) => {
    let event = 'message'
    let data = ''
    for (const line of frame.split('\n')) {
      if (!line) continue
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        data = data ? `${data}\n${line.slice(5)}` : line.slice(5).trimStart()
      }
    }
    if (event === 'session' && data) {
      handlers.onSession?.(data)
    } else if (event === 'token' && data) {
      handlers.onToken(data)
    } else if (event === 'reasoning' && data) {
      handlers.onReasoning?.(data)
    } else if (event === 'done') {
      finish('done')
    } else if (event === 'error') {
      finish('error', data || '回复生成失败')
    }
  }

  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal
    })
    if (!resp.ok) {
      // 后端业务拒绝（4xx/5xx）：优先取 R 包裹体里的中文 msg
      const text = await resp.text().catch(() => '')
      let msg = `请求失败（HTTP ${resp.status}）`
      try {
        const body = JSON.parse(text) as { msg?: string }
        if (body?.msg) msg = body.msg
      } catch { /* 非 JSON 错误体，保留兜底文案 */ }
      finish('error', msg)
      return
    }
    if (!resp.body) {
      finish('error', '当前浏览器不支持流式响应')
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        // 服务端 complete()：事件流天然结束；若始终未收到终帧，视为异常截断
        if (!settled) finish('error', '回复中断，请刷新后重试')
        return
      }
      buffer += decoder.decode(value, { stream: true })
      let sep = buffer.indexOf('\n\n')
      while (sep !== -1) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        handleFrame(frame)
        sep = buffer.indexOf('\n\n')
      }
    }
  } catch (e) {
    if (!settled) {
      finish('error', e instanceof Error ? e.message.replace(/^Failed to fetch[: ]*/i, '') : '网络错误')
    }
  }
}