<template>
  <div class="page ha-entrance-up">
    <el-card class="chat-card">
      <template #header>
        <div class="card-header">
          <span>对话新建（需求澄清）</span>
          <div class="header-actions">
            <el-button size="small" type="primary" @click="startNew">新会话</el-button>
            <el-button size="small" @click="loadList">刷新</el-button>
          </div>
        </div>
      </template>

      <div class="chat-layout">
        <!-- 左栏：会话列表 -->
        <div class="conv-list">
          <div
            v-for="conv in conversations"
            :key="String(conv.id)"
            class="conv-item"
            :class="{
              active: activeId != null && String(conv.id) === String(activeId),
              abandoned: conv.status === 'ABANDONED'
            }"
            @click="selectConversation(conv.id)"
          >
            <div class="conv-title">{{ conv.title || '(无标题)' }}</div>
            <div class="conv-meta">
              <el-tag :type="statusTag(conv.status)" size="small">{{ statusLabel(conv.status) }}</el-tag>
              <span class="conv-time">{{ fmtTime(conv.createTime) }}</span>
            </div>
          </div>
          <el-empty v-if="!conversations.length" description="暂无会话" :image-size="60" />
        </div>

        <!-- 右栏：气泡流 + 输入框 -->
        <div class="chat-main">
          <!-- V33 澄清进度条（LLM 自评，仅展示不做业务分支） -->
          <div v-if="activeId != null && clarifyProgress != null" class="clarify-progress">
            <span class="progress-label">澄清进度</span>
            <el-progress
              class="progress-bar"
              :percentage="clarifyProgress"
              :stroke-width="8"
              :status="clarifyProgress >= 100 ? 'success' : undefined"
            />
          </div>
          <div ref="streamEl" class="msg-stream">
            <template v-if="detail">
              <template v-for="row in renderMessages" :key="String(row.msg.id)">
                <!-- 结构化追问：引导语气泡（问题正文由卡片呈现，不重复展示） -->
                <div v-if="row.intro" class="msg-row" :class="row.msg.role === 'user' ? 'from-user' : 'from-assistant'">
                  <div class="msg-bubble">{{ row.intro }}</div>
                </div>
                <!-- V33 历史结构化追问：只读卡片回显当时的选项与选择 -->
                <div v-if="row.structured" class="msg-row from-assistant">
                  <StructuredQuestionCard
                    class="sq-wrap"
                    :questions="row.structured.questions!"
                    readonly
                    :selections="row.selections"
                  />
                </div>
              </template>
              <!-- V33 结构化选项卡片：仅最后一条 assistant 结构化追问且会话 ACTIVE 时可交互 -->
              <div v-if="activeStructured" class="msg-row from-assistant">
                <StructuredQuestionCard
                  :key="String(lastMessageId)"
                  class="sq-wrap"
                  :questions="activeStructured.questions!"
                  :disabled="sending || finalizing"
                  :loading="sending"
                  @submit="handleStructuredSubmit"
                />
              </div>
            </template>
            <div v-else class="chat-placeholder">
              <p>描述你想做的事情，AI 需求分析师会通过追问帮你澄清边界、交付物与验收标准。</p>
              <p class="placeholder-tip">信息足够时会生成任务终稿，确认后自动创建任务并触发 AI 拆解。</p>
            </div>
            <!-- 上轮 LLM 失败（最后一条是 user 消息）：重试条 -->
            <div v-if="canRetry" class="msg-row from-assistant">
              <div class="msg-bubble msg-retry">
                <span>回复生成失败</span>
                <el-button size="small" type="primary" plain @click="handleRetry">重试</el-button>
              </div>
            </div>
            <!-- 发送中占位气泡 -->
            <div v-if="sending && pendingText" class="msg-row from-user">
              <div class="msg-bubble">{{ pendingText }}</div>
            </div>
            <div v-if="sending" class="msg-row from-assistant">
              <div class="msg-bubble msg-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                思考中…
              </div>
            </div>

            <!-- 终稿卡片（有终稿即渲染；ACTIVE 可确认，FINALIZED 只读） -->
            <div v-if="conversation?.finalTitle" class="final-card">
              <div class="final-card-header">
                <el-tag type="success" size="small">终稿</el-tag>
                <span class="final-title">{{ conversation.finalTitle }}</span>
              </div>
              <pre class="final-desc">{{ conversation.finalDescription }}</pre>
              <div class="final-actions">
                <template v-if="conversation.status === 'ACTIVE'">
                  <el-button type="primary" :loading="finalizing" @click="handleFinalize">
                    创建任务并自动拆解
                  </el-button>
                  <span class="final-tip">不满意可继续对话，让 AI 修正终稿</span>
                </template>
                <template v-else-if="conversation.status === 'FINALIZED'">
                  <el-button
                    v-if="taskExists"
                    type="primary"
                    plain
                    @click="router.push({ path: '/tasks', query: { review: String(conversation.taskId) } })"
                  >查看任务</el-button>
                  <template v-else>
                    <el-button type="primary" :loading="finalizing" @click="handleRegenerate">
                      重新生成任务和子任务
                    </el-button>
                    <span class="final-tip">原任务已删除，可用此终稿重新建任务并自动拆解</span>
                  </template>
                </template>
              </div>
            </div>
          </div>

          <div class="chat-input">
            <template v-if="!conversation || conversation.status === 'ACTIVE'">
              <!-- 新会话：Planner 手动选择；已有会话：展示钉住的 Planner -->
              <div v-if="activeId == null" class="planner-select">
                <span class="planner-label">Planner</span>
                <el-select v-model="selectedPlanner" size="small" class="planner-picker">
                  <el-option label="系统自动（等权重，优先空闲）" value="" />
                  <el-option
                    v-for="opt in plannerOptions"
                    :key="String(opt.id)"
                    :label="plannerOptionLabel(opt)"
                    :value="String(opt.id)"
                    :disabled="!opt.selectable"
                  />
                </el-select>
              </div>
              <div v-else-if="pinnedPlannerName" class="planner-select">
                <span class="planner-label">Planner</span>
                <el-tag size="small" type="info">{{ pinnedPlannerName }}</el-tag>
              </div>
              <el-input
                v-model="input"
                type="textarea"
                :rows="2"
                resize="none"
                :disabled="sending || finalizing"
                :placeholder="activeId ? '继续补充需求，Enter 发送' : '描述你的需求，Enter 发送开启新会话'"
                @keydown.enter.exact.prevent="handleSend"
              />
              <div class="input-actions">
                <el-button
                  v-if="activeId && conversation?.status === 'ACTIVE'"
                  size="small"
                  type="danger"
                  plain
                  :disabled="sending || finalizing"
                  @click="handleAbandon"
                >放弃会话</el-button>
                <el-button
                  type="primary"
                  :loading="sending"
                  :disabled="!input.trim() || finalizing"
                  @click="handleSend"
                >发送</el-button>
              </div>
            </template>
            <div v-else class="chat-readonly-tip">
              会话已{{ conversation.status === 'FINALIZED' ? '生成任务' : '放弃' }}，只读展示；点击「新会话」开始新的需求澄清。
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { clarifyApi } from '@/api/clarify'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import StructuredQuestionCard from './StructuredQuestionCard.vue'
import type { ClarifyAssistantPayload, ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, RequirementConversationStatus, RequirementMessage, LongId } from '@/types'

