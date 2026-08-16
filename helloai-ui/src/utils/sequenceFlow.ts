// ============================================================
// 子任务时间线 → 泳道时序图（mermaid sequenceDiagram）转换工具
// ------------------------------------------------------------
// 输入：TaskTimelineItem[]（按 id 升序，已含 role / eventType / agentId / payload）
// 输出：mermaid sequenceDiagram 语法字符串
// ------------------------------------------------------------
// 设计要点：
// 1. 固定 6 条泳道（用户已确认）：
//    BIZ 业务系统 / SCH 调度引擎 / EXT 执行 Agent / RVW 核验 Agent
//    / DLQ 死信队列 / OPS 人工运维台
// 2. 事件 → 泳道映射规则：
//    - task_plan_* / task_auto_completed         → BIZ
//    - dispatch_* / execution_command_* / poll_*  → SCH
//    - execute_* / llm_call_* / result_discarded → EXT（按 agentId 解析执行 Agent 实例名）
//    - review_* / auto_review_*                    → RVW
//    - dead_letter（无 manual）                   → DLQ
//    - dead_letter_manual_assign / trigger=manual → OPS
// 3. 箭头方向：相邻事件对 → 一条箭头
//    - 主动行为（dispatch / execute / submit / assign）→ 实线 "->>"：源 → 目标
//    - 响应行为（success / failed / returned / end）   → 虚线 "-->>"：响应方 → 发起方
//    - 自循环（自动重试 / 内部决策）                   → 自循环 "->>"：源 → 自身
// 4. Note 标注：失败/重试/熔断/人工介入/恢复点处加 Note，标注重试次数与耗时
// 5. Loop 折叠：连续 N 次同类型重试包成 loop 块
// ============================================================

import type { TaskTimelineItem } from '@/types'

// ── 泳道枚举 ──
export type Swimlane = 'BIZ' | 'SCH' | 'EXT' | 'RVW' | 'DLQ' | 'OPS'

export const SWIMLANE_ORDER: Swimlane[] = ['BIZ', 'SCH', 'EXT', 'RVW', 'DLQ', 'OPS']

export const SWIMLANE_LABEL: Record<Swimlane, string> = {
  BIZ: '业务系统',
  SCH: '调度引擎',
  EXT: '执行 Agent',
  RVW: '核验 Agent',
  DLQ: '死信队列',
  OPS: '人工运维台'
}

// ── 箭头类型 ──
export type ArrowKind = 'sync' | 'async' | 'response' | 'loop'

// ── 转换后的事件（含泳道归属 + 显示标签）──
export interface SequenceMessage {
  from: Swimlane
  to: Swimlane
  kind: ArrowKind
  label: string
  note?: string
  /** 用于合并相邻同泳道事件到 loop 块 */
  loopKey?: string
}

// ── 泳道分类 ──
export function classifySwimlane(ev: TaskTimelineItem): Swimlane {
  const t = ev.eventType
  const trigger = ev.payload?.trigger
  // 任务级：业务系统视角
  if (t.startsWith('task_plan_') || t === 'task_auto_completed') return 'BIZ'
  // 人工介入（死信重派 / 手动触发）→ 人工运维台
  if (t === 'sub_task_dead_letter_manual_assign') return 'OPS'
  if (trigger === 'manual' || trigger === 'dead_letter_redispatch') return 'OPS'
  // 死信（系统判定）→ 死信队列
  if (t === 'sub_task_dead_letter') return 'DLQ'
  // 核验 → 核验 Agent
  if (t.startsWith('subtask_review_') || t.startsWith('sub_task_auto_review_')) return 'RVW'
  // 执行 → 执行 Agent
  if (t.startsWith('sub_task_execute_') || t.startsWith('sub_task_llm_call_')
      || t === 'sub_task_deps_context_loaded' || t === 'sub_task_spec_context_loaded'
      || t === 'sub_task_execution_command_consume'
      || t === 'sub_task_execution_command_consume_skipped') return 'EXT'
  // 其余 SYSTEM 角色 → 调度引擎
  return 'SCH'
}

