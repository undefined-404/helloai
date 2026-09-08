<template>
  <div class="page ha-entrance-up">
    <el-card class="head-card full-row">
      <template #header>
        <div class="card-header">
          <span>事件流工作台</span>
          <span class="head-note">G-006：Agent 事件回放（Replay）与审计（Audit），数据源 agent_event 执行轨迹表（只读）</span>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <!-- Replay：按 Run ID 重建一次需求完整执行的细粒度轨迹 -->
        <el-tab-pane
          label="Replay 轨迹追溯"
          name="replay"
        >
          <div class="query-row">
            <el-input
              v-model="replayRunId"
              placeholder="输入 Run ID（如 run-2084256044640505858-1 的 ID 部分）"
              clearable
              style="width: 340px"
              @keyup.enter="runReplay"
            />
            <el-button
              type="primary"
              :loading="replayLoading"
              @click="runReplay"
            >
              追溯
            </el-button>
            <span
              v-if="replayEvents.length"
              class="query-stats"
            >共 {{ replayEvents.length }} 条事件</span>
          </div>

          <el-empty
            v-if="!replayLoading && !replayEvents.length"
            description="输入 Run ID 查询执行轨迹"
          />
          <div
            v-else-if="replaySummary"
            class="summary-strip"
          >
            <span class="query-stats">去重环节 {{ replaySummary.turnCount }} 个</span>
            <span
              v-for="[cat, count] in replaySummary.cats"
              :key="cat"
              class="summary-chip"
            >{{ cat }} ×{{ count }}</span>
            <span class="query-stats summary-span">{{ replaySummary.spanText }}</span>
          </div>
          <el-timeline
            v-else
            class="tl"
          >
            <el-timeline-item
              v-for="ev in replayEvents"
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
                  <span class="tl-time">{{ fmtTime(ev.createTime) }}</span>
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
                <el-collapse
                  v-if="ev.payload"
                  class="payload-collapse"
                >
                  <el-collapse-item
                    title="payload 原文"
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
              v-model="auditTaskId"
              placeholder="输入 Task ID（数字）"
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
                  <span v-if="row.taskId != null">task {{ row.taskId }}<template v-if="row.subTaskId != null"> · sub {{ row.subTaskId }}</template></span>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              label="环节"
              width="80"
            >
              <template #default="{ row }">{{ row.turn ?? '-' }}/{{ row.step ?? '-' }}</template>
            </el-table-column>
            <el-table-column
              label="agentId"
              width="96"
            >
              <template #default="{ row }">{{ row.agentId ?? '-' }}</template>
            </el-table-column>
            <el-table-column
              label="时间"
              width="172"
            >
              <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column
              label="payload"
              min-width="170"
            >
              <template #default="{ row }">
                <el-collapse
                  v-if="row.payload"
                  class="payload-collapse"
                >
                  <el-collapse-item
                    title="查看原文"
                    name="p"
                  >
                    <pre class="payload-pre">{{ jsonOf(row.payload) }}</pre>
                  </el-collapse-item>
                </el-collapse>
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
import { computed, ref } from 'vue'
import { agentEventApi } from '@/api/agentEvent'
import { EVENT_META, eventCategory, eventLabel, eventTypeColor } from '@/utils/eventMeta'
import { fmtTime } from '@/utils/tableConfig'
import type { AgentEventItem } from '@/types'

const activeTab = ref('replay')

// ── Replay：按 runId 拉取整条执行轨迹（createTime+id 有序，天然时间线） ──
const replayRunId = ref('')
const replayLoading = ref(false)
const replayEvents = ref<AgentEventItem[]>([])

async function runReplay() {
  const runId = replayRunId.value.trim()
  if (!runId) return
  replayLoading.value = true
  try {
    replayEvents.value = await agentEventApi.trace(runId)
  } catch {
    replayEvents.value = []
  } finally {
    replayLoading.value = false
  }
}

// ── Audit：按 taskId 分页查询执行事实（eventType 可选过滤，最新在前） ──
const auditTaskId = ref('')
const auditEventType = ref('')
const auditLoading = ref(false)
const auditEvents = ref<AgentEventItem[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = ref(20)

async function runAudit(page = auditPage.value) {
  const taskId = auditTaskId.value.trim()
  if (!taskId) return
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
  const times = evs
    .map((e) => Date.parse(String(e.createTime).replace(' ', 'T')))
    .filter((t) => !Number.isNaN(t))
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
  return { turnCount: turns.size, cats: [...cats.entries()], spanText }
})

// 事件描述人话化：字典命中用 desc，未命中回退原始类型名
function descOf(ev: AgentEventItem): string {
  return EVENT_META[ev.eventType]?.desc || ev.eventType
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
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>