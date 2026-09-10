// ============================================================
// Agent 事件元数据共享字典（G-006 事件流工作台 + SubTaskDetail 时间线共用）
//
// 单一事实源：EVENT_META 事件类型 → { label, desc } 人话化映射。
// 新增事件类型只改此处，两个消费面同步生效：
//   - views/subtask/SubTaskDetail.vue（M4.5 执行时间线卡片）
//   - views/event/EventStreamWorkbench.vue（Replay 轨迹 / Audit 表格）
// ============================================================
export const EVENT_META: Record<string, { label: string; desc: string }> = {
  // 分发 / 派单
  sub_task_dispatch_prepare: { label: '准备分发', desc: '系统开始为该子任务寻找合适的执行 Agent' },
  sub_task_auto_execute_dispatch: { label: '自动派单', desc: '系统自动把子任务分派给执行 Agent' },
  sub_task_auto_execute_dispatch_enter: { label: '进入派单', desc: '系统进入自动派单流程' },
  sub_task_auto_execute_dispatch_ok: { label: '派单成功', desc: '已成功把子任务交给执行 Agent' },
  sub_task_auto_execute_dispatch_fail: { label: '派单失败', desc: '暂时没有空闲的执行 Agent，稍后重试' },
  sub_task_execution_command_created: { label: '生成执行指令', desc: '系统已生成执行指令，等待 Agent 领取' },
  sub_task_execution_command_consume: { label: '领取指令', desc: '执行 Agent 已领取指令，准备开始' },
  sub_task_execution_command_consume_skipped: { label: '跳过指令', desc: '该执行指令被跳过（可能已被处理）' },
  sub_task_execution_command_poll_recovery: { label: '指令恢复', desc: '系统巡检恢复了一条遗漏的执行指令' },
  // 超时改派（2026-09-01：关键调度节点可观测——超时未领取 / 执行超时）
  sub_task_unclaimed_timeout_reassign: { label: '超时未领取改派', desc: '分配后 Agent 超时未领取，系统自动改派其他 Agent' },
  sub_task_execution_timeout_reassign: { label: '执行超时改派', desc: '执行超过时限，系统判定超时并转入失败回退链重新分发' },
  sub_task_offline_reassign: { label: '离线改派', desc: '分配后 Agent 心跳丢失（离线），系统自动改派其他 Agent' },
  // 执行
  sub_task_execute_enter: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_start: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_before_platform: { label: '执行前准备', desc: '执行前的平台准备工作' },
  sub_task_deps_context_loaded: { label: '参考上游产出', desc: '执行 Agent 已读取前置子任务的交付结果，作为本次执行的参考' },
  sub_task_spec_context_loaded: { label: '装配任务上下文', desc: '执行 Agent 已装配任务全局上下文与直接前置产出，作为本次执行的参考' },
  sub_task_llm_call_start: { label: '调用大模型', desc: '执行 Agent 开始请求大模型生成内容' },
  sub_task_llm_call_end: { label: '大模型返回', desc: '大模型已返回生成结果' },
  sub_task_llm_call_failed: { label: '大模型失败', desc: '调用大模型失败（超时或网络异常）' },
  sub_task_execute_thinking: { label: '思考过程', desc: '执行 Agent 的思考 / 推理过程' },
  sub_task_execute: { label: '执行产出', desc: '执行 Agent 产出了内容' },
  sub_task_execute_submit: { label: '提交产出', desc: '执行 Agent 提交了本次产出' },
  sub_task_artifact_materialized: { label: '产出物化', desc: '执行产出已物化为附件，可在产出附件中下载' },
  sub_task_execute_success: { label: '执行成功', desc: '子任务执行成功' },
  sub_task_execute_failed: { label: '执行失败', desc: '子任务执行失败' },
  sub_task_execute_result_discarded: { label: '结果丢弃', desc: '本次执行结果被丢弃（可能已过期）' },
  sub_task_report_blocked: { label: '执行受阻', desc: '子任务被标记为阻塞，需要人工介入' },
  // 核验
  sub_task_auto_review_passed: { label: '核验通过', desc: '自动核验通过，子任务达标' },
  sub_task_auto_review_rejected: { label: '核验驳回', desc: '自动核验未通过，需要返工' },
  sub_task_auto_review_unparseable: { label: '核验异常', desc: '核验结果无法解析' },
  sub_task_auto_review_skip_max_rework: { label: '跳过核验', desc: '已达最大返工次数，跳过核验' },
  subtask_review_prompt: { label: '核验请求', desc: '发起对产出的核验' },
  subtask_review_verdict: { label: '核验结论', desc: '核验给出的结论' },
  subtask_review_thinking: { label: '核验思考', desc: '核验的分析过程' },
  // V58: 双审 / 抽检（链路来源区分，timeline 事件 + 对话流消息类型共用字典）
  sub_task_dual_review_consented: { label: '双审共识', desc: '两位评审结论一致，按共识策略落地' },
  sub_task_dual_review_incomplete: { label: '双审缺失', desc: '双审核验不完整，子任务停留 REVIEW 等人工' },
  sub_task_dual_review_degraded: { label: '双审降级', desc: '双审候选不足，降级为单审' },
  sub_task_reviewer_disagreement: { label: '双审分歧', desc: '两位评审结论不一致，转人工介入' },
  sub_task_recheck_consistent: { label: '抽检一致', desc: '抽检复审与原判定一致' },
  sub_task_recheck_discrepancy: { label: '抽检分歧', desc: '抽检复审与原判定不一致，仅度量不改状态' },
  subtask_dual_review_prompt: { label: '双审请求', desc: '双审发起对产出的核验' },
  subtask_dual_review_verdict: { label: '双审结论', desc: '双审给出的核验结论' },
  subtask_dual_review_thinking: { label: '双审思考', desc: '双审的分析过程' },
  subtask_dual_review_result: { label: '双审共识', desc: '双审按共识策略落定的结论' },
  subtask_recheck_prompt: { label: '抽检请求', desc: '抽检复审发起的核验' },
  subtask_recheck_verdict: { label: '抽检审查', desc: '抽检复审给出的核验结论' },
  subtask_recheck_thinking: { label: '抽检思考', desc: '抽检复审的分析过程' },
  subtask_recheck_result: { label: '抽检结论', desc: '抽检复审结论（只度量不改状态）' },
  // 死信 / 重派
  sub_task_dead_letter: { label: '进入死信', desc: '多次失败，子任务进入死信池' },
  sub_task_dead_letter_manual_assign: { label: '死信重派', desc: '人工把死信子任务重新指派给 Agent' },
  // 核验熔断 / 人工介入（2026-08-19：与调度死信对称，OPS/DLQ 泳道可回溯）
  sub_task_review_dead_letter: { label: '核验熔断', desc: '核验返工超过上限，子任务进入死信池，等待人工决定通过/改派' },
  sub_task_manual_intervention_required: { label: '人工介入', desc: '系统判定该子任务需要人工处置（改派/人工通过/人工驳回）' },
  sub_task_manual_review_passed: { label: '人工验收通过', desc: '管理员人工审定通过，子任务完成（LOG-20260903-005 新增，与自动核验对称）' },
  sub_task_manual_review_rejected: { label: '人工驳回', desc: '管理员人工驳回返工/改派（LOG-20260903-005 新增，与自动核验对称）' },
  sub_task_manual_rework_reset: { label: '人工改派', desc: '人工驳回并改派执行者，同时重置返工计数' },
  // 任务级
  task_plan_generated: { label: '生成拆解', desc: '已生成任务拆解草案' },
  task_plan_confirmed: { label: '确认拆解', desc: '拆解草案已确认' },
  task_plan_rejected: { label: '驳回拆解', desc: '拆解草案被驳回' },
  task_plan_failed: { label: '拆解失败', desc: '任务拆解失败' },
  task_plan_llm_call_start: { label: '拆解调模型', desc: '开始请求大模型进行任务拆解' },
  task_auto_completed: { label: '任务完成', desc: '所有子任务完成，主任务自动收尾' },
  // A6 并轨：agent_event 执行轨迹（Run / Turn / Step 细粒度事件）
  run_created: { label: 'Run 创建', desc: '一次需求完整执行启动' },
  run_completed: { label: 'Run 完成', desc: '一次需求完整执行完成' },
  task_created: { label: '任务创建', desc: '主任务已创建' },
  task_assigned: { label: '任务分配', desc: '子任务已分配给执行 Agent' },
  agent_started: { label: 'Agent 开始', desc: '执行 Agent 开始处理子任务' },
  skill_resolved: { label: '技能解析', desc: '已解析执行所需技能' },
  tool_resolved: { label: '工具解析', desc: '已解析启用的工具清单' },
  environment_resolved: { label: '环境解析', desc: '已确定执行环境' },
  context_built: { label: '上下文装配', desc: '执行上下文已装配' },
  tool_call_started: { label: '工具调用', desc: 'Agent 开始调用工具' },
  tool_call_completed: { label: '工具返回', desc: '工具调用已返回结果' },
  agent_completed: { label: 'Agent 完成', desc: '执行 Agent 完成本轮处理并提交' },
  review_started: { label: '核验开始', desc: '系统开始核验产出' },
  review_rejected: { label: '核验驳回', desc: '核验未通过，需要返工' },
  rework_started: { label: '返工开始', desc: '子任务进入返工流程' },
  review_approved: { label: '核验通过', desc: '核验通过，子任务完成' }
}

