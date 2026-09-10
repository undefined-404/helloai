<template>
  <div class="page ha-entrance-up">
    <el-card class="head-card full-row">
      <template #header>
        <div class="card-header">
          <span>事件流工作台</span>
          <span class="head-note">G-006：Agent 事件回放（Replay）与审计（Audit），数据源 agent_event 执行轨迹表（只读）</span>
        </div>
      </template>

      <!-- 级联入口：按任务维度选择（免记 ID）；任务列表 / 子任务详情页深链自动带入并查询 -->
      <div class="query-row">
        <el-select
          v-model="selectedTaskId"
          placeholder="选择任务（支持标题 / ID 过滤）"
          clearable
          filterable
          class="task-select"
          @change="onTaskChange"
        >
          <el-option
            v-for="t in taskOptions"
            :key="t.id"
            :label="taskOptionLabel(t)"
            :value="String(t.id)"
          />
        </el-select>
        <el-button
          link
          type="primary"
          @click="toggleAdvanced"
        >
          {{ advanced ? '收起高级模式' : '高级模式（手工输入 ID）' }}
        </el-button>
      </div>

      <el-tabs v-model="activeTab">
        <!-- Replay：按 Run ID 重建一次需求完整执行的细粒度轨迹 -->
        <el-tab-pane
          label="Replay 轨迹追溯"
          name="replay"
        >
          <div class="query-row">
            <el-select
              v-if="selectedTaskId"
              v-model="selectedSubTaskId"
              placeholder="全部子任务（可选聚焦）"
              clearable
              filterable
              class="subtask-select"
              @change="onSubTaskChange"
            >
              <el-option
                v-for="s in subTaskOptions"
                :key="s.id"
                :label="subTaskOptionLabel(s)"
                :value="String(s.id)"
              />
            </el-select>
            <el-button
              type="primary"
              :loading="replayLoading"
              @click="runReplay"
            >
              追溯
            </el-button>
            <el-input
              v-if="advanced"
              v-model="replayRunId"
              placeholder="手工 Run ID（如 run-2084256044640505858-1）"
              clearable
              style="width: 320px"
              @keyup.enter="runReplay"
            />
            <span
              v-if="replayEvents.length"
              class="query-stats"
            >共 {{ replayEvents.length }} 条事件</span>
          </div>

          <el-empty
            v-if="!replayLoading && !replayEvents.length"
            :description="replayEmptyText"
          />
          <div
            v-if="replaySummary"
            class="summary-strip"
          >
            <span class="query-stats">去重环节 {{ replaySummary.turnCount }} 个</span>
            <span
              v-for="[cat, count] in replaySummary.cats"
              :key="cat"
              class="summary-chip"
            >{{ cat }} ×{{ count }}</span>
            <span
              v-for="s in replaySummary.subTaskSpans"
              :key="'sub-' + s.id"
              class="summary-chip"
              :title="s.tip"
            >{{ s.name }} 耗时 {{ s.text }}</span>
            <span class="query-stats summary-span">{{ replaySummary.spanText }}</span>
          </div>
          <el-timeline
            v-if="replayEvents.length"
            class="tl"
          >
            <el-timeline-item
              v-for="(ev, idx) in replayEvents"
              :key="ev.id"
              :type="eventTypeColor(ev.eventType)"
            >
              <div class="tl-item">
                <div class="tl-top">
                  <el-tag
                    size="small"
                    effect="light"
                    :type="eventTypeColor(ev.eventType)"
                  >
                    {{ eventCategory(ev.eventType) }}
                  </el-tag>
                  <span class="tl-time">
                    {{ fmtTime(ev.createTime) }}
                    <span
                      v-if="gapOf(idx)"
                      class="tl-gap"
                    >{{ gapOf(idx) }}</span>
                  </span>
                </div>
                <div class="tl-title">
                  {{ eventLabel(ev.eventType) }}
                </div>
                <div class="tl-desc">
                  {{ descOf(ev) }}
                  <span
                    v-if="ev.turn != null || ev.step != null"
                    class="tl-pos"
                  >环节 #{{ ev.turn ?? '-' }}/{{ ev.step ?? '-' }}</span>
                </div>
                <!-- 归属与执行方：子任务可点击跳转详情（免手记 ID），Agent 显示注册名 -->
                <div
                  v-if="ev.subTaskId != null || ev.agentId != null"
                  class="tl-meta"
                >
                  <el-button
                    v-if="ev.subTaskId != null"
                    link
                    type="primary"
                    class="tl-link"
                    @click="goSubTask(ev.subTaskId)"
                  >
                    子任务 {{ subTaskRef(ev.subTaskId) }}
                  </el-button>
                  <span v-if="ev.agentId != null">{{ resolveAgentName(ev.agentId) }}</span>
                </div>
                <!-- payload 关键字段结构化解构（纯标量已全覆盖；完整原文仅含数组/嵌套时出现） -->
                <div
                  v-if="payloadFields(ev.payload).length"
                  class="tl-fields"
                >
                  <div
                    v-for="f in payloadFields(ev.payload)"
                    :key="f.key"
                    class="pf-row"
                    :class="{ 'pf-text': f.kind === 'text' }"
                  >
                    <span class="pf-label">{{ f.label }}</span>
                    <span class="pf-value">{{ f.kind === 'agent' ? resolveAgentName(f.value) : f.value }}</span>
                  </div>
                </div>
                <el-collapse
                  v-if="ev.payload && payloadHasNested(ev.payload)"
                  class="payload-collapse"
                >
                  <el-collapse-item
                    title="payload 完整原文"
                    name="p"
                  >
                    <pre class="payload-pre">{{ jsonOf(ev.payload) }}</pre>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <!-- Audit：按 Task ID 分页查执行事实，事件类型可过滤 -->
        <el-tab-pane
          label="Audit 审计查询"
          name="audit"
        >
          <div class="query-row">
            <el-input
              v-if="advanced"
              v-model="auditTaskId"
              placeholder="手工 Task ID（数字）"
              clearable
              style="width: 240px"
              @keyup.enter="runAudit(1)"
            />
            <el-select
              v-model="auditEventType"
              placeholder="事件类型（全部）"
              clearable
              filterable
              style="width: 280px"
            >
              <el-option
                v-for="(meta, type) in EVENT_META"
                :key="type"
                :label="meta.label + '（' + type + '）'"
                :value="type"
              />
            </el-select>
            <el-button
              type="primary"
              :loading="auditLoading"
              @click="runAudit(1)"
            >
              查询
            </el-button>
            <span
              v-if="auditTotal"
              class="query-stats"
            >共 {{ auditTotal }} 条</span>
          </div>

          <el-table
            v-loading="auditLoading"
            :data="auditEvents"
            class="audit-table"
          >
            <el-table-column
              label="事件类型"
              min-width="180"
            >
              <template #default="{ row }">
                <el-tag
                  size="small"
                  effect="light"
                  :type="eventTypeColor(row.eventType)"
                >
                  {{ eventLabel(row.eventType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="eventId"
              label="eventId"
              min-width="150"
              show-overflow-tooltip
            />
            <el-table-column
              label="归属"
              min-width="200"
            >
              <template #default="{ row }">
                <div class="cell-stack">
                  <span>run {{ row.runId }}</span>
                  <span v-if="row.taskId != null">task {{ row.taskId }}</span>
                  <el-button
                    v-if="row.subTaskId != null"
                    link
                    type="primary"
                    class="tl-link"
                    @click="goSubTask(row.subTaskId)"
                  >
                    sub {{ subTaskRef(row.subTaskId) }}
                  </el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              label="执行方"
              min-width="140"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                {{ row.agentId != null ? resolveAgentName(row.agentId) : '-' }}
              </template>
            </el-table-column>
            <el-table-column
              label="时间"
              width="172"
            >
              <template #default="{ row }">
                {{ fmtTime(row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column
              label="payload"
              min-width="220"
            >
              <template #default="{ row }">
                <div
                  v-if="row.payload"
                  class="cell-payload"
                >
                  <span
                    v-if="payloadBrief(row.payload)"
                    class="cell-brief"
                    :title="payloadBrief(row.payload)"
                  >{{ payloadBrief(row.payload) }}</span>
                  <el-collapse
                    v-if="payloadHasNested(row.payload)"
                    class="payload-collapse"
                  >
                    <el-collapse-item
                      title="完整原文"
                      name="p"
                    >
                      <pre class="payload-pre">{{ jsonOf(row.payload) }}</pre>
                    </el-collapse-item>
                  </el-collapse>
                </div>
                <span
                  v-else
                  class="tl-muted"
                >-</span>
              </template>
            </el-table-column>
          </el-table>
          <div
            v-if="auditTotal"
            class="pager-row"
          >
            <el-pagination
              background
              layout="total, prev, pager, next"
              :total="auditTotal"
              :page-size="auditPageSize"
              :current-page="auditPage"
              @current-change="runAudit"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentEventApi } from '@/api/agentEvent'
import { subTaskApi } from '@/api/subTask'
import { taskApi } from '@/api/task'
import { useAgentNames } from '@/composables/useAgentNames'
import { EVENT_META, eventCategory, eventLabel, eventTypeColor, payloadFields, payloadHasNested } from '@/utils/eventMeta'
import { fmtTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import { SUB_TASK_STATUS_MAP, TASK_STATUS_MAP } from '@/types'
import type { AgentEventItem, LongId, SubTask, Task } from '@/types'

const route = useRoute()
const router = useRouter()

// Agent ID → 注册名解析（事件卡 / Audit 表展示人话名称而非裸 ID）
const { loadAgentNames, resolveAgentName } = useAgentNames()

const activeTab = ref('replay')

// ── 级联入口：任务（必选）/ 子任务（可选聚焦）选择器，免手记 ID；
//    任务列表 / 子任务详情页深链（?taskId=&subTaskId=&tab=）自动带入并查询 ──
const advanced = ref(false)
const selectedTaskId = ref('')
const selectedSubTaskId = ref('')
const taskOptions = ref<Task[]>([])
const subTaskOptions = ref<SubTask[]>([])
// 子任务 id → 标题 / 拓扑序号映射（事件卡归属展示；随选择器加载与 Replay 结果补全）
const subTaskTitleMap = ref<Record<string, string>>({})
// 拓扑正序序号（#N；与子任务列表 / 依赖图 / 草案审阅弹窗同口径）
const subTaskSeqMap = ref<Record<string, number>>({})

function rememberSubTaskMeta(list: SubTask[]) {
  const titles = { ...subTaskTitleMap.value }
  list.forEach((s) => { titles[String(s.id)] = s.title })
  subTaskTitleMap.value = titles
  const seq = { ...subTaskSeqMap.value }
  orderByDependency(list).forEach((s, i) => { seq[String(s.id)] = i + 1 })
  subTaskSeqMap.value = seq
}

// Replay 结果中出现但映射缺失的子任务：按其 taskId 拉取标题与序号补全（仅影响展示，失败静默降级短 ID）
async function ensureSubTaskMeta(evs: AgentEventItem[]) {
  const taskIds = new Set<string>()
  for (const ev of evs) {
    if (ev.subTaskId == null || ev.taskId == null) continue
    if (!subTaskTitleMap.value[String(ev.subTaskId)]) taskIds.add(String(ev.taskId))
  }
  for (const taskId of taskIds) {
    try {
      const result = await subTaskApi.list({ taskId, page: 1, pageSize: 200 })
      rememberSubTaskMeta(result.list)
    } catch {
      // 标题补全失败不阻断回放展示
    }
  }
}

function taskOptionLabel(t: Task): string {
  const status = TASK_STATUS_MAP[t.status]?.label || t.status
  return `${t.title} · ${status} · ${t.id}`
}

function subTaskOptionLabel(s: SubTask): string {
  const status = SUB_TASK_STATUS_MAP[s.status]?.label || s.status
  return `${s.title} · ${status}`
}

async function loadTasks() {
  try {
    const data = await taskApi.list()
    taskOptions.value = Array.isArray(data) ? data : data.list
  } catch {
    taskOptions.value = []
  }
}

async function loadSubTasks() {
  if (!selectedTaskId.value) {
    subTaskOptions.value = []
    return
  }
  try {
    const result = await subTaskApi.list({ taskId: selectedTaskId.value, page: 1, pageSize: 200 })
    subTaskOptions.value = result.list
    rememberSubTaskMeta(result.list)
  } catch {
    subTaskOptions.value = []
  }
}

// 选中任务：清空子任务聚焦 + 加载子任务选项 + 自动跑当前 Tab；清空任务仅复位子任务选项
function onTaskChange() {
  selectedSubTaskId.value = ''
  if (!selectedTaskId.value) {
    subTaskOptions.value = []
    return
  }
  void loadSubTasks()
  autoRun()
}

// 选中 / 清空子任务：Replay 自动刷新（清空 = 回退任务级 Run 轨迹）
function onSubTaskChange() {
  if (activeTab.value === 'replay') void runReplay()
}

function toggleAdvanced() {
  advanced.value = !advanced.value
}

function autoRun() {
  if (activeTab.value === 'replay') void runReplay()
  else void runAudit(1)
}

// ── Replay：任务级 / 子任务级 / 手工 runId 三入口（createTime+id 有序，天然时间线） ──
const replayRunId = ref('')
const replayLoading = ref(false)
const replayEvents = ref<AgentEventItem[]>([])

async function runReplay() {
  const manualRunId = advanced.value ? replayRunId.value.trim() : ''
  if (!manualRunId && !selectedSubTaskId.value && !selectedTaskId.value) {
    ElMessage.warning('请先选择任务，或打开高级模式手工输入 Run ID')
    return
  }
  replayLoading.value = true
  try {
    if (manualRunId) {
      replayEvents.value = await agentEventApi.trace(manualRunId)
    } else if (selectedSubTaskId.value) {
      replayEvents.value = await agentEventApi.traceBySubTaskId(selectedSubTaskId.value)
    } else {
      replayEvents.value = await agentEventApi.traceByTaskId(selectedTaskId.value)
    }
    void ensureSubTaskMeta(replayEvents.value)
  } catch {
    replayEvents.value = []
  } finally {
    replayLoading.value = false
  }
}

// ── Audit：按 taskId 分页查询执行事实（eventType 可选过滤，按时间正序） ──
const auditTaskId = ref('')
const auditEventType = ref('')
const auditLoading = ref(false)
const auditEvents = ref<AgentEventItem[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = ref(20)

async function runAudit(page = auditPage.value) {
  const taskId = (advanced.value && auditTaskId.value.trim()) || selectedTaskId.value
  if (!taskId) {
    ElMessage.warning('请先选择任务，或打开高级模式手工输入 Task ID')
    return
  }
  auditPage.value = page
  auditLoading.value = true
  try {
    const result = await agentEventApi.audit({
      taskId,
      eventType: auditEventType.value || undefined,
      page,
      pageSize: auditPageSize.value
    })
    auditEvents.value = result.list
    auditTotal.value = result.total
  } catch {
    auditEvents.value = []
    auditTotal.value = 0
  } finally {
    auditLoading.value = false
  }
}

// 深链：任务列表 / 子任务详情页跳转自动带入并查询（?tab=audit 直达审计位）
onMounted(async () => {
  void loadAgentNames()
  const q = route.query
  if (q.tab === 'audit') activeTab.value = 'audit'
  await loadTasks()
  if (q.taskId) {
    selectedTaskId.value = String(q.taskId)
    await loadSubTasks()
    if (q.subTaskId) selectedSubTaskId.value = String(q.subTaskId)
    autoRun()
  }
})

// Replay 空态文案：已选数据源 = 无记录；未选 = 引导选择
const replayEmptyText = computed(() => {
  const hasSource =
    !!selectedSubTaskId.value ||
    !!selectedTaskId.value ||
    (advanced.value && !!replayRunId.value.trim())
  return hasSource ? '该范围内暂无事件记录（确认任务已执行且事件已写入）' : '请先选择任务，或打开高级模式手工输入 Run ID'
})

// ── 时间与归属展示辅助（后端 createTime 为 'yyyy-MM-dd HH:mm:ss' 形态，统一解析） ──
function tsOf(t: string | null | undefined): number {
  return Date.parse(String(t ?? '').replace(' ', 'T'))
}

// 时长人话化（毫秒 → 秒/分/时/天）
function fmtDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分 ${s % 60} 秒`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 时 ${m % 60} 分`
  return `${Math.floor(h / 24)} 天 ${h % 24} 时`
}

// 与上一条事件的间隔（首条为空；时间不可解析时降级为空）
function gapOf(index: number): string {
  if (index <= 0) return ''
  const prev = replayEvents.value[index - 1]
  const cur = replayEvents.value[index]
  if (!prev || !cur) return ''
  const t1 = tsOf(prev.createTime)
  const t2 = tsOf(cur.createTime)
  return Number.isNaN(t1) || Number.isNaN(t2) ? '' : '+' + fmtDuration(t2 - t1)
}

// 子任务归属展示：#拓扑序号 + 标题（未命中时降级 #N 或短 ID）
function subTaskRef(subTaskId: LongId | null): string {
  if (subTaskId == null) return ''
  const s = String(subTaskId)
  const seq = subTaskSeqMap.value[s]
  const title = subTaskTitleMap.value[s]
  if (seq && title) return `#${seq} ${title}`
  if (seq) return `#${seq}`
  return title || ('#' + s.slice(-6))
}

function goSubTask(subTaskId: LongId | null) {
  if (subTaskId == null) return
  router.push('/sub-tasks/' + String(subTaskId))
}

// ── Replay run 级汇总（G-006 C2：纯 computed，无额外 API；空结果不展示） ──
const replaySummary = computed(() => {
  const evs = replayEvents.value
  if (!evs.length) return null
  const turns = new Set(evs.filter((e) => e.turn != null).map((e) => e.turn))
  const cats = new Map<string, number>()
  for (const ev of evs) {
    const c = eventCategory(ev.eventType)
    cats.set(c, (cats.get(c) ?? 0) + 1)
  }
  // 时间跨度：createTime 有序（trace 按 createTime+id 升序），首/末即最早/最晚
  const times = evs.map((e) => tsOf(e.createTime)).filter((t) => !Number.isNaN(t))
  let spanText = ''
  if (times.length) {
    const ms = Math.max(0, Math.max(...times) - Math.min(...times))
    const day = Math.floor(ms / 86400000)
    const hour = Math.floor((ms % 86400000) / 3600000)
    const min = Math.floor((ms % 3600000) / 60000)
    const span =
      day > 0 ? `${day} 天 ${hour} 时 ${min} 分` : hour > 0 ? `${hour} 时 ${min} 分` : `${min} 分`
    spanText = `${fmtTime(evs[0].createTime)} → ${fmtTime(evs[evs.length - 1].createTime)}（${span}）`
  }
  // 子任务耗时：同子任务内首末事件时间差（单事件子任务无可度量，跳过；turn 为子任务内维度，不可跨子任务混算）
  const bySub = new Map<string, { min: number; max: number }>()
  for (const ev of evs) {
    if (ev.subTaskId == null) continue
    const t = tsOf(ev.createTime)
    if (Number.isNaN(t)) continue
    const key = String(ev.subTaskId)
    const cur = bySub.get(key)
    bySub.set(key, cur ? { min: Math.min(cur.min, t), max: Math.max(cur.max, t) } : { min: t, max: t })
  }
  const subTaskSpans = [...bySub.entries()]
    .filter(([, v]) => v.max > v.min)
    .sort((a, b) => a[1].min - b[1].min)
    .map(([sid, v]) => {
      const seq = subTaskSeqMap.value[sid]
      const title = subTaskTitleMap.value[sid]
      return {
        id: sid,
        name: seq ? `#${seq}` : '#' + sid.slice(-6),
        tip: (title ? `${seq ? `#${seq} ` : ''}${title}` : `子任务 ${sid}`) + ' · 首末事件时间差',
        text: fmtDuration(v.max - v.min)
      }
    })
  return { turnCount: turns.size, cats: [...cats.entries()], spanText, subTaskSpans }
})

// 事件描述人话化：字典命中用 desc，未命中回退原始类型名
function descOf(ev: AgentEventItem): string {
  return EVENT_META[ev.eventType]?.desc || ev.eventType
}

// Audit 表格 payload 摘要：结构化字段拼接（agent 解析名称），单元格内截断、title 悬停看全文
function payloadBrief(payload: Record<string, any> | null): string {
  const fields = payloadFields(payload)
  if (!fields.length) return ''
  return fields
    .map((f) => `${f.label} ${f.kind === 'agent' ? resolveAgentName(f.value) : f.value}`)
    .join(' · ')
}

// payload 原文美化：审计场景保留原始结构，异常 payload 兜底直显
function jsonOf(payload: Record<string, any>): string {
  try {
    return JSON.stringify(payload, null, 2)
  } catch {
    return String(payload)
  }
}
</script>

<style scoped>
.page {
  max-width: var(--ha-content-width);
  margin: 0 auto;
}
.head-card {
  border: 1px solid var(--ha-border);
  box-shadow: var(--ha-shadow-sm);
  transition: box-shadow var(--ha-duration-normal) var(--ha-ease-out);
}
.head-card:hover { box-shadow: var(--ha-shadow-md); }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.head-note { font-size: 12px; color: var(--ha-muted); }
.query-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.query-stats { font-size: 12px; color: var(--ha-muted); }
.task-select { width: 380px; }
.subtask-select { width: 300px; }

/* ── Replay run 汇总条（G-006 C2） ── */
.summary-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 14px;
  padding: 8px 12px;
  border: 1px dashed var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface-muted, var(--ha-surface, transparent));
}
.summary-chip {
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--ha-border);
  font-size: 12px;
  color: var(--ha-muted);
}
.summary-span { font-variant-numeric: tabular-nums; }

/* ── Replay 时间线：事件卡片化（与 SubTaskDetail 执行时间线同体系） ── */
.tl { padding-left: 4px; }
.tl :deep(.el-timeline-item__wrapper) { padding-left: 14px; top: -2px; }
.tl :deep(.el-timeline-item__timestamp) { display: none; }
.tl-item {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface, transparent);
  transition: border-color var(--ha-duration-fast, 150ms) var(--ha-ease-out, ease-out);
}
.tl-item:hover { border-color: var(--ha-primary); }
.tl-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.tl-top :deep(.el-tag) { border-radius: 999px; font-size: 11px; }
.tl-title {
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ha-ink, inherit);
  letter-spacing: -0.005em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tl-time {
  font-size: 12px;
  color: var(--ha-muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.tl-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ha-muted);
  word-break: break-word;
}
.tl-pos {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  border: 1px solid var(--ha-border);
  font-size: 11px;
  color: var(--ha-muted);
  font-variant-numeric: tabular-nums;
}
.tl-gap {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  border: 1px solid var(--ha-border);
  font-size: 11px;
  color: var(--ha-muted);
  font-variant-numeric: tabular-nums;
}
.tl-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ha-muted);
}
.tl-link {
  height: auto;
  padding: 0;
  font-size: 12px;
}

/* ── payload 关键字段解构（审计原文仍保留在下方折叠） ── */
.tl-fields {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-sm);
  background: var(--ha-surface-muted, var(--ha-surface, transparent));
}
.pf-row {
  display: flex;
  gap: 10px;
  font-size: 12px;
  line-height: 1.6;
}
.pf-label {
  flex: none;
  min-width: 64px;
  color: var(--ha-muted);
}
.pf-value {
  color: var(--ha-ink-secondary, inherit);
  word-break: break-word;
}
.pf-text .pf-value {
  white-space: pre-wrap;
  max-height: 200px;
  overflow: auto;
}

/* ── payload 原文折叠面板 ── */
.payload-collapse { margin-top: 8px; }
:deep(.payload-collapse .el-collapse-item__header) {
  height: auto;
  min-height: 24px;
  font-size: 12px;
  color: var(--ha-muted);
}
:deep(.payload-collapse .el-collapse-item__content) { padding-bottom: 8px; }
.payload-pre {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-sm);
  background: var(--ha-surface);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ha-ink-secondary, inherit);
  max-height: 260px;
  overflow: auto;
}

/* ── Audit 表格 ── */
.audit-table { width: 100%; }
.cell-stack {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ha-muted);
  font-variant-numeric: tabular-nums;
}
.tl-muted { color: var(--ha-muted); font-size: 12px; }
.cell-payload { min-width: 0; }
.cell-brief {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ha-ink-secondary, inherit);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>