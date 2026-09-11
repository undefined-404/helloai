<template>
  <div class="page ha-entrance-up">
    <!-- ── 1. 页面标题区（与 ReviewList / BrowserSessionList 同构） ── -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><Share /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            事件流工作台
            <span
              v-if="activeEvents.length"
              class="query-stats"
              style="margin-left: 4px"
            >共 {{ activeEvents.length }} 条</span>
          </h2>
          <p class="page-subheading">
            Agent 事件回放与审计
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <button
          class="link-text"
          @click="toggleAdvanced"
        >
          <el-icon><Setting /></el-icon>
          {{ advanced ? '收起高级模式' : '高级模式（手工输入 ID）' }}
        </button>
        <span class="query-stats">
          <el-icon style="color: var(--ha-primary)"><InfoFilled /></el-icon>
          G-006：数据源 agent_event 执行轨迹表（只读）
        </span>
      </div>
    </div>

    <!-- ── 2. 顶部 4 张统计卡（G-006 增强：汇总条数字化） ── -->
    <div class="stats-grid ha-stagger-entrance">
      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            事件总数
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><List /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ activeEvents.length }}
        </div>
        <div class="stat-tile-extra">
          <span class="query-stats">覆盖 {{ activeRunCount }} 个 Run</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            涉及子任务
          </div>
          <div class="stat-tile-icon success">
            <el-icon><Connection /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ activeSubTaskCount }}
        </div>
        <div class="stat-tile-extra">
          <span class="query-stats">按拓扑正序聚合</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            执行环节
          </div>
          <div class="stat-tile-icon info">
            <el-icon><Operation /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ activeTurnCount }}
        </div>
        <div class="stat-tile-extra">
          <span class="query-stats">去重 turn</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            时间跨度
          </div>
          <div class="stat-tile-icon warning">
            <el-icon><Timer /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ activeSpanShort }}
        </div>
        <div class="stat-tile-extra">
          <span class="query-stats">{{ activeSpanRange }}</span>
        </div>
      </div>
    </div>

    <!-- ── 3. 查询入口卡（任务选择 + 子任务聚焦 + 高级模式面板） ── -->
    <div class="query-card">
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
        <el-select
          v-if="selectedTaskId && activeTab === 'replay'"
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
      </div>

      <!-- 高级模式面板（默认折叠） -->
      <div
        v-if="advanced"
        class="advanced-panel"
      >
        <div class="advanced-panel-head">
          <div class="advanced-panel-title">
            <el-icon><Filter /></el-icon>
            手工输入 ID 覆盖查询
          </div>
          <span class="readonly-tag">只读模式</span>
        </div>
        <div class="advanced-panel-fields">
          <div class="field">
            <div class="field-label">
              执行 ID（run_id）
            </div>
            <el-input
              v-model="replayRunId"
              placeholder="如 run-2084256044640505858-1"
              clearable
              @keyup.enter="runReplay"
            />
          </div>
          <div class="field">
            <div class="field-label">
              子任务 ID（可空）
            </div>
            <el-input
              v-model="advancedSubTaskId"
              placeholder="如 #6"
              clearable
              @keyup.enter="runReplay"
            />
          </div>
          <div class="field">
            <div class="field-label">
              时间下界
            </div>
            <el-date-picker
              v-model="advancedTimeStart"
              type="datetime"
              placeholder="开始时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 100%"
            />
          </div>
          <div class="field">
            <div class="field-label">
              时间上界
            </div>
            <el-date-picker
              v-model="advancedTimeEnd"
              type="datetime"
              placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 100%"
            />
          </div>
        </div>
        <div class="advanced-panel-actions">
          <button
            class="btn-secondary"
            @click="resetAdvanced"
          >
            <el-icon><RefreshLeft /></el-icon>
            重置
          </button>
          <el-button
            type="primary"
            :loading="replayLoading || auditLoading"
            @click="onAdvancedTrace"
          >
            <el-icon><Search /></el-icon>
            按 ID 追溯
          </el-button>
        </div>
        <div class="advanced-panel-warn">
          <el-icon><WarningFilled /></el-icon>
          手工 ID 绕过场景选择器，仅用于排障；查询结果不写入审计流水。
        </div>
      </div>
    </div>

    <!-- ── 4. 自定义 Tabs（紫下划线） ── -->
    <div class="tabs-bar">
      <button
        class="tab"
        :class="{ active: activeTab === 'replay' }"
        @click="switchTab('replay')"
      >
        Replay <span class="tab-sub">轨迹追溯</span>
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'audit' }"
        @click="switchTab('audit')"
      >
        Audit <span class="tab-sub">审计查询</span>
      </button>
    </div>

    <!-- ── 5. Replay 区 ── -->
    <div
      v-show="activeTab === 'replay'"
      class="ha-entrance-fade"
    >
      <div
        v-if="replaySummary"
        class="summary-strip"
      >
        <span class="summary-chip">去重环节 {{ replaySummary.turnCount }} 个</span>
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
        <span class="summary-chip summary-span">{{ replaySummary.spanText }}</span>
      </div>

      <!-- Replay 加载态：3 行骨架 -->
      <div
        v-if="replayLoading"
        class="tl"
        aria-busy="true"
      >
        <div
          v-for="i in 3"
          :key="i"
          class="tl-skeleton-row"
        >
          <div
            class="ha-skeleton tl-skeleton-bar"
            style="width: 40%"
          />
          <div
            class="ha-skeleton tl-skeleton-bar"
            style="width: 70%"
          />
          <div
            class="ha-skeleton tl-skeleton-bar"
            style="width: 55%"
          />
        </div>
      </div>

      <!-- Replay 空态 -->
      <div
        v-else-if="!replayEvents.length"
        class="empty-state"
      >
        <div class="empty-icon">
          <el-icon><DocumentRemove /></el-icon>
        </div>
        <h3 class="empty-title">
          暂无符合条件的事件
        </h3>
        <p class="empty-desc">
          {{ replayEmptyText }}
        </p>
        <div class="empty-actions">
          <button
            class="btn-secondary"
            @click="resetAdvanced"
          >
            <el-icon><RefreshLeft /></el-icon>
            重置筛选条件
          </button>
          <el-button
            type="primary"
            @click="loadAllEvents"
          >
            <el-icon><View /></el-icon>
            查看全部事件
          </el-button>
        </div>
        <div class="empty-hint">
          <el-icon><InfoFilled /></el-icon>
          提示：可尝试放宽时间范围，或使用上方「高级模式」按 run_id / 子任务 ID 精确追溯
        </div>
      </div>

      <!-- Replay 时间线：自定义节点 + 卡片化事件 -->
      <div
        v-else
        class="tl tl-stagger"
      >
        <div
          v-for="(ev, idx) in replayEvents"
          :key="ev.id"
          class="tl-item"
        >
          <span
            class="tl-node"
            :class="eventCategoryColor(eventCategory(ev.eventType))"
          />
          <span class="tl-line" />
          <span
            class="tl-item-stripe"
            :class="eventCategoryColor(eventCategory(ev.eventType))"
          />
          <div class="tl-top">
            <span
              class="tl-tag"
              :class="['tone-' + eventCategoryColor(eventCategory(ev.eventType)),
                       'bg-' + eventCategoryColor(eventCategory(ev.eventType))]"
            >
              {{ eventCategory(ev.eventType) }}
            </span>
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
      </div>
    </div>

    <!-- ── 6. Audit 区 ── -->
    <div
      v-show="activeTab === 'audit'"
      class="ha-entrance-fade"
    >
      <div class="query-card">
        <div class="query-row">
          <el-select
            v-model="auditEventType"
            placeholder="事件类型（全部）"
            clearable
            filterable
            class="event-type-select"
            @change="runAudit(1)"
          >
            <el-option
              v-for="(meta, type) in EVENT_META"
              :key="type"
              :label="meta.label + '（' + type + '）'"
              :value="type"
            />
          </el-select>
          <el-date-picker
            v-model="auditTimeRange"
            type="datetimerange"
            range-separator="→"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            class="time-range"
            @change="runAudit(1)"
          />
          <el-button
            type="primary"
            :loading="auditLoading"
            @click="runAudit(1)"
          >
            <el-icon><Search /></el-icon>
            查询
          </el-button>
          <button
            class="btn-secondary"
            :disabled="auditLoading"
            @click="resetAudit"
          >
            <el-icon><RefreshLeft /></el-icon>
            重置
          </button>
          <span
            v-if="!auditLoading && auditTotal"
            class="query-stats"
          >共 {{ auditTotal }} 条</span>
          <span
            v-if="auditLoading"
            class="query-stats"
          >
            <span class="dot" />
            正在统计…
          </span>
        </div>
      </div>

      <div class="query-card">
        <el-table
          :data="auditEvents"
          class="audit-table"
          :empty-text="' '"
        >
          <el-table-column
            label="事件类型"
            width="180"
          >
            <template #default="{ row }">
              <span
                class="cell-tag"
                :class="['tone-' + eventCategoryColor(eventCategory(row.eventType)),
                         'bg-' + eventCategoryColor(eventCategory(row.eventType))]"
              >
                {{ eventLabel(row.eventType) }}
              </span>
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
            min-width="220"
          >
            <template #default="{ row }">
              <div class="cell-stack">
                <span>run {{ row.runId }}</span>
                <span v-if="row.taskId != null">task {{ row.taskId }}</span>
                <el-button
                  v-if="row.subTaskId != null"
                  link
                  type="primary"
                  class="cell-link"
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
                class="cell-muted"
              >-</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- Audit 加载态：6 行表格骨架 -->
        <div
          v-if="auditLoading && !auditEvents.length"
          aria-busy="true"
        >
          <div
            v-for="i in 6"
            :key="i"
            class="audit-skeleton-row"
          >
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 14%"
            />
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 18%"
            />
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 22%"
            />
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 12%"
            />
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 14%"
            />
            <div
              class="ha-skeleton audit-skeleton-bar"
              style="width: 18%; margin-left: auto"
            />
          </div>
        </div>

        <!-- Audit 空态 -->
        <div
          v-else-if="!auditLoading && !auditEvents.length"
          class="empty-state"
        >
          <div class="empty-icon">
            <el-icon><FolderOpened /></el-icon>
          </div>
          <h3 class="empty-title">
            暂无符合条件的事件
          </h3>
          <p class="empty-desc">
            当前筛选条件下没有查询到审计事件，请调整事件类型或时间范围后重试
          </p>
          <div class="empty-actions">
            <button
              class="btn-secondary"
              @click="resetAudit"
            >
              <el-icon><RefreshLeft /></el-icon>
              重置筛选条件
            </button>
            <el-button
              type="primary"
              @click="loadAllEvents"
            >
              <el-icon><View /></el-icon>
              查看全部事件
            </el-button>
          </div>
          <div class="empty-hint">
            <el-icon><InfoFilled /></el-icon>
            提示：可尝试放宽时间范围，或使用上方「高级模式」按 run_id / 子任务 ID 精确追溯
          </div>
        </div>

        <!-- Audit 读取数据横幅 -->
        <div
          v-if="auditLoading"
          class="audit-loading-banner"
        >
          <el-icon
            class="is-loading"
          >
            <Loading />
          </el-icon>
          正在从 agent_event 执行轨迹表读取数据，请稍候…
        </div>

        <!-- Audit 分页 -->
        <el-pagination
          v-if="auditTotal"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="auditTotal"
          :page-sizes="[10, 14, 20, 50, 100]"
          :page-size="auditPageSize"
          :current-page="auditPage"
          class="pager-row"
          @current-change="runAudit"
          @size-change="onAuditSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Connection,
  DocumentRemove,
  Filter,
  FolderOpened,
  InfoFilled,
  List,
  Loading,
  Operation,
  RefreshLeft,
  Search,
  Setting,
  Share,
  Timer,
  View,
  WarningFilled
} from '@element-plus/icons-vue'
import { agentEventApi } from '@/api/agentEvent'
import { subTaskApi } from '@/api/subTask'
import { taskApi } from '@/api/task'
import { useAgentNames } from '@/composables/useAgentNames'
import {
  EVENT_META,
  eventCategory,
  eventCategoryColor,
  eventLabel,
  payloadFields,
  payloadHasNested
} from '@/utils/eventMeta'
import { fmtTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import { SUB_TASK_STATUS_MAP, TASK_STATUS_MAP } from '@/types'
import type { AgentEventItem, LongId, SubTask, Task } from '@/types'

const route = useRoute()
const router = useRouter()

// Agent ID → 注册名解析（事件卡 / Audit 表展示人话名称而非裸 ID）
const { loadAgentNames, resolveAgentName } = useAgentNames()

const activeTab = ref<'replay' | 'audit'>('replay')

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

function switchTab(tab: 'replay' | 'audit') {
  activeTab.value = tab
  autoRun()
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

// ── Audit：按 taskId 分页查询执行事实（eventType / 时间范围可选过滤，按时间正序） ──
const auditTaskId = ref('')
const auditEventType = ref('')
const auditTimeRange = ref<[string, string] | null>(null)
const auditLoading = ref(false)
const auditEvents = ref<AgentEventItem[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = ref(14)

// 高级模式面板的额外字段（runId / 子任务 ID / 时间范围）
const advancedSubTaskId = ref('')
const advancedTimeStart = ref('')
const advancedTimeEnd = ref('')

async function runAudit(page = auditPage.value) {
  const taskId = (advanced.value && auditTaskId.value.trim()) || selectedTaskId.value
  if (!taskId) {
    ElMessage.warning('请先选择任务，或打开高级模式手工输入 Task ID')
    return
  }
  auditPage.value = page
  auditLoading.value = true
  try {
    // TODO(G-006 follow-up): 后端 AgentEventAudit 暂未支持 timeStart/timeEnd 参数，
    // 这里 UI 已就位（advancedTimeStart/End + auditTimeRange），等后端补齐 DDL 后切换为：
    //   timeStart: advanced.value ? advancedTimeStart.value : auditTimeRange.value?.[0],
    //   timeEnd:   advanced.value ? advancedTimeEnd.value   : auditTimeRange.value?.[1],
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

function onAuditSizeChange(s: number) {
  auditPageSize.value = s
  auditPage.value = 1
  void runAudit(1)
}

// 高级模式「按 ID 追溯」按钮：按当前 activeTab 触发对应查询
function onAdvancedTrace() {
  if (activeTab.value === 'replay') void runReplay()
  else void runAudit(1)
}

// 重置高级模式字段
function resetAdvanced() {
  replayRunId.value = ''
  advancedSubTaskId.value = ''
  advancedTimeStart.value = ''
  advancedTimeEnd.value = ''
  auditTaskId.value = ''
}

// 重置 Audit 筛选
function resetAudit() {
  auditEventType.value = ''
  auditTimeRange.value = null
  void runAudit(1)
}

// 查看全部事件（空态跳转）：清空筛选 → 重新查询
function loadAllEvents() {
  auditEventType.value = ''
  auditTimeRange.value = null
  if (activeTab.value === 'audit') void runAudit(1)
  else void runReplay()
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

// 当前 tab 的事件列表（stat-tile 数据源）
const activeEvents = computed<AgentEventItem[]>(() => {
  if (activeTab.value === 'replay') return replayEvents.value
  return auditEvents.value
})

// 覆盖的 Run 数（按 runId 去重）
const activeRunCount = computed(() => {
  const set = new Set<string>()
  for (const ev of activeEvents.value) {
    if (ev.runId) set.add(String(ev.runId))
  }
  return set.size
})

// 涉及的子任务数（按 subTaskId 去重，仅含非空）
const activeSubTaskCount = computed(() => {
  const set = new Set<string>()
  for (const ev of activeEvents.value) {
    if (ev.subTaskId != null) set.add(String(ev.subTaskId))
  }
  return set.size
})

// 去重 turn 数（执行环节）
const activeTurnCount = computed(() => {
  const set = new Set<number>()
  for (const ev of activeEvents.value) {
    if (ev.turn != null) set.add(ev.turn)
  }
  return set.size
})

// 时间跨度短描述（如 "28 秒" / "2 时 15 分"）
const activeSpanShort = computed(() => {
  const times = activeEvents.value.map((e) => tsOf(e.createTime)).filter((t) => !Number.isNaN(t))
  if (times.length < 2) return times.length ? '单点' : '—'
  return fmtDuration(Math.max(...times) - Math.min(...times))
})

// 时间跨度区间（首末事件）
const activeSpanRange = computed(() => {
  const evs = activeEvents.value
  if (!evs.length) return '等待数据'
  const first = evs[0]
  const last = evs[evs.length - 1]
  if (!first) return '等待数据'
  if (first === last) return fmtTime(first.createTime)
  return `${fmtTime(first.createTime)} → ${fmtTime(last.createTime)}`
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
    const min = Math.floor((ms % 36000000) / 60000)
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
/* 页面宽度与外层间距（沿用 design-system.css .page 已 token 化的全局 padding） */
.page { max-width: var(--ha-content-width); }

/* 分页右对齐（Audit 表格尾部） */
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

/* 分类色背景/文字对（事件卡 .tl-tag / 表格 .cell-tag 共用） */
/* tone-X 决定文字色，bg-X 决定背景色，与 design-system.css 的 --ha-* token 对齐 */
.tone-primary { color: var(--ha-primary); }
.tone-success { color: var(--ha-success-text); }
.tone-warning { color: var(--ha-warning-text); }
.tone-danger { color: var(--ha-danger-text); }
.tone-info { color: var(--ha-info-text); }
.bg-primary { background: var(--ha-primary-light); }
.bg-success { background: var(--ha-success-bg); }
.bg-warning { background: var(--ha-warning-bg); }
.bg-danger { background: var(--ha-danger-bg); }
.bg-info { background: var(--ha-info-bg); }
html.dark .bg-primary { background: var(--ha-primary-muted); }

/* 响应式：stats-grid 已在 design-system.css 全局 token 化；查询行/面板在 768px 以下单列堆叠 */
@media (max-width: 768px) {
  .query-row .task-select,
  .query-row .subtask-select,
  .query-row .event-type-select,
  .query-row .time-range { width: 100% }
}
</style>
