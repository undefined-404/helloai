import { paths } from './paths'
import { useAuthStore } from '@/stores/auth'

// MSG-5：人侧站内消息推送（SSE，GET 长连接）。与 workbenchStream 同源：fetch + ReadableStream，
// 鉴权头手动携带（EventSource 不支持自定义头）。
//
// 事件协议（后端 NotificationEventHub）：
//   event: connected    -> 建连握手
//   event: notification -> 新消息落库且事务提交（data=notificationId），调用方重拉未读 / 列表
//   event: ping         -> 25s 心跳，忽略
//
// 服务端 30 分钟超时、代理也可能回收空闲连接：流结束/失败时回调 onClosed，
// 由调用方在铃铛仍可见时退避重连。帧体只带 id，正文一律回源查库——漏推/重复推只影响提示时机。

export interface NotificationStreamHandlers {
  /** 新消息到了：调用方自行防抖后重拉未读数 / 列表 */
  onNotification: () => void
  /** 流结束或建连失败（主动 abort 不回调） */
  onClosed: () => void
}

export async function streamNotifications(
  handlers: NotificationStreamHandlers,
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
    const resp = await fetch(`/api${paths.notification.stream}`, {
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

function handleFrame(frame: string, handlers: NotificationStreamHandlers) {
  let event = 'message'
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      break
    }
  }
  if (event === 'notification') {
    handlers.onNotification()
  }
}