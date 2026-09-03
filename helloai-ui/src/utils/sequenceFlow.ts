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
//    - dead_letter / review_dead_letter（无 manual）→ DLQ
//    - dead_letter_manual_assign / manual_intervention_required /
//      manual_rework_reset / trigger=manual          → OPS
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
  // 人工介入（死信重派 / 手动触发 / 人工介入标记 / 人工驳回改派）→ 人工运维台
  if (t === 'sub_task_dead_letter_manual_assign'
      || t === 'sub_task_manual_intervention_required'
      || t === 'sub_task_manual_review_passed'
      || t === 'sub_task_manual_review_rejected'
      || t === 'sub_task_manual_rework_reset') return 'OPS'
  if (trigger === 'manual' || trigger === 'dead_letter_redispatch') return 'OPS'
  // 死信（系统判定：调度重分配熔断 / 核验返工熔断）→ 死信队列
  if (t === 'sub_task_dead_letter' || t === 'sub_task_review_dead_letter') return 'DLQ'
  // 核验 → 核验 Agent
  if (t.startsWith('subtask_review_') || t.startsWith('sub_task_auto_review_')) return 'RVW'
  // 超时/离线改派（2026-09-01）：调度引擎的关键决策节点，归 SCH 泳道；
  // execution_timeout 以 sub_task_execute_ 开头，不显式拦截会被下方规则误归 EXT
  if (t === 'sub_task_unclaimed_timeout_reassign' || t === 'sub_task_execution_timeout_reassign'
      || t === 'sub_task_offline_reassign') return 'SCH'
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
  sub_task_unclaimed_timeout_reassign: '超时未领取改派',
  sub_task_execution_timeout_reassign: '执行超时改派',
  sub_task_offline_reassign: '离线改派',
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

  // V58: 双审 / 抽检链路节点（与单审核验泳道区分）
  sub_task_dual_review_consented: '双审共识落地',
  sub_task_dual_review_incomplete: '双审缺失等人工',
  sub_task_dual_review_degraded: '双审降级单审',
  sub_task_reviewer_disagreement: '双审分歧转人工',
  sub_task_recheck_consistent: '抽检一致',
  sub_task_recheck_discrepancy: '抽检分歧',
  subtask_dual_review_prompt: '发起双审核验',
  subtask_dual_review_verdict: '双审结论',
  subtask_dual_review_thinking: '双审思考',
  subtask_dual_review_result: '双审共识落定',
  subtask_recheck_prompt: '发起抽检复审',
  subtask_recheck_verdict: '抽检审查',
  subtask_recheck_thinking: '抽检思考',
  subtask_recheck_result: '抽检结论落定',

  sub_task_dead_letter: '进入死信',
  sub_task_review_dead_letter: '核验熔断入死信',
  sub_task_dead_letter_manual_assign: '人工指派',
  sub_task_manual_intervention_required: '人工介入待处理',
  sub_task_manual_review_passed: '人工验收通过',
  sub_task_manual_review_rejected: '人工驳回',
  sub_task_manual_rework_reset: '人工驳回改派'
}

// ── 人工介入原因映射（markManualIntervention 的 reason 值 → 人话）──
const INTERVENTION_REASON: Record<string, string> = {
  rework_limit: '核验返工达上限',
  review_skip_execution_dense_no_capability: '提交者无本机执行能力',
  review_skip_no_evidence: '交付物无物化证据',
  fallback_skip_policy: '任务级策略禁止自动回退',
  fallback_skip_policy_restricted: '回退目标不在白名单',
  fallback_skip_execution_dense: '执行密集不可回退无能力Agent',
  dispatch_skip_execution_dense: '执行密集不可分配无能力Agent'
}

function interventionReason(reason: unknown): string {
  const r = String(reason || '')
  return INTERVENTION_REASON[r] || r || '需人工处置'
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
  // 重派（巡检恢复）
  if (t === 'sub_task_execution_command_poll_recovery') {
    return '巡检恢复遗漏指令'
  }
  // 超时改派（2026-09-01）：调度决策留痕，Note 标注改派原因便于回溯
  if (t === 'sub_task_unclaimed_timeout_reassign') {
    return '分配后超时未领取，自动改派'
  }
  if (t === 'sub_task_execution_timeout_reassign') {
    const mins = p.timeoutMinutes !== undefined ? String(p.timeoutMinutes) : '限'
    return `执行超过 ${mins} 分钟，判定超时改派`
  }
  if (t === 'sub_task_offline_reassign') {
    return 'Agent 离线（心跳丢失），自动改派'
  }
  // 死信（调度维度）
  if (t === 'sub_task_dead_letter') {
    return `熔断器打开（累计失败 ${p.failureCount || attempt} 次）`
  }
  // 死信（核验维度，2026-08-19 新增）：带返工计数，DLQ 泳道可回溯熔断原因
  if (t === 'sub_task_review_dead_letter') {
    const rc = p.reworkCount !== undefined ? String(p.reworkCount) : String(attempt)
    const max = p.maxRework !== undefined ? String(p.maxRework) : '上限'
    return `核验熔断（返工 ${rc}/${max} 次，等待人工打捞）`
  }
  // 人工介入标记（OPS 泳道）：reason 走映射人话化
  if (t === 'sub_task_manual_intervention_required') {
    return `人工介入：${interventionReason(p.reason)}`
  }
  // 人工驳回改派（OPS 泳道）：提示改派目标与原执行者重做语义
  if (t === 'sub_task_manual_rework_reset') {
    return `改派执行者并重置返工计数`
  }
  // 人工审查结果（OPS 泳道）：验收通过/驳回留痕（LOG-20260903-005）
  if (t === 'sub_task_manual_review_passed') {
    return `人工验收通过（评分 ${p.score ?? '-'}）`
  }
  if (t === 'sub_task_manual_review_rejected') {
    return `人工驳回（评分 ${p.score ?? '-'}）：${String(p.issues || '重新执行').slice(0, 60)}`
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
    // 2026-08-19：核验熔断（sub_task_review_dead_letter）与调度熔断（sub_task_dead_letter）对称折叠
    if (cur.eventType === 'sub_task_dead_letter' || cur.eventType === 'sub_task_review_dead_letter') {
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