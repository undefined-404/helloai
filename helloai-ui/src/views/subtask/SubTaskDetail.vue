<template>
  <div class="page ha-entrance-up" v-loading="loading">
    <el-card v-if="item">
      <template #header>
        <div class="card-header">
          <span>子任务详情</span>
          <el-button size="small" @click="router.push('/sub-tasks')">返回列表</el-button>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题">{{ item.title }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getSubTaskStatusMeta(item.status)?.type || 'info'" size="small">
            {{ getSubTaskStatusMeta(item.status)?.label || item.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="负责人">{{ item.assignedAgentName || item.assignedAgent || '-' }}</el-descriptions-item>
        <el-descriptions-item label="评分">
          <el-tag v-if="item.scoreGrade" :type="SCORE_GRADE_MAP[item.scoreGrade]?.type || 'info'" size="small">
            {{ SCORE_GRADE_MAP[item.scoreGrade]?.label || item.scoreGrade }}
          </el-tag>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">{{ fmtTime(item.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">{{ item.content || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- V28: 执行对话流（执行产出全文 + 核验 Prompt/分析原文） -->
    <el-card v-if="item" style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>执行对话流</span>
          <span style="font-size:12px;color:var(--ha-muted)">共 {{ conversation.length }} 条</span>
        </div>
      </template>
      <el-empty v-if="!conversation.length" description="暂无对话消息" />
      <div v-else class="conv-list">
        <div v-for="msg in conversation" :key="msg.id" class="conv-item">
          <div class="conv-head">
            <el-tag size="small" :type="convTagType(msg.toolName)">{{ convTagLabel(msg.toolName) }}</el-tag>
            <span class="conv-meta">
              #{{ msg.seq }} · {{ msg.role }}/{{ msg.senderType }}<template v-if="msg.senderId"> · agent={{ msg.senderId }}</template>
              · {{ fmtTime(msg.createTime) }}
            </span>
          </div>
          <el-collapse v-if="msg.content.length > 300" class="conv-collapse">
            <el-collapse-item :title="msg.content.slice(0, 100) + '…（展开全文 ' + msg.content.length + ' 字）'" name="c">
              <pre class="conv-content">{{ msg.content }}</pre>
            </el-collapse-item>
          </el-collapse>
          <pre v-else class="conv-content">{{ msg.content || '-' }}</pre>
        </div>
      </div>
    </el-card>

    <!-- M4.5: 执行时间线（M5 联调可视化） -->
    <el-card v-if="item" style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>执行时间线</span>
          <span style="font-size:12px;color:var(--ha-muted)">{{ timelinePolling ? '轮询中（5s）' : '已停止' }} · 共 {{ timeline.length }} 条</span>
        </div>
      </template>
      <el-empty v-if="!timeline.length" description="暂无时间线事件" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="ev in timeline"
          :key="ev.id"
          :timestamp="fmtTime(ev.createTime)"
          placement="top"
          :type="eventTypeColor(ev.eventType)"
        >
          <div class="tl-head">
            <el-tag size="small" :type="eventTypeColor(ev.eventType)">{{ ev.eventType }}</el-tag>
            <span class="tl-meta">
              {{ ev.role || '-' }}<template v-if="ev.agentId"> · agent={{ ev.agentId }}</template>
            </span>
          </div>
          <el-collapse v-if="ev.payload && Object.keys(ev.payload).length" style="margin-top:6px">
            <el-collapse-item title="payload" name="p">
              <pre class="tl-payload">{{ JSON.stringify(ev.payload, null, 2) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { fmtTime } from '@/utils/tableConfig'
import type { SubTask, TaskTimelineItem, ConversationMessageItem } from '@/types'

const route = useRoute()
const router = useRouter()
const item = ref<SubTask | null>(null)
const loading = ref(false)
const timeline = ref<TaskTimelineItem[]>([])
const conversation = ref<ConversationMessageItem[]>([])
let pollTimer: number | null = null
const timelinePolling = ref(false)

const TERMINAL_STATUSES: SubTask['status'][] = ['DONE', 'CANCELLED']

function getSubTaskStatusMeta(status: SubTask['status']) {
  return SUB_TASK_STATUS_MAP[status]
}

function eventTypeColor(eventType: string): '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (!eventType) return 'info'
  if (eventType.includes('assigned') || eventType.includes('created')) return 'primary'
  if (eventType.includes('completed') || eventType.includes('submitted') || eventType.includes('review')) return 'success'
  if (eventType.includes('blocked') || eventType.includes('rejected') || eventType.includes('failed')) return 'danger'
  if (eventType.includes('paused') || eventType.includes('warning')) return 'warning'
  return 'info'
}

// V28: 对话流消息来源标签（toolName → 展示文案/颜色）
const CONV_TAG_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'info' | 'warning' }> = {
  sub_task_execute: { label: '执行产出', type: 'success' },
  sub_task_execute_failed: { label: '执行失败', type: 'danger' },
  subtask_review_prompt: { label: '核验请求', type: 'info' },
  subtask_review_verdict: { label: '核验分析', type: 'warning' }
}

function convTagLabel(toolName: string | null) {
  return (toolName && CONV_TAG_MAP[toolName]?.label) || toolName || '消息'
}

function convTagType(toolName: string | null) {
  return (toolName && CONV_TAG_MAP[toolName]?.type) || 'info'
}

async function loadDetail(id: string) {
  try {
    const fresh = await subTaskApi.getById(id)
    item.value = fresh
    return fresh
  } catch (e) {
    ElMessage.error('刷新子任务失败')
    return null
  }
}

async function loadTimeline(id: string) {
  try {
    timeline.value = await subTaskApi.timeline(id)
  } catch (e) {
    // 时间线拉取失败不影响主流程
  }
}

async function loadConversation(id: string) {
  try {
    conversation.value = await subTaskApi.conversation(id)
  } catch (e) {
    // 对话流拉取失败不影响主流程
  }
}

async function pollOnce() {
  const id = String(route.params.id)
  const fresh = await loadDetail(id)
  await loadTimeline(id)
  await loadConversation(id)
  if (fresh && TERMINAL_STATUSES.includes(fresh.status)) {
    stopPolling()
  }
}

function startPolling() {
  if (pollTimer !== null) return
  timelinePolling.value = true
  pollTimer = window.setInterval(pollOnce, 5000)
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  timelinePolling.value = false
}

onMounted(async () => {
  loading.value = true
  try {
    // v1.1 修复：路由参数是 string，不要 Number() 转 LongID（>2^53 会丢精度）
    const id = String(route.params.id)
    await loadDetail(id)
    await loadTimeline(id)
    await loadConversation(id)
    // 进入页面时启动 5s 轮询；进入终态后停止
    if (item.value && !TERMINAL_STATUSES.includes(item.value.status)) {
      startPolling()
    }
  } catch (e) {
    ElMessage.error('加载子任务详情失败')
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.tl-head { display: flex; align-items: center; gap: 8px; }
.tl-meta { color: var(--ha-muted); font-size: 12px; }
.tl-payload {
  margin: 0;
  padding: 8px 12px;
  background: var(--ha-surface);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

/* V28: 执行对话流 */
.conv-list { display: flex; flex-direction: column; gap: 12px; }
.conv-item { border: 1px solid var(--ha-border, rgba(255,255,255,0.08)); border-radius: 6px; padding: 10px 12px; }
.conv-head { display: flex; align-items: center; gap: 8px; }
.conv-meta { color: var(--ha-muted); font-size: 12px; }
.conv-collapse { margin-top: 6px; }
.conv-content {
  margin: 6px 0 0;
  padding: 8px 12px;
  background: var(--ha-surface);
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 768px) {
  .page :deep(.el-descriptions__body .el-descriptions__table .el-descriptions-row) {
    display: flex;
    flex-direction: column;
  }
  .page :deep(.el-descriptions__cell) {
    padding: 8px 12px !important;
  }
}
</style>