// 人话化：事件类型 → 简短标签（未命中回退原始类型名）
export function eventLabel(eventType: string): string {
  return EVENT_META[eventType]?.label || eventType
}

// 语义分类（事件卡顶部类型徽标），供 el-tag 展示简短分类词
export type EventCategory = '分发' | '执行' | '核验' | '人工介入' | '任务' | '流程'
export function eventCategory(eventType: string): EventCategory {
  if (/review|recheck/.test(eventType)) return '核验'
  if (/dead_letter|manual|blocked|intervention|rework/.test(eventType)) return '人工介入'
  if (/dispatch|command|assigned|timeout_reassign|offline_reassign/.test(eventType)) return '分发'
  if (/^task_|^run_|task_auto/.test(eventType)) return '任务'
  if (/execute|llm|artifact|context_loaded|thinking|report|skill_resolved|tool_resolved|environment_resolved|context_built|tool_call|agent_/.test(eventType)) return '执行'
  return '流程'
}

// ── payload 结构化解构（G-006 增强：把关键字段从 JSON 原文中解锁为可读行） ──
// 高频 payload 键 → 中文标签；未命中键回退原始键名
const PAYLOAD_KEY_LABEL: Record<string, string> = {
  submitterAgentId: '提交人',
  reviewerAgentId: '核验人',
  reworkAgentId: '改派人',
  executorAgentId: '执行人',
  previousAgentId: '原执行者',
  preferredAgentId: '目标执行者',
  assigneeAgentId: '负责人',
  agentId: 'Agent',
  score: '评分',
  round: '轮次',
  comment: '评语',
  issues: '问题',
  reason: '原因',
  error: '错误',
  executor: '执行方式',
  source: '来源',
  success: '是否成功',
  finishReason: '结束原因',
  tokens: 'Token 用量',
  channel: '核验通道',
  toolName: '工具',
  skill: '技能',
  attempt: '尝试次数',
  reassignAttemptCount: '改派次数',
  attachmentCount: '附件数',
  outputPresent: '有产出文本',
  turn: '环节',
  step: '步骤',
  taskId: '任务',
  subTaskId: '子任务'
}