// ── 事件标签（人话化，复用 timelineFlow 的 EVENT_LABEL 风格，简短 4-12 字）──
const LABEL: Record<string, string> = {
  task_plan_generated: '生成任务拆解',
  task_plan_confirmed: '确认任务拆解',
  task_plan_rejected: '驳回任务拆解',
  task_plan_failed: '任务拆解失败',
  task_auto_completed: '任务最终完成',

  sub_task_dispatch_prepare: '准备派单',
  sub_task_auto_execute_dispatch: '发起自动派单',
  sub_task_auto_execute_dispatch_enter: '进入派单流程',
  sub_task_auto_execute_dispatch_ok: '派单成功',
  sub_task_auto_execute_dispatch_fail: '派单失败（无空闲 Agent）',
  sub_task_execution_command_created: '生成执行指令',
  sub_task_execution_command_consume: 'Agent 领取指令',
  sub_task_execution_command_consume_skipped: '跳过指令（已处理）',
  sub_task_execution_command_poll_recovery: '巡检恢复指令',

  sub_task_execute_enter: '进入执行',
  sub_task_execute_start: '开始执行',
  sub_task_execute_before_platform: '执行前准备',
  sub_task_deps_context_loaded: '装配依赖产出',
  sub_task_spec_context_loaded: '装配任务上下文',
  sub_task_llm_call_start: '调用大模型',
  sub_task_llm_call_end: '大模型返回',
  sub_task_llm_call_failed: '调用大模型失败',
  sub_task_execute_thinking: '思考/推理',
  sub_task_execute: '产出 AI 内容',
  sub_task_execute_submit: '提交产出',
  sub_task_execute_success: '执行成功',
  sub_task_execute_failed: '执行失败',
  sub_task_execute_result_discarded: '结果丢弃',
  sub_task_report_blocked: '执行受阻',

  sub_task_auto_review_passed: '核验通过',
  sub_task_auto_review_rejected: '核验驳回',
  sub_task_auto_review_unparseable: '核验异常',
  sub_task_auto_review_skip_max_rework: '已达最大返工，跳过核验',
  subtask_review_prompt: '发起核验',
  subtask_review_verdict: '核验结论',
  subtask_review_thinking: '核验思考',

  sub_task_dead_letter: '进入死信',
  sub_task_dead_letter_manual_assign: '人工指派'
}

function shortLabel(ev: TaskTimelineItem): string {
  return LABEL[ev.eventType] || ev.eventType.replace(/^sub_task_/, '').replace(/_/g, ' ')
}

// ── Note 推导：失败 / 重试 / 熔断 / 人工 / 恢复 → 加 note ──
function inferNote(prev: TaskTimelineItem | undefined, cur: TaskTimelineItem, attempt: number): string | undefined {
  const t = cur.eventType
  const p = cur.payload || {}
  // 失败事件
  if (t.includes('failed') || t.includes('rejected') || t.includes('blocked') || t.includes('unparseable')) {
    const reason = p.error || p.reason || p.issue || ''
    const head = attempt > 1 ? `第 ${attempt} 次失败` : '失败'
    return reason ? `${head}：${String(reason).slice(0, 60)}` : head
  }
  // 重派
  if (t === 'sub_task_execution_command_poll_recovery') {
    return '巡检恢复遗漏指令'
  }
  // 死信
  if (t === 'sub_task_dead_letter') {
    return `熔断器打开（累计失败 ${p.failureCount || attempt} 次）`
  }
  // 人工介入
  if (t === 'sub_task_dead_letter_manual_assign') {
    return `人工介入：${p.reason || '重新指派'}`
  }
  // 耗时提示
  if (t === 'sub_task_llm_call_end' && prev && prev.eventType === 'sub_task_llm_call_start') {
    const dt = new Date(cur.createTime).getTime() - new Date(prev.createTime).getTime()
    if (dt >= 1000) return `LLM 耗时 ${(dt / 1000).toFixed(1)}s`
  }
  return undefined
}

// ── 箭头方向推断：相邻事件对 → { from, to, kind, label, note } ──
//   主动行为（发起）：sync 实线 "->>"
//   响应行为（返回）：response 虚线 "-->>"
function inferMessage(prev: TaskTimelineItem | undefined, cur: TaskTimelineItem, attempt: number, _agentDisplay: (id: string) => string): SequenceMessage {
  const from = prev ? classifySwimlane(prev) : classifySwimlane(cur)
  const to = classifySwimlane(cur)
  const t = cur.eventType
  const label = shortLabel(cur)
  const note = inferNote(prev, cur, attempt)
  // 自循环：调度引擎的自动重试决策（同一泳道内连续重试事件对）
  if (prev && from === to && (t === 'sub_task_auto_execute_dispatch_ok' || t === 'sub_task_execution_command_created')) {
    return { from, to, kind: 'loop', label, note }
  }
  // 响应类（虚线）：success / failed / returned / end / rejected / unparseable / blocked / discarded
  const isResponse = (
    t.includes('success') || t.includes('failed') || t.includes('failed') ||
    t === 'sub_task_llm_call_end' || t === 'sub_task_execution_command_consume' ||
    t === 'sub_task_execute_result_discarded' || t.includes('rejected') ||
    t.includes('blocked') || t.includes('unparseable') ||
    t === 'sub_task_auto_review_passed' || t === 'sub_task_auto_review_rejected' ||
    t === 'sub_task_auto_execute_dispatch_ok'
  )
  if (isResponse && prev) {
    return { from: to, to: from, kind: 'response', label, note }
  }
  // 主动类（实线）
  return { from, to, kind: 'sync', label, note }
}

