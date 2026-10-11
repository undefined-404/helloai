// ============================================================
// Agent 事件元数据（REF-6.8：事件码服务端单一来源）
//
// 代码 → label/desc 字典不再本地硬编码，改经 /api/events/catalog 拉取（与技能目录 REF-1.2c
// 同一范式）。事件类型由后端共有，前端只保留展示函数（eventLabel/eventCategory/eventTypeColor）
// 与行为配置（分类徽标、语义色、payload 结构化解构）；label 未命中或目录未加载时回退原始码。
//   - views/subtask/SubTaskDetail.vue（M4.5 执行时间线卡片）
//   - views/event/EventStreamWorkbench.vue（Replay 轨迹 / Audit 表格）
// ============================================================
import { useEventCatalogStore } from '@/stores/eventCatalog'

// 人话化：事件类型 → 简短标签（懒拉起目录；未命中/未加载回退原始类型名，fail-open）
export function eventLabel(eventType: string): string {
  const store = useEventCatalogStore()
  store.ensureLoaded() // fire-and-forget：首帧可能未就绪，回退原始码，就绪后重渲染生效
  return store.labelOf(eventType, eventType)
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
