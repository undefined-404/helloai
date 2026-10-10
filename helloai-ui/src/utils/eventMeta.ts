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
  sub_task_no_candidate: { label: '暂无可用执行者', desc: '当前没有空闲或可用的候选执行 Agent，系统将自动重试派发' },
  sub_task_claim_rejected: { label: '认领被拒', desc: '该 Agent 不在任务执行者白名单内或缺少必需技能，认领未通过' },
  sub_task_dispatch_fallback: { label: '熔断降级换人', desc: '原执行 Agent 不可用（熔断 / 离线），系统已把该子任务改派给替代 Agent 执行' },
  sub_task_dispatch_no_alternative: { label: '无可用替代执行者', desc: '原执行 Agent 不可用且当前无满足任务约束的候选执行者，改派未发生，需稍后重试或人工介入' },
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
  attachment_deleted: { label: '删除产出', desc: '执行 Agent 删除了此前上传的产出附件（止损即停、零残留）' },
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
  // 核验跳过 / 终态分支（2026-10-05）：timeline 事件 + 对话流消息类型（前端两边共用字典）
  subtask_review_skip_max_rework: { label: '跳过核验·返工超限', desc: '返工次数达上限，跳过自动核验并转入死信等待人工' },
  subtask_review_skip_no_capability: { label: '跳过核验·无本机能力', desc: '执行密集任务由无本机执行能力的 Agent 提交，跳过自动核验转人工' },
  subtask_review_skip_no_evidence: { label: '跳过核验·无产出证据', desc: '无产出本体（正文与附件皆空），跳过自动核验转人工' },
  subtask_review_skip_disagreement: { label: '跳过核验·双审分歧', desc: '双审结论不一致，停留 REVIEW 等人工裁决' },
  subtask_review_skip_repeated_failure: { label: '跳过核验·重复失败', desc: '判定为结构化重复失败，熔断转死信等人工' },
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
  review_approved: { label: '核验通过', desc: '核验通过，子任务完成' },

  // ── 审计 / 内部事件补齐（2026-10-11，REF-6.8）─────────────────────────────
  // 这些码**后端早已落库**，但本字典未登记 ⇒ `eventLabel()` 回退原始码，时间线显示裸英文
  // （42 个里 0 个在 `COMPACT_HIDDEN_EVENTS` 内，即默认视图就能看到）。
  // 由 `scripts/shell/verify-event-key-parity.sh` 守卫（后端写入的事件码 ↔ 本字典 ↔ sequenceFlow.LABEL，缺一即红）。
  // Agent 生命周期（系统级：taskId / subTaskId 均为空）
  agent_sleep: { label: 'Agent 休眠', desc: 'Agent 进入休眠，不再接收派单（次日打卡后恢复在岗）' },
  agent_wake: { label: 'Agent 唤醒', desc: 'Agent 从休眠被唤醒（转离线，待再次打卡恢复在岗）' },
  // 需求澄清
  requirement_description_section_missing: { label: '需求小节缺失', desc: '澄清会话的需求描述缺少必需小节（会话级事件，payload 带 conversationId）' },
  // 子任务：派发 / 降级 / 回收 / 核验跳过
  sub_task_dispatch_deferred: { label: '派发推迟', desc: '下游节点分发失败，保持 PENDING 等待兜底重派' },
  sub_task_dispatch_skip_dependency: { label: '跳过派发（依赖未就绪）', desc: '前置依赖未就绪，本轮不派发' },
  sub_task_dispatch_skip_no_capability: { label: '跳过派发（无本机能力）', desc: '候选 Agent 无本机执行能力，不派发' },
  sub_task_fallback_skip_policy: { label: '放弃降级（策略）', desc: '按任务约束策略不执行熔断降级' },
  sub_task_fallback_skip_need_human: { label: '放弃降级（需人工）', desc: '熔断降级的替代 Agent 也无本机执行能力，标记人工介入' },
  sub_task_redispatch_skipped: { label: '跳过重派', desc: '闸门判定不满足重派条件，本轮跳过（不空烧重派预算）' },
  sub_task_lease_reclaimed: { label: '租约回收', desc: '在飞租约过期被回收（仅审计用途，不阻断回收主链路）' },
  sub_task_report_blocked_skipped: { label: '跳过阻塞上报', desc: '重复或无效的阻塞上报被忽略' },
  sub_task_session_interrupted: { label: '会话中断', desc: '执行会话被中断（payload 保留上下文摘要）' },
  sub_task_contract_backfilled: { label: '契约内容回填', desc: '完成时回填子任务契约内容（原 content 为空，从产出补齐）' },
  sub_task_review_skip_no_capability: { label: '核验跳过（无本机能力）', desc: '执行密集任务由无本机能力 Agent 提交，自动核验跳过' },
  sub_task_review_skip_no_evidence: { label: '核验跳过（无证据）', desc: '无产出证据支撑，自动核验跳过' },
  sub_task_auto_review_skip_repeated_failure: { label: '核验重复失败短路', desc: '自动核验连续失败且判定为结构性失败，短路转死信不再耗轮次' },
  // 任务级：拆解（Planner）
  task_plan_async_submitted: { label: '拆解已异步提交', desc: '拆解已提交异步执行，HTTP 线程返回，进度查草案' },
  task_plan_llm_call_end: { label: '拆解大模型返回', desc: '拆解调用大模型结束（payload 含耗时与 token 用量）' },
  task_plan_timeout_recovered: { label: '拆解超时恢复', desc: '拆解超时由定时任务恢复（重置为可重试）' },
  task_plan_draft_field_missing: { label: '拆解草案缺字段', desc: '草案子项缺必需字段（审计先于抛出，可回溯字段与原始输出）' },
  task_plan_constraints_missing: { label: '拆解约束缺失', desc: 'COARSE 粒度子项缺 constraints（记审计，不阻断落库）' },
  task_plan_skill_filtered: { label: '拆解技能被过滤', desc: '草案中的技能不在已知目录，过滤后不落库' },
  task_plan_uncertainty_degraded: { label: '不确定性降级', desc: '不确定性条目的类型不在白名单内，降级处理' },
  task_plan_uncertainty_missing: { label: '不确定性缺失', desc: '草案未申报待确认的不确定性（提示可能漏报）' },
  // 任务级：整合报告生成 + 审查链
  task_final_report_llm_call_start: { label: '报告调用大模型', desc: '开始调用大模型生成报告章节' },
  task_final_report_generated: { label: '报告已生成', desc: '整合报告生成完成并写回' },
  task_final_report_failed: { label: '报告失败', desc: '整合报告生成失败（分章降级后仍失败）' },
  task_final_report_outline_ready: { label: '报告大纲就绪', desc: '大纲生成完成，进入分章写作' },
  task_final_report_outline_order_suspect: { label: '报告大纲顺序可疑', desc: '大纲章节顺序异常，疑似顺序错乱' },
  task_final_report_outline_failed: { label: '报告大纲失败', desc: '报告大纲生成或解析失败' },
  task_final_report_review_discarded_stale: { label: '报告审查丢弃（陈旧）', desc: '审查前发现已被重新生成 / 回滚接管，旧链审查丢弃' },
  task_final_report_review_unparseable: { label: '报告审查不可解析', desc: '审查结论无法解析，按证据不足处理' },
  task_final_report_review_warned: { label: '报告审查告警', desc: '机械软违规（跨章逐字重复 / 覆盖追溯表缺失），交审查重点关注' },
  task_final_report_review_passed: { label: '报告审查通过', desc: '报告审查通过' },
  task_final_report_review_rejected: { label: '报告审查驳回', desc: '报告审查驳回，需返工' },
  task_final_report_review_failed: { label: '报告审查失败', desc: '报告审查调用失败' },
  task_final_report_review_skipped: { label: '报告审查跳过', desc: '报告审查跳过并收敛为完成（记录原因）' },
  task_final_report_review_orphan_converged: { label: '报告审查孤儿收敛', desc: '孤儿巡检把长时间停留审查中的报告收敛为完成' },
  task_final_report_max_review_reached: { label: '报告审查达上限', desc: '报告审查轮次达上限，停止再审' },
  task_final_report_rework_discarded_stale: { label: '报告返工丢弃（陈旧）', desc: '返工基于旧链已无意义，丢弃不重写不收敛' },
  task_final_report_rolled_back: { label: '报告已回滚', desc: '报告回滚到上一版（当前版 ↔ 上一版互换）' },
  task_iteration_backfill_failed: { label: '轮次回填失败', desc: '任务轮次回填失败（不影响报告生成）' },
  // 非「字面量直写」形态的码：**静态提取扫不到**，靠 `verify-event-key-parity.sh --db`（库内 distinct event_type）发现
  agent_offline: { label: 'Agent 离线', desc: 'Agent 判为离线（历史事件；payload 带离线原因与触发上下文）' },
  sub_task_executor_done_issues: { label: '执行完成问题回填', desc: '执行者完成时上报的问题回填到子任务（payload.state 区分成功 / 跳过 / 失败）' },
  task_created_from_clarify: { label: '澄清完成建单', desc: '澄清会话定稿后创建任务' },
  agent_external_fallback_triggered: { label: '外部执行兜底触发', desc: '外部 Agent 无在跑子任务时触发阈值回退兜底（仅写冷却标记）' }
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
  if (/dispatch|command|assigned|timeout_reassign|offline_reassign|no_candidate|claim_rejected/.test(eventType)) return '分发'
  if (/^task_|^run_|task_auto/.test(eventType)) return '任务'
  if (/execute|llm|artifact|attachment|context_loaded|thinking|report|skill_resolved|tool_resolved|environment_resolved|context_built|tool_call|agent_/.test(eventType)) return '执行'
  return '流程'
}

// 分类 → 语义色（G-006 事件流工作台时间线节点 / 事件卡左侧色条共用）
// 与 el-tag / stat-tile-icon / EP timeline dot 同色族，亮暗双主题自动跟随。
export function eventCategoryColor(cat: EventCategory): EventTone {
  switch (cat) {
    case '分发': return 'primary'
    case '执行': return 'info'
    case '核验': return 'success'
    case '任务': return 'primary'
    case '人工介入': return 'warning'
    case '流程': return 'info'
  }
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
  excludedAgentId: '排除执行者',
  role: '角色',
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
  if (eventType.includes('assigned') || eventType.includes('created') || eventType.includes('dispatch') || eventType.includes('command') || eventType.includes('no_candidate')) return 'primary'
  return 'info'
}