const router = useRouter()

const conversations = ref<RequirementConversation[]>([])
const activeId = ref<LongId | null>(null)
const detail = ref<ClarifyConversationDetail | null>(null)
const conversation = computed(() => detail.value?.conversation ?? null)
// 会话关联任务是否仍存在（仅 detail 返回）；FINALIZED 且为 false 时展示「重新生成」
const taskExists = computed(() => detail.value?.taskExists === true)

const input = ref('')
const pendingText = ref('')
const sending = ref(false)
const finalizing = ref(false)
const streamEl = ref<HTMLElement | null>(null)

// Planner 下拉选：'' = 系统自动选择（等权重，优先空闲）
const plannerOptions = ref<PlannerOption[]>([])
const selectedPlanner = ref<string>('')

// 上轮 LLM 失败后可重试：ACTIVE 且最后一条是 user 消息（数据驱动，刷新后仍可重试）
const canRetry = computed(() => {
  if (sending.value || conversation.value?.status !== 'ACTIVE') return false
  const msgs = detail.value?.messages ?? []
  return msgs.length > 0 && msgs[msgs.length - 1].role === 'user'
})

const pinnedPlannerName = computed(() => {
  const id = conversation.value?.plannerAgentId
  if (id == null) return ''
  const opt = plannerOptions.value.find(o => String(o.id) === String(id))
  return opt ? opt.name : `Agent#${id}`
})

// ── V33 结构化澄清：payload 解析 + 卡片/进度条派生 ──