// ── 失败/重试 attempt 计数（用于 Note 显示「第 N 次失败」）──
function computeAttempts(events: TaskTimelineItem[]): Map<string, number> {
  const counts = new Map<string, number>()
  let failN = 0
  let retryN = 0
  for (const e of events) {
    const t = e.eventType
    if (t.includes('failed') || t.includes('rejected') || t === 'sub_task_dead_letter' || t.includes('blocked')) {
      failN++
      counts.set(String(e.id), failN)
    } else if (t === 'sub_task_execution_command_created') {
      retryN++
      counts.set(String(e.id), retryN)
    } else {
      counts.set(String(e.id), 0)
    }
  }
  return counts
}

// ── 把同泳道内连续 N 次「失败 → 重派」合并为 loop 块 ──
interface SequenceBlock {
  kind: 'message' | 'note' | 'loop'
  msg?: SequenceMessage
  noteText?: string
  /** mermaid Note over <参与者>：用 Swimlane 标识放在哪个泳道旁边 */
  notePos?: Swimlane | null
  loopTitle?: string
  loopMessages?: SequenceMessage[]
}

function buildBlocks(events: TaskTimelineItem[], attempts: Map<string, number>, agentDisplay: (id: string) => string): SequenceBlock[] {
  const blocks: SequenceBlock[] = []
  let i = 0
  while (i < events.length) {
    const cur = events[i]
    const curLane = classifySwimlane(cur)
    // 检测连续多次「失败/熔断 → 重新生成指令」是否值得折叠为 loop
    if (cur.eventType === 'sub_task_dead_letter') {
      // 死信前置：合并自上一次 dead_letter 之后的所有失败/重派事件
      const loopMsgs: SequenceMessage[] = []
      const prev = i > 0 ? events[i - 1] : undefined
      const m = inferMessage(prev, cur, attempts.get(String(cur.id)) || 0, agentDisplay)
      loopMsgs.push(m)
      i++
      while (i < events.length && classifySwimlane(events[i]) === curLane) {
        loopMsgs.push(inferMessage(events[i - 1], events[i], attempts.get(String(events[i].id)) || 0, agentDisplay))
        i++
      }
      blocks.push({ kind: 'loop', loopTitle: '自动重试 N 次', loopMessages: loopMsgs })
      continue
    }
    // 普通消息
    const prev = i > 0 ? events[i - 1] : undefined
    const m = inferMessage(prev, cur, attempts.get(String(cur.id)) || 0, agentDisplay)
    blocks.push({ kind: 'message', msg: m })
    // Note 作为独立 block（mermaid 中 Note 也是单行）
    if (m.note) {
      blocks.push({ kind: 'note', noteText: m.note, notePos: m.to })
    }
    i++
  }
  return blocks
}

// ── 主入口：TaskTimelineItem[] → mermaid syntax 字符串 ──
export interface BuildSequenceOptions {
  /** Agent ID → 注册名解析 */
  resolveAgentName: (agentId: string) => string
}

export function buildMermaidSequence(
  events: TaskTimelineItem[],
  opts: BuildSequenceOptions
): string {
  if (!events.length) return ''
  const lines: string[] = ['sequenceDiagram', '    autonumber']
  // 1. participant 声明
  for (const sw of SWIMLANE_ORDER) {
    lines.push(`    participant ${sw} as ${SWIMLANE_LABEL[sw]}`)
  }
  // 2. 事件排序 + attempt 计数
  const sorted = [...events].sort((a, b) => new Date(a.createTime).getTime() - new Date(b.createTime).getTime())
  const attempts = computeAttempts(sorted)
  const blocks = buildBlocks(sorted, attempts, opts.resolveAgentName)
  // 3. blocks → mermaid 语法
  for (const blk of blocks) {
    if (blk.kind === 'message' && blk.msg) {
      const m = blk.msg
      const safeLabel = m.label.replace(/"/g, '\\"')
      const prefix = m.from === m.to ? `${m.from}` : m.from
      const arrow = m.kind === 'response' ? '-->>' : '->>'
      lines.push(`    ${prefix}${arrow}${m.to}: ${safeLabel}`)
    } else if (blk.kind === 'note' && blk.noteText) {
      // 默认 note 落在 调度引擎 / 执行 Agent 之间（最常见故障回溯场景）
      const over = blk.notePos ? blk.notePos : 'SCH'
      const safe = blk.noteText.replace(/"/g, '\\"').replace(/\n/g, '<br/>')
      lines.push(`    Note over ${over}: ${safe}`)
    } else if (blk.kind === 'loop' && blk.loopMessages) {
      lines.push(`    loop 自动重试`)
      for (const m of blk.loopMessages) {
        const safeLabel = m.label.replace(/"/g, '\\"')
        const prefix = m.from === m.to ? m.from : m.from
        const arrow = m.kind === 'response' ? '-->>' : '->>'
        lines.push(`        ${prefix}${arrow}${m.to}: ${safeLabel}`)
      }
      lines.push(`    end`)
    }
  }
  return lines.join('\n')
}