import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

/**
 * PENDING 子任务「无候选等待重试」倒计时共享逻辑。
 *
 * 背景：4 主任务并发但外部 Agent 不足时，部分子任务长期无候选候选、停留 PENDING。
 * 后端会在 `sub_task.context.noCandidate` 写入下次允许重试时刻（键名与结构已冻结）：
 *   "noCandidate": { "rounds": 3, "nextDispatchAt": "2026-10-05T22:47:30+08:00",
 *                    "reason": "no_available_candidate", "updatedAt": "..." }
 * 达阈值转人工时另写 `context.manualIntervention`。
 *
 * 本模块是子任务详情页（SubTaskDetail.vue）与列表页（SubTaskList.vue）的单一事实源：
 * 契约解析、剩余秒数计算、文案生成三处口径一致，避免两页文案漂移。
 */

/** 后端 `context.noCandidate` 结构（字段按契约冻结，均为可选以兼容历史数据） */
export interface NoCandidateMark {
  /** 连续无候选轮数 */
  rounds?: number
  /** 下次允许重试时刻（ISO 8601 带时区） */
  nextDispatchAt?: string | null
  /** 原因枚举，如 no_available_candidate */
  reason?: string | null
  /** 标记写入时刻 */
  updatedAt?: string | null
}

export type PendingWaitKind = 'countdown' | 'manual'

export interface PendingWait {
  kind: PendingWaitKind
  text: string
}

/**
 * 从子任务 context 中提取 noCandidate 标记。
 * 返回 null 表示无标记或结构不合法（缺 nextDispatchAt）——不猜、不兜底编造时间。
 */
export function readNoCandidate(
  context: Record<string, any> | null | undefined
): NoCandidateMark | null {
  const raw = context?.noCandidate
  if (!raw || typeof raw !== 'object') return null
  const at = (raw as NoCandidateMark).nextDispatchAt
  if (typeof at !== 'string' || !at) return null
  return raw as NoCandidateMark
}

/**
 * 距 nextDispatchAt 的剩余秒数（向上取整，N ≤ 0 表示已到点）。
 * 时间字符串不可解析时返回 null（调用方据此降级，不显示倒计时）。
 */
export function remainingSeconds(
  nextDispatchAt: string | null | undefined,
  nowMs: number
): number | null {
  if (!nextDispatchAt) return null
  const target = Date.parse(nextDispatchAt)
  if (Number.isNaN(target)) return null
  return Math.ceil((target - nowMs) / 1000)
}

/** 倒计时文案（中文化、全角标点）；short=true 用于列表短版 */
export function noCandidateText(sec: number | null, short = false): string {
  if (sec === null) return ''
  if (sec <= 0) return short ? '即将重试' : '等待执行者 · 即将重试'
  return short ? '约 ' + sec + 's 后重试' : '等待执行者 · 约 ' + sec + ' 秒后重试'
}

/**
 * 汇总「PENDING 等待」状态：人工介入标记优先于倒计时。
 * 非 PENDING / 无任何标记 → 返回 null（不渲染任何等待提示）。
 */
export function resolvePendingWait(
  status: string | null | undefined,
  context: Record<string, any> | null | undefined,
  nowMs: number,
  short = false
): PendingWait | null {
  if (status !== 'PENDING') return null
  // 人工介入标记优先：后端已停止自动重派，倒计时会误导用户
  if (context?.manualIntervention) return { kind: 'manual', text: '等待人工介入' }
  const mark = readNoCandidate(context)
  if (!mark) return null
  const text = noCandidateText(remainingSeconds(mark.nextDispatchAt, nowMs), short)
  return text ? { kind: 'countdown', text } : null
}

export interface UsePendingCountdownOptions {
  /** 是否需要每秒 tick（如「当前存在可见倒计时」），false 时自动停表省开销 */
  active?: Ref<boolean> | (() => boolean)
}

/**
 * 每秒 tick 的共享时钟。仅当 active 为 true 时计时，active 变 false 或组件卸载时
 * 自动 clearInterval——调用方无需自己清理定时器。
 *
 * 关键约束：active 的求值【只能发生在挂载之后】，绝不可以在 setup 同步阶段触碰。
 * 原因：Vue 的 `watch`（即使非 immediate）会在 setup 里同步执行一次 getter，
 * 而调用方的 active 通常是「基于本 composable 返回的 now 计算的 computed」
 * （如 `computed(() => 倒计时文案存在)`），此刻解构赋值尚未完成，同步求值会命中 TDZ。
 * 故这里把首次判定与 watch 注册都放进 onMounted。
 */
export function usePendingCountdown(options: UsePendingCountdownOptions = {}) {
  const now = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | null = null
  let stopActiveWatch: (() => void) | null = null

  function isActive(): boolean {
    if (!options.active) return true
    return typeof options.active === 'function' ? options.active() : options.active.value
  }

  function start() {
    if (timer !== null) return
    now.value = Date.now()
    timer = setInterval(() => { now.value = Date.now() }, 1000)
  }

  function stop() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  onMounted(() => {
    if (isActive()) start()
    // 挂载后再建 watch，避免 setup 同步求值 active（见上方关键约束）
    stopActiveWatch = watch(isActive, (v) => { if (v) start(); else stop() })
  })

  onBeforeUnmount(() => {
    // 组件卸载必须停表，否则定时器持续触发已卸载组件的响应式更新
    stopActiveWatch?.()
    stopActiveWatch = null
    stop()
  })

  return { now, start, stop }
}