function assistantPayloadOf(msg: RequirementMessage): ClarifyAssistantPayload | null {
  if (msg.role !== 'assistant' || !msg.payload) return null
  try {
    const parsed = JSON.parse(msg.payload)
    return parsed && typeof parsed === 'object' ? parsed as ClarifyAssistantPayload : null
  } catch { return null }
}

const lastMessageId = computed(() => {
  const msgs = detail.value?.messages ?? []
  return msgs.length ? msgs[msgs.length - 1].id : ''
})

// 可交互的结构化追问：会话 ACTIVE 且最后一条消息是 assistant 的 structured payload
const activeStructured = computed(() => {
  if (conversation.value?.status !== 'ACTIVE' || sending.value) return null
  const msgs = detail.value?.messages ?? []
  if (!msgs.length) return null
  const p = assistantPayloadOf(msgs[msgs.length - 1])
  return p?.mode === 'structured' && p.questions?.length ? p : null
})

// user 消息 payload：{selections:[...]} 选择快照；非选项回答/解析失败返回 null
function userSelectionsOf(msg: RequirementMessage): ClarifySelection[] | null {
  if (msg.role !== 'user' || !msg.payload) return null
  try {
    const parsed = JSON.parse(msg.payload)
    return Array.isArray(parsed?.selections) ? parsed.selections as ClarifySelection[] : null
  } catch { return null }
}

// 结构化 assistant content 由后端合成（引导语 + \n1. 问题列表），卡片呈现问题时气泡只留引导语
function introOf(content: string) {
  const idx = content.search(/(^|\n)1\. /)
  return idx === -1 ? content : content.slice(0, idx).trim()
}

// 消息流渲染行：结构化追问→引导语气泡 + 只读卡片（选择快照取下一条 user 消息）；
// 最后一条 ACTIVE 结构化追问由可交互卡片承接，此处不重复出只读卡；
// 选择已由上方只读卡高亮回显的 user 消息不再重复出文本气泡
const renderMessages = computed(() => {
  const msgs = detail.value?.messages ?? []
  const rows = msgs.map((msg, i) => {
    const p = assistantPayloadOf(msg)
    const structured = p?.mode === 'structured' && p.questions?.length ? p : null
    if (!structured) return { msg, structured: null, intro: msg.content, selections: null }
    const interactive = i === msgs.length - 1 && activeStructured.value != null
    const next = msgs[i + 1]
    return {
      msg,
      structured: interactive ? null : structured,
      intro: introOf(msg.content),
      selections: next ? userSelectionsOf(next) : null
    }
  })
  for (let i = 1; i < rows.length; i++) {
    if (rows[i - 1].structured && rows[i - 1].selections && rows[i].msg.role === 'user') {
      rows[i].intro = ''
    }
  }
  return rows
})

// 进度：取最近一条带 progress 的 assistant payload；FINALIZED 直接 100
const clarifyProgress = computed(() => {
  if (conversation.value?.status === 'FINALIZED') return 100
  const msgs = detail.value?.messages ?? []
  for (let i = msgs.length - 1; i >= 0; i--) {
    const p = assistantPayloadOf(msgs[i])
    if (p?.progress != null) return Math.min(100, Math.max(0, p.progress))
  }
  return null
})

const STATUS_LABEL: Record<RequirementConversationStatus, string> = {
  ACTIVE: '进行中',
  FINALIZED: '已建任务',
  ABANDONED: '已放弃'
}

function statusLabel(status: RequirementConversationStatus) { return STATUS_LABEL[status] || status }
function statusTag(status: RequirementConversationStatus) {
  return status === 'FINALIZED' ? 'success' : status === 'ABANDONED' ? 'info' : 'primary'
}

async function loadList() {
  try { conversations.value = await clarifyApi.list() } catch { /* 拦截器已弹错 */ }
}

async function loadPlannerOptions() {
  try { plannerOptions.value = await clarifyApi.plannerOptions() } catch { /* 拦截器已弹错 */ }
}

function plannerOptionLabel(opt: PlannerOption) {
  const model = opt.accessType === 'API_KEY_LLM' ? (opt.modelType || '平台内') : '外部 Agent'
  return opt.selectable
    ? `${opt.name}（${model}）`
    : `${opt.name}（${model}·${opt.disabledReason || '不可选'}）`
}

async function scrollToBottom() {
  await nextTick()
  streamEl.value?.scrollTo({ top: streamEl.value.scrollHeight })
}

