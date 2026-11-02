import { paths } from './paths'
import { useAuthStore } from '@/stores/auth'

// PA-6：工作台事件推送（SSE，GET 长连接）。与 chatStream 同源：fetch + ReadableStream，
// 鉴权头手动携带（EventSource 不支持自定义头）。
//
// 事件协议：
//   event: connected -> 建连握手
//   event: task      -> 任务状态可能变化（data=taskId，可为空），调用方重拉聚合
//   event: ping      -> 25s 心跳，忽略
//
// 服务端 30 分钟超时、代理也可能回收空闲连接：流结束/失败时回调 onClosed，
// 由调用方在面板仍打开时退避重连。

export interface WorkbenchStreamHandlers {
  /** 任务状态可能变化：调用方自行防抖后重拉 /workbench */
  onTaskChange: () => void
  /** 流结束或建连失败（主动 abort 不回调） */
  onClosed: () => void
}

export async function streamWorkbenchEvents(
  handlers: WorkbenchStreamHandlers,
  signal: AbortSignal
): Promise<void> {
  const auth = useAuthStore()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (auth.adminToken) {
    headers['X-Admin-Token'] = auth.adminToken
  }
  if (auth.agentKey) {
    headers['Authorization'] = `Bearer ${auth.agentKey}`
  }

  try {
    const resp = await fetch(`/api${paths.clarifications.workbenchStream}`, {
      method: 'GET',
      headers,
      signal
    })
    if (!resp.ok || !resp.body) {
      handlers.onClosed()
      return
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        handlers.onClosed()
        return
      }
      buffer += decoder.decode(value, { stream: true })
      let sep = buffer.indexOf('\n\n')
      while (sep !== -1) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        handleFrame(frame, handlers)
        sep = buffer.indexOf('\n\n')
      }
    }
  } catch {
    if (!signal.aborted) {
      handlers.onClosed()
    }
  }
}

function handleFrame(frame: string, handlers: WorkbenchStreamHandlers) {
  let event = 'message'
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      break
    }
  }
  if (event === 'task') {
    handlers.onTaskChange()
  }
}
