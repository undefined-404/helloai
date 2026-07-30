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
          <div ref="streamEl" class="msg-stream">
            <template v-if="detail">
              <div
                v-for="msg in detail.messages"
                :key="String(msg.id)"
                class="msg-row"
                :class="msg.role === 'user' ? 'from-user' : 'from-assistant'"
              >
                <div class="msg-bubble">{{ msg.content }}</div>
              </div>
            </template>
            <div v-else class="chat-placeholder">
              <p>描述你想做的事情，AI 需求分析师会通过追问帮你澄清边界、交付物与验收标准。</p>
              <p class="placeholder-tip">信息足够时会生成任务终稿，确认后自动创建任务并触发 AI 拆解。</p>
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
import type { ClarifyConversationDetail, RequirementConversation, RequirementConversationStatus, LongId } from '@/types'

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
      ? await clarifyApi.create(text)
      : await clarifyApi.send(activeId.value, text)
    detail.value = result
    activeId.value = result.conversation.id
    loadList()
    scrollToBottom()
  } catch {
    // 拦截器已弹错；user 消息服务端已保留，刷新详情让用户看到并可继续对话
    input.value = text
    if (activeId.value != null) {
      try { detail.value = await clarifyApi.detail(activeId.value) } catch { /* 拦截器已弹错 */ }
    } else {
      loadList()
    }
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

onMounted(() => loadList())
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

@media (max-width: 768px) {
  .chat-layout { flex-direction: column; height: auto; }
  .conv-list { width: 100%; border-right: none; border-bottom: 1px solid var(--ha-border-light); max-height: 180px; padding-right: 0; padding-bottom: 8px; }
}
</style>
