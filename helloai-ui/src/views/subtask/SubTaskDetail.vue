<template>
  <div class="page ha-entrance-up" v-loading="loading">
    <el-card v-if="item">
      <template #header>
        <div class="card-header">
          <span>子任务详情</span>
          <el-button size="small" @click="goBackToList">返回列表</el-button>
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
        <!-- V27 依赖编排可视化补全：前置依赖（全部 DONE 才会分发本任务）与被依赖（本任务完成后解锁的下游） -->
        <el-descriptions-item label="前置依赖" :span="2">
          <template v-if="upstreamItems.length">
            <el-tag
              v-for="dep in upstreamItems" :key="dep.id"
              size="small" class="dep-tag" :type="dep.tagType"
              @click="goSibling(dep.id)"
            >{{ dep.text }}</el-tag>
          </template>
          <span v-else>无（就绪后即可分发）</span>
        </el-descriptions-item>
        <el-descriptions-item label="被依赖" :span="2">
          <template v-if="downstreamItems.length">
            <el-tag
              v-for="dep in downstreamItems" :key="dep.id"
              size="small" class="dep-tag" :type="dep.tagType"
              @click="goSibling(dep.id)"
            >{{ dep.text }}</el-tag>
          </template>
          <span v-else>无</span>
        </el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">{{ item.content || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 方案 2：产出附件（执行产出物化后可单独下载；无附件时不展示卡片） -->
    <el-card v-if="item && attachments.length" style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>产出附件</span>
          <span style="font-size:12px;color:var(--ha-muted)">共 {{ attachments.length }} 个</span>
        </div>
      </template>
      <div class="att-list">
        <div v-for="att in attachments" :key="String(att.id)" class="att-item">
          <span class="att-name" :title="att.fileName">{{ att.fileName }}</span>
          <span class="att-meta">{{ fmtSize(att.fileSize) }} · {{ fmtTime(att.createTime) }}</span>
          <el-button
            link
            size="small"
            type="primary"
            :loading="downloadingAttId === att.id"
            @click="downloadAttachment(att)"
          >下载</el-button>
        </div>
      </div>
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
              #{{ msg.seq }} · {{ msg.role }}/{{ msg.senderType }}<template v-if="msg.senderId"> · {{ resolveAgentName(msg.senderId) }}</template>
              · {{ fmtTime(msg.createTime) }}
            </span>
            <!-- 方案 1：仅“执行产出”保留复制/导出按钮，其余消息展开查看即可 -->
            <div class="conv-actions" v-if="msg.toolName === 'sub_task_execute' && msg.content">
              <el-button link size="small" @click="copyMessage(msg)">复制</el-button>
              <el-button link size="small" type="primary" @click="exportMarkdown(msg)">导出 .md</el-button>
            </div>
          </div>
          <!-- 内容用 Markdown 渲染成带格式富文本（类 DeepSeek/Kimi 观感）；超长折叠展开 -->
          <ReviewVerdictView v-if="msg.toolName === 'subtask_review_verdict'" :content="msg.content" />
          <template v-else>
            <el-collapse v-if="(msg.content?.length || 0) > 300" class="conv-collapse">
              <el-collapse-item :title="'展开全文（' + msg.content.length + ' 字）'" name="c">
                <MarkdownView :content="msg.content" />
              </el-collapse-item>
            </el-collapse>
            <MarkdownView v-else :content="msg.content" />
          </template>
        </div>
      </div>
    </el-card>

    <!-- M4.5: 执行时间线（M5 联调可视化）。V35 加「执行时序图」tab：泳道式 sequence diagram，跳框重试、人工介入、熔断 全可看 -->
    <el-card v-if="item" style="margin-top:16px">
      <template #header>
        <div class="card-header">
          <span>执行时间线</span>
          <span style="font-size:12px;color:var(--ha-muted)">{{ timelinePolling ? '轮询中（5s）' : '已停止' }} · 共 {{ timeline.length }} 条</span>
        </div>
      </template>
      <!-- V35: 列表 / 时序图 双视图切换 -->
      <el-tabs v-model="timelineView" class="timeline-tabs">
        <el-tab-pane label="时间线列表" name="list">
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
                <el-tag size="small" :type="eventTypeColor(ev.eventType)">{{ eventLabel(ev.eventType) }}</el-tag>
                <span class="tl-meta">
                  {{ roleLabel(ev.role) }}<template v-if="ev.agentId"> · {{ resolveAgentName(ev.agentId) }}</template>
                </span>
              </div>
              <!-- 人话化：把事件类型/payload 翻译成非开发者能看懂的一句话 -->
              <div class="tl-desc">{{ eventDescription(ev) }}</div>
              <el-collapse v-if="ev.payload && Object.keys(ev.payload).length" class="tl-collapse">
                <el-collapse-item title="技术详情（开发者）" name="p">
                  <pre class="tl-payload">{{ JSON.stringify(ev.payload, null, 2) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
        <el-tab-pane label="执行时序图" name="seq">
          <SubTaskSequenceFlow :events="timeline" :resolve-agent-name="resolveAgentName" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import { agentApi } from '@/api/agent'
import { attachmentApi } from '@/api/attachment'
import { saveBlobResponse } from '@/utils/download'
import MarkdownView from '@/components/MarkdownView.vue'
import ReviewVerdictView from '@/components/ReviewVerdictView.vue'
import SubTaskSequenceFlow from '@/components/SubTaskSequenceFlow.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { fmtTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import type { SubTask, TaskTimelineItem, ConversationMessageItem, Attachment, LongId } from '@/types'

const route = useRoute()
const router = useRouter()
const item = ref<SubTask | null>(null)
const loading = ref(false)
const timeline = ref<TaskTimelineItem[]>([])
const conversation = ref<ConversationMessageItem[]>([])
let pollTimer: number | null = null
const timelinePolling = ref(false)
// V35: 列表 / 时序图 tab 切换；默认列表保留原始细节，时序图供快速扫描跨角色调用链
const timelineView = ref<'list' | 'seq'>('list')

const TERMINAL_STATUSES: SubTask['status'][] = ['DONE', 'CANCELLED']

// ── V27 依赖编排可视化补全：同主任务兄弟子任务（前置/被依赖标签映射）──
const siblings = ref<SubTask[]>([])

async function loadSiblings(taskId: LongId | null | undefined) {
  if (!taskId) { siblings.value = []; return }
  try {
    siblings.value = await subTaskApi.listAllByTask(taskId)
  } catch {
    // 拉取失败不阻断详情展示，依赖行降级显示"无"
    siblings.value = []
  }
}

// 拓扑正序全局序号（与子任务列表依赖列/依赖图的 #序号 口径一致）
const siblingSeqMap = computed(() => {
  const map = new Map<string, number>()
  orderByDependency(siblings.value).forEach((s, i) => map.set(String(s.id), i + 1))
  return map
})

interface DepDisplayItem { id: string; text: string; tagType: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }

function toDepItem(s: SubTask): DepDisplayItem {
  const seq = siblingSeqMap.value.get(String(s.id))
  const meta = SUB_TASK_STATUS_MAP[s.status]
  return {
    id: String(s.id),
    text: '#' + (seq ?? '?') + ' ' + s.title + '（' + (meta?.label || s.status) + '）',
    tagType: meta?.type || 'info'
  }
}

// 前置依赖：本任务 dependsOn 指向的兄弟（全部 DONE 后才会被分发）
const upstreamItems = computed<DepDisplayItem[]>(() => {
  if (!item.value) return []
  const deps = new Set((item.value.dependsOn || []).map(String))
  return siblings.value.filter(s => deps.has(String(s.id))).map(toDepItem)
})

// 被依赖：dependsOn 包含本任务的下游兄弟（本任务完成后被解锁）
const downstreamItems = computed<DepDisplayItem[]>(() => {
  if (!item.value) return []
  const myId = String(item.value.id)
  return siblings.value.filter(s => (s.dependsOn || []).map(String).includes(myId)).map(toDepItem)
})

// 跳兄弟子任务详情（同组件复用，路由参数变化由 watch 重新加载）
function goSibling(id: string) {
  if (String(route.params.id) === id) return
  router.push('/sub-tasks/' + id)
}

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

// 人话化：事件类型 → （简短标签 + 一句话描述），面向非开发者
const EVENT_META: Record<string, { label: string; desc: string }> = {
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
  // 执行
  sub_task_execute_enter: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_start: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_before_platform: { label: '执行前准备', desc: '执行前的平台准备工作' },
  sub_task_deps_context_loaded: { label: '参考上游产出', desc: '执行 Agent 已读取前置子任务的交付结果，作为本次执行的参考' },
  sub_task_llm_call_start: { label: '调用大模型', desc: '执行 Agent 开始请求大模型生成内容' },
  sub_task_llm_call_end: { label: '大模型返回', desc: '大模型已返回生成结果' },
  sub_task_llm_call_failed: { label: '大模型失败', desc: '调用大模型失败（超时或网络异常）' },
  sub_task_execute_thinking: { label: '思考过程', desc: '执行 Agent 的思考 / 推理过程' },
  sub_task_execute: { label: '执行产出', desc: '执行 Agent 产出了内容' },
  sub_task_execute_submit: { label: '提交产出', desc: '执行 Agent 提交了本次产出' },
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
  // 死信 / 重派
  sub_task_dead_letter: { label: '进入死信', desc: '多次失败，子任务进入死信池' },
  sub_task_dead_letter_manual_assign: { label: '死信重派', desc: '人工把死信子任务重新指派给 Agent' },
  // 任务级
  task_plan_generated: { label: '生成拆解', desc: '已生成任务拆解草案' },
  task_plan_confirmed: { label: '确认拆解', desc: '拆解草案已确认' },
  task_plan_rejected: { label: '驳回拆解', desc: '拆解草案被驳回' },
  task_plan_failed: { label: '拆解失败', desc: '任务拆解失败' },
  task_plan_llm_call_start: { label: '拆解调模型', desc: '开始请求大模型进行任务拆解' },
  task_auto_completed: { label: '任务完成', desc: '所有子任务完成，主任务自动收尾' }
}

const ROLE_LABEL: Record<string, string> = {
  EXECUTOR: '执行者', PLANNER: '规划者', REVIEWER: '核验者', SYSTEM: '系统', USER: '用户'
}

const TRIGGER_LABEL: Record<string, string> = {
  auto_assign: '自动分配', manual: '手动触发', blocked_reassign: '阻塞后重新调度',
  dead_letter_redispatch: '死信重投', poll_recovery: '巡检恢复'
}

function eventLabel(eventType: string): string {
  return EVENT_META[eventType]?.label || eventType
}

function roleLabel(role: string | null): string {
  return (role && ROLE_LABEL[role]) || role || '系统'
}

// Agent ID → 注册名字映射（一次性拉取全量 Agent），展示注册名而非原始 ID
const agentNameMap = ref<Record<string, string>>({})
async function loadAgents() {
  try {
    const agents = await agentApi.list()
    const map: Record<string, string> = {}
    agents.forEach((a) => { map[String(a.id)] = a.name })
    agentNameMap.value = map
  } catch (e) {
    // 拉取失败不阻断详情展示，降级显示短 ID
  }
}
// 优先显示注册名字；未命中（如系统/已删除 Agent）降级为短 ID
function resolveAgentName(agentId: string | number | null): string {
  if (!agentId) return ''
  const s = String(agentId)
  return agentNameMap.value[s] || ('Agent #' + s.slice(-4))
}

function eventDescription(ev: TaskTimelineItem): string {
  const base = EVENT_META[ev.eventType]?.desc || ev.eventType
  const p = ev.payload || {}
  const extras: string[] = []
  if (p.trigger) extras.push('触发方式：' + (TRIGGER_LABEL[p.trigger] || p.trigger))
  if (p.reason) extras.push('原因：' + p.reason)
  if (p.error) extras.push('错误：' + p.error)
  const attempt = p.attempt ?? p.reassignAttemptCount
  if (attempt) extras.push('第 ' + attempt + ' 次尝试')
  return extras.length ? base + '（' + extras.join('；') + '）' : base
}

// 返回列表：携带当前子任务所属主任务 taskId，回到本主任务的子任务列表
function goBackToList() {
  const tid = item.value?.taskId
  router.push(tid ? { path: '/sub-tasks', query: { taskId: String(tid) } } : '/sub-tasks')
}

// V28: 对话流消息来源标签（toolName → 展示文案/颜色）
const CONV_TAG_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'info' | 'warning' }> = {
  sub_task_execute_user_prompt: { label: '执行请求', type: 'info' },
  sub_task_execute_thinking: { label: '思考过程', type: 'info' },
  sub_task_execute: { label: '执行产出', type: 'success' },
  sub_task_execute_failed: { label: '执行失败', type: 'danger' },
  subtask_review_prompt: { label: '核验请求', type: 'info' },
  subtask_review_thinking: { label: '核验思考', type: 'info' },
  subtask_review_verdict: { label: '核验分析', type: 'warning' }
}

function convTagLabel(toolName: string | null) {
  return (toolName && CONV_TAG_MAP[toolName]?.label) || toolName || '消息'
}

function convTagType(toolName: string | null) {
  return (toolName && CONV_TAG_MAP[toolName]?.type) || 'info'
}

// 方案 1：前端导出工具（无后端、无第三方依赖，直接把已展示的产出文本落成文件）
function sanitizeFilename(name: string): string {
  return (name || 'subtask').replace(/[\\/:*?"<>|\r\n]/g, '_').trim().slice(0, 60) || 'subtask'
}

function downloadText(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function exportMarkdown(msg: ConversationMessageItem) {
  const base = sanitizeFilename(item.value?.title || 'subtask')
  const tag = convTagLabel(msg.toolName)
  downloadText(`${base}-${tag}-#${msg.seq}.md`, msg.content || '', 'text/markdown;charset=utf-8')
  ElMessage.success('已导出 Markdown 文件')
}

async function copyMessage(msg: ConversationMessageItem) {
  try {
    await navigator.clipboard.writeText(msg.content || '')
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
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

// ── 方案 2：产出附件列表 + 单附件下载 ──
const attachments = ref<Attachment[]>([])
const downloadingAttId = ref<LongId | null>(null)

async function loadAttachments(id: string) {
  try {
    attachments.value = await attachmentApi.list(id)
  } catch (e) {
    // 附件拉取失败不影响主流程
  }
}

function fmtSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function downloadAttachment(att: Attachment) {
  downloadingAttId.value = att.id
  try {
    const resp = await attachmentApi.download(att.id)
    saveBlobResponse(resp, att.fileName || 'attachment')
  } catch { /* 拦截器已弹错 */ }
  finally { downloadingAttId.value = null }
}

async function pollOnce() {
  const id = String(route.params.id)
  const fresh = await loadDetail(id)
  await loadSiblings(fresh?.taskId)
  await loadTimeline(id)
  await loadConversation(id)
  await loadAttachments(id)
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

// 页面初始化（首次挂载与依赖标签跳兄弟子任务复用；路由参数变化时重新执行）
async function initPage() {
  loading.value = true
  stopPolling()
  try {
    // v1.1 修复：路由参数是 string，不要 Number() 转 LongID（>2^53 会丢精度）
    const id = String(route.params.id)
    await loadDetail(id)
    await loadSiblings(item.value?.taskId)
    await loadTimeline(id)
    await loadConversation(id)
    await loadAttachments(id)
    // 进入页面时启动 5s 轮询；进入终态后停止
    if (item.value && !TERMINAL_STATUSES.includes(item.value.status)) {
      startPolling()
    }
  } catch (e) {
    ElMessage.error('加载子任务详情失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadAgents()
  await initPage()
})

// 依赖标签跳兄弟子任务是同组件路由复用，参数变化时重新加载全部数据
watch(() => route.params.id, (nv, ov) => {
  if (nv && nv !== ov) initPage()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.dep-tag { cursor: pointer; margin: 2px 6px 2px 0; }
.tl-head { display: flex; align-items: center; gap: 8px; }
.tl-meta { color: var(--ha-muted); font-size: 12px; }
.tl-desc { margin: 4px 0 0; font-size: 13px; line-height: 1.6; color: var(--ha-text, inherit); }
.tl-collapse { margin-top: 6px; }
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

/* 方案 2：产出附件 */
.att-list { display: flex; flex-direction: column; gap: 8px; }
.att-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--ha-border, rgba(255,255,255,0.08));
  border-radius: 6px;
}
.att-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.att-meta { color: var(--ha-muted); font-size: 12px; white-space: nowrap; }

/* V28: 执行对话流 */
.conv-list { display: flex; flex-direction: column; gap: 12px; }
.conv-item { border: 1px solid var(--ha-border, rgba(255,255,255,0.08)); border-radius: 6px; padding: 10px 12px; }
.conv-head { display: flex; align-items: center; gap: 8px; }
.conv-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; }
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