async function selectConversation(id: LongId) {
  if (sending.value) return
  activeId.value = id
  try {
    detail.value = await clarifyApi.detail(id)
    scrollToBottom()
  } catch { /* 拦截器已弹错 */ }
}

function startNew() {
  if (sending.value) return
  activeId.value = null
  detail.value = null
  input.value = ''
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  pendingText.value = text
  sending.value = true
  scrollToBottom()
  try {
    const result = activeId.value == null
      ? await clarifyApi.create(text, selectedPlanner.value || null)
      : await clarifyApi.send(activeId.value, text)
    detail.value = result
    activeId.value = result.conversation.id
    loadList()
    scrollToBottom()
  } catch {
    // 拦截器已弹错；user 消息多半已落库，刷新详情后靠重试按钮续跑
    if (activeId.value != null) {
      try { detail.value = await clarifyApi.detail(activeId.value) } catch { /* 拦截器已弹错 */ }
    } else {
      // create 失败：会话可能已落库（LLM 失败在建会之后），按标题找回以展示重试按钮
      await loadList()
      const title = text.length <= 50 ? text : text.slice(0, 50)
      const found = conversations.value.find(c => c.status === 'ACTIVE' && c.title === title)
      if (found) {
        activeId.value = found.id
        try { detail.value = await clarifyApi.detail(found.id) } catch { /* 拦截器已弹错 */ }
      }
    }
    // 消息未落库（建会前就失败/未找回会话）时回填输入框，避免丢失用户文本
    const msgs = detail.value?.messages ?? []
    const lastIsSameUserText = msgs.length > 0
      && msgs[msgs.length - 1].role === 'user'
      && msgs[msgs.length - 1].content === text
    if (!lastIsSameUserText) input.value = text
  } finally {
    pendingText.value = ''
    sending.value = false
  }
}

async function handleRetry() {
  if (activeId.value == null || sending.value) return
  sending.value = true
  scrollToBottom()
  try {
    detail.value = await clarifyApi.retry(activeId.value)
    scrollToBottom()
  } catch { /* 拦截器已弹错；保持现状可再次重试 */ }
  finally { sending.value = false }
}

// V33 结构化选项提交：可读文本走 content（LLM 上下文），选择快照走 payload（回显）
async function handleStructuredSubmit(payload: { text: string; selections: ClarifySelection[] }) {
  const id = activeId.value
  if (id == null || sending.value) return
  pendingText.value = payload.text
  sending.value = true
  scrollToBottom()
  try {
    const result = await clarifyApi.send(id, payload.text, payload.selections)
    detail.value = result
    loadList()
    scrollToBottom()
  } catch {
    // 拦截器已弹错；user 消息多半已落库，刷新详情后靠重试按钮续跑
    try { detail.value = await clarifyApi.detail(id) } catch { /* 拦截器已弹错 */ }
  } finally {
    pendingText.value = ''
    sending.value = false
  }
}

async function handleFinalize() {
  const conv = conversation.value
  if (!conv) return
  try {
    await ElMessageBox.confirm(
      `将以终稿「${conv.finalTitle}」创建任务，并自动调用 LLM 做 AI 拆解（约需几十秒）。是否继续？`,
      '创建任务并自动拆解',
      { type: 'info', confirmButtonText: '创建并拆解', cancelButtonText: '取消' }
    )
  } catch { return }
  finalizing.value = true
  try {
    const task = await clarifyApi.finalize(conv.id)
    ElMessage.success('任务已创建，正在自动拆解…')
    try {
      await taskApi.plan(task.id)
      router.push({ path: '/tasks', query: { review: String(task.id) } })
    } catch {
      // 拦截器已弹错；任务已创建成功，跳任务列表可手动重拆
      router.push('/tasks')
    }
  } catch { /* 拦截器已弹错（无终稿/非 ACTIVE 等） */ }
  finally { finalizing.value = false }
}

async function handleRegenerate() {
  const conv = conversation.value
  if (!conv) return
  try {
    await ElMessageBox.confirm(
      `原任务已不存在，将以终稿「${conv.finalTitle}」重新创建任务，并自动调用 LLM 做 AI 拆解（约需几十秒）。是否继续？`,
      '重新生成任务和子任务',
      { type: 'info', confirmButtonText: '重新生成', cancelButtonText: '取消' }
    )
  } catch { return }
  finalizing.value = true
  try {
    const task = await clarifyApi.regenerate(conv.id)
    ElMessage.success('任务已重新创建，正在自动拆解…')
    try {
      await taskApi.plan(task.id)
      router.push({ path: '/tasks', query: { review: String(task.id) } })
    } catch {
      // 拦截器已弹错；任务已创建成功，跳任务列表可手动重拆
      router.push('/tasks')
    }
  } catch { /* 拦截器已弹错（非 FINALIZED / 原任务仍在 / 无终稿等） */ }
  finally { finalizing.value = false }
}

