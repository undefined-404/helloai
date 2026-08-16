import { onBeforeUnmount, watch, type Ref } from 'vue'

/**
 * 通用 setInterval 自动刷新 composable。
 *
 * 使用场景：列表页/详情页需要按周期静默重拉数据（Dashboard 活跃度、子任务依赖图、值班概览等）。
 * - 通过 start() / stop() 控制；
 * - 提供 shouldRun 响应式判断：仅当 shouldRun 为 true 时才会执行回调；
 * - 组件卸载时自动 stop，避免泄漏；
 * - 监听依赖 key（如 taskId）变化时自动按新 key 重启。
 *
 * 用法：
 *   const { start, stop } = useAutoRefresh(async () => { await load() }, {
 *     intervalMs: 10_000,
 *     shouldRun: computed(() => viewMode.value === 'dag' && taskId.value),
 *     key: computed(() => taskId.value)
 *   })
 *   onMounted(() => { if (taskId.value) start() })
 */
export interface UseAutoRefreshOptions {
  /** 轮询间隔（毫秒） */
  intervalMs: number
  /** 返回 true 时才执行回调（避免拉已终结任务的数据） */
  shouldRun?: Ref<boolean> | (() => boolean)
  /** 关键参数变化时重启 timer，例如 taskId / 子任务 ID */
  key?: Ref<string | number | null | undefined> | (() => string | number | null | undefined)
  /** 是否在 setup 完成后立即启动；默认 false，需要调用方在 onMounted 显式 start() */
  autoStart?: boolean
}

export function useAutoRefresh(
  fn: () => void | Promise<void>,
  options: UseAutoRefreshOptions
) {
  let timer: ReturnType<typeof setInterval> | null = null

  function shouldRunNow(): boolean {
    if (!options.shouldRun) return true
    return typeof options.shouldRun === 'function'
      ? options.shouldRun()
      : options.shouldRun.value
  }

  function currentKey(): string | number | null | undefined {
    if (!options.key) return undefined
    return typeof options.key === 'function'
      ? options.key()
      : options.key.value
  }

  async function tick() {
    if (!shouldRunNow()) return
    try {
      await fn()
    } catch {
      // composable 不感知具体业务错误，吞掉避免静默刷新把控制台染红
    }
  }

  function start() {
    if (timer) return
    timer = setInterval(tick, options.intervalMs)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  // 关键参数变化：自动重启以使新 key 生效
  if (options.key) {
    watch(currentKey, (_v, old) => {
      if (old !== undefined) stop()
      if (shouldRunNow()) start()
    })
  }

  onBeforeUnmount(stop)

  if (options.autoStart) start()

  return { start, stop }
}