// 明确无疑的枚举值翻译（其余值原样展示，避免失真）
const PAYLOAD_VALUE_LABEL: Record<string, string> = {
  SINGLE: '单审',
  DUAL: '双审',
  cli_client: 'CLI 客户端',
  EXTERNAL: '外部 Agent'
}

export interface PayloadField {
  key: string
  label: string
  value: string
  /** agent=按 Agent ID 解析名称；score=评分；bool=是/否；text=长文本块；plain=直接展示 */
  kind: 'agent' | 'score' | 'bool' | 'text' | 'plain'
}

const AGENT_KEY_RE = /agentid$/i
const TEXT_KEYS = new Set(['comment', 'issues', 'reason', 'error', 'output', 'message', 'description'])

/**
 * payload 结构化解构：把可读标量字段拆成 label/value 行（agent ID 交由调用方解析名称），
 * 对象/数组与超长内容不拆、留在原文折叠中（审计原文始终可达）。
 */
export function payloadFields(payload: Record<string, any> | null | undefined): PayloadField[] {
  if (!payload) return []
  const fields: PayloadField[] = []
  for (const [key, raw] of Object.entries(payload)) {
    if (raw === null || raw === undefined || raw === '') continue
    const label = PAYLOAD_KEY_LABEL[key] || key
    if (typeof raw === 'boolean') {
      fields.push({ key, label, value: raw ? '是' : '否', kind: 'bool' })
    } else if (typeof raw === 'number') {
      if (AGENT_KEY_RE.test(key)) fields.push({ key, label, value: String(raw), kind: 'agent' })
      else if (key === 'score') fields.push({ key, label, value: `${raw}/5`, kind: 'score' })
      else fields.push({ key, label, value: String(raw), kind: 'plain' })
    } else if (typeof raw === 'string') {
      if (AGENT_KEY_RE.test(key) && /^\d+$/.test(raw)) {
        fields.push({ key, label, value: raw, kind: 'agent' })
      } else if (key === 'score' && /^\d+$/.test(raw)) {
        fields.push({ key, label, value: `${raw}/5`, kind: 'score' })
      } else if (TEXT_KEYS.has(key) || raw.length > 80) {
        fields.push({ key, label, value: raw, kind: 'text' })
      } else {
        fields.push({ key, label, value: PAYLOAD_VALUE_LABEL[raw] || raw, kind: 'plain' })
      }
    }
    // 其余（对象/数组）留在 payload 原文折叠中
  }
  return fields
}