async function handleAbandon() {
  const conv = conversation.value
  if (!conv) return
  try {
    await ElMessageBox.confirm('放弃后该会话不可继续对话（记录保留可查看）。是否放弃？', '放弃会话',
      { type: 'warning', confirmButtonText: '放弃', cancelButtonText: '取消' })
  } catch { return }
  try {
    await clarifyApi.abandon(conv.id)
    ElMessage.success('会话已放弃')
    await selectConversation(conv.id)
    loadList()
  } catch { /* 拦截器已弹错 */ }
}

onMounted(() => {
  loadList()
  loadPlannerOptions()
})
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.header-actions { display: flex; gap: 8px; }

.chat-layout {
  display: flex;
  gap: 12px;
  height: calc(100vh - 220px);
  min-height: 420px;
}

/* ── 左栏会话列表 ── */
.conv-list {
  width: 240px;
  flex-shrink: 0;
  overflow-y: auto;
  border-right: 1px solid var(--ha-border-light);
  padding-right: 12px;
}

.conv-item {
  padding: 10px 12px;
  border-radius: var(--ha-radius-md);
  cursor: pointer;
  margin-bottom: 4px;
  border: 1px solid transparent;
}

.conv-item:hover { background: var(--ha-surface-elevated); }
.conv-item.active { background: var(--ha-primary-muted); border-color: var(--ha-border); }
.conv-item.abandoned { opacity: 0.5; }

.conv-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 6px;
}

.conv-meta { display: flex; align-items: center; gap: 8px; }
.conv-time { font-size: 12px; color: var(--ha-muted); }

/* ── 右栏气泡流 ── */
.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }

/* ── V33 澄清进度条 ── */
.clarify-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px 8px;
  border-bottom: 1px solid var(--ha-border-light);
  margin-bottom: 6px;
}
.progress-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.progress-bar { flex: 1; }

/* 结构化问题卡片宽度对齐气泡 */
.sq-wrap { max-width: 72%; min-width: 320px; }

.msg-stream { flex: 1; overflow-y: auto; padding: 4px 8px; }

.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.from-user { justify-content: flex-end; }
.msg-row.from-assistant { justify-content: flex-start; }

.msg-bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: var(--ha-radius-lg);
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.from-user .msg-bubble { background: var(--ha-primary-muted); }
.from-assistant .msg-bubble { background: var(--ha-surface-elevated); border: 1px solid var(--ha-border-light); }
.msg-loading { display: flex; align-items: center; gap: 6px; color: var(--ha-muted); }

.chat-placeholder {
  color: var(--ha-muted);
  text-align: center;
  padding: 48px 24px 0;
  font-size: 14px;
  line-height: 1.8;
}
.placeholder-tip { font-size: 12px; }

/* ── 终稿卡片 ── */
.final-card {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface-elevated);
  padding: 14px 16px;
  margin: 8px 0 4px;
}

.final-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.final-title { font-size: 15px; font-weight: 600; }

.final-desc {
  margin: 0 0 12px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ha-muted);
  max-height: 320px;
  overflow-y: auto;
}

.final-actions { display: flex; align-items: center; gap: 12px; }
.final-tip { font-size: 12px; color: var(--ha-muted); }

/* ── 输入区 ── */
.chat-input { border-top: 1px solid var(--ha-border-light); padding-top: 10px; }
.input-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.chat-readonly-tip { color: var(--ha-muted); font-size: 13px; text-align: center; padding: 8px 0; }

/* ── Planner 选择 / 重试条 ── */
.planner-select { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.planner-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.planner-picker { width: 300px; max-width: 100%; }
.msg-retry { display: flex; align-items: center; gap: 10px; color: var(--ha-muted); }

@media (max-width: 768px) {
  .chat-layout { flex-direction: column; height: auto; }
  .conv-list { width: 100%; border-right: none; border-bottom: 1px solid var(--ha-border-light); max-height: 180px; padding-right: 0; padding-bottom: 8px; }
}
</style>