/**
 * payload 是否含未解构内容（对象/数组）。
 * 仅此类 payload 的"完整原文"有增量价值——纯标量 payload 已被 payloadFields 全部解锁为可读行。
 */
export function payloadHasNested(payload: Record<string, any> | null | undefined): boolean {
  if (!payload) return false
  return Object.values(payload).some((v) => v !== null && typeof v === 'object')
}

// 语义色（el-tag / el-timeline 节点 / 卡片 tone-* 底色共用）
export type EventTone = '' | 'success' | 'warning' | 'danger' | 'info' | 'primary'
export function eventTypeColor(eventType: string): EventTone {
  if (!eventType) return 'info'
  // 异常优先判定（避免 auto_review_rejected 含 review 被误判为 success）
  if (eventType.includes('failed') || eventType.includes('rejected') || eventType.includes('blocked') || eventType.includes('dead_letter')) return 'danger'
  if (eventType.includes('paused') || eventType.includes('warning') || eventType.includes('unparseable') || eventType.includes('degraded') || eventType.includes('disagreement') || eventType.includes('discrepancy') || eventType.includes('incomplete') || eventType.includes('timeout') || eventType.includes('offline_reassign')) return 'warning'
  if (eventType.includes('completed') || eventType.includes('submitted') || eventType.includes('passed') || eventType.includes('ok') || eventType.includes('materialized') || eventType.includes('consistent') || eventType.includes('consented')) return 'success'
  if (eventType.includes('assigned') || eventType.includes('created') || eventType.includes('dispatch') || eventType.includes('command')) return 'primary'
  return 'info'
}