<template>
  <div class="page ha-entrance-up">
    <el-card class="chat-card">
      <template #header>
        <div class="card-header">
          <span>对话新建（AI 助手）</span>
          <div class="header-actions">
            <!-- V39 当前会话模式徽标（null 老数据按方案澄清展示） -->
            <el-tag
              v-if="conversation"
              :type="isChatMode ? 'info' : 'warning'"
              size="small"
            >
              {{ isChatMode ? '自由对话' : '方案澄清' }}
            </el-tag>
            <el-button
              size="small"
              type="primary"
              @click="startNew"
            >
              新会话
            </el-button>
            <el-button
              size="small"
              @click="loadList"
            >
              刷新
            </el-button>
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
              active: activeId != null && String(conv.id) === activeId,
              abandoned: conv.status === 'ABANDONED'
            }"
            @click="selectConversation(String(conv.id))"
          >
            <div class="conv-title">
              {{ conv.title || '(无标题)' }}
            </div>
            <div class="conv-meta">
              <el-tag
                :type="statusTag(conv.status)"
                size="small"
              >
                {{ statusLabel(conv.status) }}
              </el-tag>
              <!-- V39 模式小标签：CHAT=对话 / CLARIFY 与 NULL 老数据=方案 -->
              <el-tag
                v-if="conv.mode === 'CHAT'"
                type="info"
                size="small"
                effect="plain"
              >
                对话
              </el-tag>
              <el-tag
                v-else
                type="warning"
                size="small"
                effect="plain"
              >
                方案
              </el-tag>
              <span class="conv-time">{{ fmtTime(conv.createTime) }}</span>
              <!-- 已放弃会话删除（软删，不可恢复；仅 ABANDONED 显示，悬停列表项时可见） -->
              <el-button
                v-if="conv.status === 'ABANDONED'"
                class="conv-del-btn"
                type="danger"
                link
                size="small"
                title="删除该会话及其全部对话记录（不可恢复）"
                @click.stop="handleDeleteConversation(String(conv.id), conv.title)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
          <el-empty
            v-if="!conversations.length"
            description="暂无会话"
            :image-size="60"
          />
        </div>

        <!-- 右栏：气泡流 + 输入框 -->
        <div class="chat-main">
          <!-- V33 澄清进度条（LLM 自评，仅展示不做业务分支；V39 CHAT 自由对话模式隐藏） -->
          <div
            v-if="activeId != null && clarifyProgress != null && !isChatMode"
            class="clarify-progress"
          >
            <span class="progress-label">澄清进度</span>
            <el-progress
              class="progress-bar"
              :percentage="clarifyProgress"
              :stroke-width="8"
              :status="clarifyProgress >= 100 ? 'success' : undefined"
            />
          </div>
          <div
            ref="streamEl"
            class="msg-stream"
          >
            <template v-if="detail">
              <template
                v-for="row in renderMessages"
                :key="String(row.msg.id)"
              >
                <!-- V41 联网搜索折叠查验条（对齐 DeepSeek/Kimi 形态：挂在 assistant 回复上方） -->
                <div
                  v-if="row.webSearch"
                  class="msg-row from-assistant"
                >
                  <WebSearchBar
                    class="ws-wrap"
                    :trace="row.webSearch"
                  />
                </div>
                <!-- 结构化追问：引导语气泡（问题正文由卡片呈现，不重复展示） -->
                <div
                  v-if="row.intro"
                  class="msg-row"
                  :class="row.msg.role === 'user' ? 'from-user' : 'from-assistant'"
                >
                  <div class="msg-bubble">
                    <!-- 用户消息原样展示；assistant 回复渲染 Markdown（标题/表格/列表/引用，流式与历史回显统一） -->
                    <template v-if="row.msg.role === 'user'">
                      {{ row.intro }}
                    </template>
                    <MarkdownView
                      v-else
                      :content="row.intro"
                    />
                  </div>
                </div>
                <!-- V33 历史结构化追问：只读卡片回显当时的选项与选择 -->
                <div
                  v-if="row.structured"
                  class="msg-row from-assistant"
                >
                  <StructuredQuestionCard
                    class="sq-wrap"
                    :questions="row.structured.questions!"
                    readonly
                    :selections="row.selections"
                  />
                </div>
              </template>
              <!-- V33 结构化选项卡片：仅最后一条 assistant 结构化追问且会话 ACTIVE 时可交互 -->
              <div
                v-if="activeStructured"
                class="msg-row from-assistant"
              >
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
            <div
              v-else
              class="chat-placeholder"
            >
              <p>描述你想做的事情，或直接向 AI 助手提问——它会解答疑问、帮你梳理思路。</p>
              <p class="placeholder-tip">
                说「整理成方案」可把讨论转成可落地方案；信息足够时可生成任务终稿并自动拆解。
              </p>
            </div>
            <!-- 上轮 LLM 失败（最后一条是 user 消息）：重试条 -->
            <div
              v-if="canRetry"
              class="msg-row from-assistant"
            >
              <div class="msg-bubble msg-retry">
                <span>回复生成失败</span>
                <el-button
                  size="small"
                  type="primary"
                  plain
                  @click="handleRetry"
                >
                  重试
                </el-button>
              </div>
            </div>
            <!-- 发送中占位气泡 -->
            <div
              v-if="sending && pendingText"
              class="msg-row from-user"
            >
              <div class="msg-bubble">
                {{ pendingText }}
              </div>
            </div>
            <div
              v-if="sending"
              class="msg-row from-assistant"
            >
              <!-- S1 流式回复：token 增量渲染 Markdown；尚未产出 token 时保持思考中占位 -->
              <div
                v-if="streamText"
                class="msg-bubble msg-streaming"
              >
                <MarkdownView :content="streamText" />
              </div>
              <div
                v-else
                class="msg-bubble msg-loading"
              >
                <el-icon class="is-loading">
                  <Loading />
                </el-icon>
                思考中…
              </div>
            </div>

            <!-- 终稿卡片（有终稿即渲染；V39 CHAT 自由对话模式不渲染，仅 CLARIFY/老会话；ACTIVE 可确认，FINALIZED 只读） -->
            <div
              v-if="conversation?.finalTitle && !isChatMode"
              class="final-card"
            >
              <div class="final-card-header">
                <el-tag
                  type="success"
                  size="small"
                >
                  终稿
                </el-tag>
                <span class="final-title">{{ conversation.finalTitle }}</span>
              </div>
              <div class="final-desc">
                <!-- 终稿描述为 Markdown 小节组织（模板要求），渲染富文本而非原样裸露 -->
                <MarkdownView :content="conversation.finalDescription" />
              </div>
              <div class="final-actions">
                <template v-if="conversation.status === 'ACTIVE'">
                  <el-button
                    type="primary"
                    :loading="finalizing"
                    :disabled="sending || finalizing"
                    @click="handleFinalize"
                  >
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
                  >
                    查看任务
                  </el-button>
                  <template v-else>
                    <el-button
                      type="primary"
                      :loading="finalizing"
                      :disabled="sending || finalizing"
                      @click="handleRegenerate"
                    >
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
              <!-- Planner Chat 输入优化（PromptEnhancer）：预览面板，确认后回填输入框，不自动发送、不自动覆盖原文 -->
              <div
                v-if="enhancePanelVisible"
                class="enhance-panel"
              >
                <div class="enhance-panel-header">
                  <span class="enhance-panel-title">优化后的输入（可编辑）</span>
                  <span class="enhance-panel-tip">确认后点「使用此版本」回填输入框，不会自动发送</span>
                </div>
                <el-input
                  v-model="enhancedDraft"
                  type="textarea"
                  :rows="6"
                  resize="vertical"
                  :disabled="enhancing"
                />
                <div class="enhance-panel-actions">
                  <el-button
                    size="small"
                    :loading="enhancing"
                    @click="handleEnhanceInput"
                  >
                    重新优化
                  </el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :disabled="!enhancedDraft.trim() || enhancing"
                    @click="applyEnhancedInput"
                  >
                    使用此版本
                  </el-button>
                  <el-button
                    size="small"
                    text
                    :disabled="enhancing"
                    @click="closeEnhancePanel"
                  >
                    关闭
                  </el-button>
                </div>
              </div>
              <el-input
                v-model="input"
                type="textarea"
                :rows="4"
                resize="none"
                :disabled="sending || finalizing"
                :placeholder="inputPlaceholder"
                @keydown.enter.exact.prevent="handleSend"
              />
              <div class="input-actions">
                <!-- V34 会话级联网搜索开关：ima copilot 样式（默认开启；仅新会话可改；老会话只读取会话原值） -->
                <div class="web-search-switch">
                  <el-tooltip
                    :content="webSearchTooltip"
                    placement="top"
                    :show-after="300"
                  >
                    <span class="web-search-toggle">
                      <el-icon class="web-search-icon"><Connection /></el-icon>
                      <span>联网搜索</span>
                    </span>
                  </el-tooltip>
                  <el-switch
                    v-model="webSearchEnabled"
                    :disabled="activeId != null && conversation?.status === 'ACTIVE'"
                    size="small"
                    inline-prompt
                    active-text="开"
                    inactive-text="关"
                    @change="onWebSearchToggle"
                  />
                </div>
                <!-- 新会话：规划者手动选择；已有会话：展示钉住的规划者 -->
                <div
                  v-if="activeId == null"
                  class="planner-select"
                >
                  <span class="planner-label">规划者</span>
                  <el-select
                    v-model="selectedPlanner"
                    size="small"
                    class="planner-picker"
                  >
                    <el-option
                      label="系统自动（等权重，优先空闲）"
                      value="__auto__"
                    />
                    <el-option
                      v-for="opt in plannerOptions"
                      :key="String(opt.id)"
                      :label="plannerOptionLabel(opt)"
                      :value="String(opt.id)"
                      :disabled="!opt.selectable"
                    />
                  </el-select>
                </div>
                <div
                  v-else-if="pinnedPlannerName"
                  class="planner-select"
                >
                  <span class="planner-label">规划者</span>
                  <el-tag
                    size="small"
                    type="info"
                  >
                    {{ pinnedPlannerName }}
                  </el-tag>
                </div>
                <div class="input-actions-right">
                  <!-- 输入优化：调 LLM 把当前输入改写为结构化表达（仅预览，不自动发送、不覆盖输入框） -->
                  <el-button
                    size="small"
                    plain
                    :loading="enhancing"
                    :disabled="!input.trim() || sending || finalizing"
                    title="调用 AI 把当前输入优化为更清晰的结构化表达（仅预览，不自动发送）"
                    @click="handleEnhanceInput"
                  >
                    <el-icon v-if="!enhancing"><MagicStick /></el-icon>
                    优化输入
                  </el-button>
                  <el-button
                    v-if="activeId && conversation?.status === 'ACTIVE'"
                    size="small"
                    type="danger"
                    plain
                    :disabled="sending || finalizing"
                    @click="handleAbandon"
                  >
                    放弃会话
                  </el-button>
                  <el-button
                    type="primary"
                    :loading="sending"
                    :disabled="!input.trim() || finalizing"
                    @click="handleSend"
                  >
                    发送
                  </el-button>
                </div>
              </div>
            </template>
            <div
              v-else
              class="chat-readonly-tip"
            >
              会话已{{ conversation.status === 'FINALIZED' ? '生成任务' : '放弃' }}，只读展示；点击「新会话」开始新的需求澄清。
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Connection, Delete, MagicStick } from '@element-plus/icons-vue'
import { clarifyApi } from '@/api/clarify'
import { promptEnhanceApi } from '@/api/promptEnhance'
import { taskApi } from '@/api/task'
import { streamSendConversation } from '@/api/chatStream'
import { fmtTime } from '@/utils/tableConfig'
import StructuredQuestionCard from './StructuredQuestionCard.vue'
import WebSearchBar from './WebSearchBar.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import type { ClarifyAssistantPayload, ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, RequirementConversationStatus, RequirementMessage, LongId, WebSearchTrace } from '@/types'

const router = useRouter()

const conversations = ref<RequirementConversation[]>([])
const activeId = ref<LongId | null>(null)
const detail = ref<ClarifyConversationDetail | null>(null)
const conversation = computed(() => detail.value?.conversation ?? null)
// 会话关联任务是否仍存在（仅 detail 返回）；FINALIZED 且为 false 时展示「重新生成」
const taskExists = computed(() => detail.value?.taskExists === true)

// V39 双模式：CHAT 自由对话 / CLARIFY 方案澄清（mode 为 null 的老数据视为 CLARIFY）
const isChatMode = computed(() => conversation.value?.mode === 'CHAT')

// 输入框占位文案按会话模式区分（V39；V40.2 补 /planner 斜杠命令入口提示；§6.169 补 /task 直达拆解提示）
const inputPlaceholder = computed(() => {
  if (activeId.value == null) return '描述你想做的事情，或直接提问；输入 /task 可直达拆解，Enter 发送'
  return isChatMode.value
    ? '和 AI 助手自由对话；输入 /planner 进入方案整理，/task 直达拆解'
    : '继续补充需求，Enter 发送'
})

// V40.2 计划类斜杠命令（/planner|/plan）：显式进入方案澄清模式（CLARIFY）；可带附加文本（落库进上下文后再切）。
// §6.169 /task 直达拆解命令不在此列：后端 doRound 按前缀分流终稿直出轮，前端原样发送不拦截
const PLANNER_COMMAND_RE = /^(?:\/planner|\/plan)(?:\s+([\s\S]+))?$/i

const input = ref('')
const pendingText = ref('')
const sending = ref(false)
const finalizing = ref(false)
const streamEl = ref<HTMLElement | null>(null)

// 输入优化（PromptEnhancer）：先预览、用户自行回填；不自动发送、不自动覆盖原文本输入框
const enhancing = ref(false)
const enhancePanelVisible = ref(false)
const enhancedDraft = ref('')

// S1 Chat SSE 流式：streamText 为流式回复增量全文（60ms 节流写入），done 后拉 detail 收敛清空
const streamText = ref('')
let streamBuffer = ''
let streamFlushTimer: number | null = null

// Planner 下拉选：'__auto__' = 系统自动选择（等权重，优先空闲）
const plannerOptions = ref<PlannerOption[]>([])
const selectedPlanner = ref<string>('__auto__')

// V34 会话级联网搜索开关：默认开启；新会话提交后跟随会话落库（老会话不能改）
const webSearchEnabled = ref<boolean>(true)
const webSearchTooltip = '每轮对话自动联网检索行业资料 / 竞品 / 技术方案，注入 Prompt 增强回答质量；失败自动降级'

// 已存在会话：下拉开关同步为会话原值（不可改）；新会话手动点击
watch(
  () => detail.value?.conversation?.webSearchEnabled,
  (v: boolean | null | undefined) => { if (v != null) webSearchEnabled.value = v }
)

function onWebSearchToggle() { /* 占位：保留供后续埋点/提示扩展 */ }

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

// 可交互的结构化追问：会话 ACTIVE 且最后一条消息是 assistant 的 structured payload；
// V39 曾禁 CHAT 模式交互卡；V40.2 放开——CHAT 模式 LLM 追问（容错双模）同样渲染推荐卡片
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
// V41 起 assistant 消息 payload 含 webSearch 时随行渲染折叠查验条（实时消息与历史回显统一）；
// 最后一条 ACTIVE 结构化追问由可交互卡片承接，此处不重复出只读卡；
// 选择已由上方只读卡高亮回显的 user 消息不再重复出文本气泡
const renderMessages = computed(() => {
  const msgs = detail.value?.messages ?? []
  const rows = msgs.map((msg, i) => {
    const p = assistantPayloadOf(msg)
    const structured = p?.mode === 'structured' && p.questions?.length ? p : null
    const webSearch: WebSearchTrace | null = p?.webSearch ?? null
    if (!structured) return { msg, structured: null, intro: msg.content, selections: null, webSearch }
    const interactive = i === msgs.length - 1 && activeStructured.value != null
    const next = msgs[i + 1]
    return {
      msg,
      structured: interactive ? null : structured,
      intro: introOf(msg.content),
      selections: next ? userSelectionsOf(next) : null,
      webSearch
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
  closeEnhancePanel()
  const sid = String(id)
  activeId.value = sid
  try {
    detail.value = await clarifyApi.detail(sid)
    scrollToBottom()
  } catch { /* 拦截器已弹错 */ }
}

function startNew() {
  if (sending.value) return
  closeEnhancePanel()
  activeId.value = null
  detail.value = null
  input.value = ''
}

// ── 输入优化（PromptEnhancer）：独立辅助链路，不进会话/任务链路 ──

// 优化输入：基于当前输入框内容调 LLM 生成结构化表达，结果进预览面板（不改动输入框、不发送）
async function handleEnhanceInput() {
  const text = input.value.trim()
  if (!text || enhancing.value || sending.value || finalizing.value) return
  enhancing.value = true
  try {
    const result = await promptEnhanceApi.enhance(text)
    enhancedDraft.value = result.optimizedPrompt
    enhancePanelVisible.value = true
  } catch { /* 拦截器已弹错；失败保留原输入 */ }
  finally { enhancing.value = false }
}

// 使用此版本：把（用户可能已编辑的）草稿回填输入框并收起面板，由用户自行点发送
function applyEnhancedInput() {
  const text = enhancedDraft.value.trim()
  if (!text) return
  input.value = text
  closeEnhancePanel()
}

function closeEnhancePanel() {
  enhancePanelVisible.value = false
  enhancedDraft.value = ''
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  // V40.2 /planner 斜杠命令：显式进入方案澄清模式（命令前缀不落消息）
  const cmd = text.match(PLANNER_COMMAND_RE)
  if (cmd) {
    await handlePlannerCommand(cmd[1]?.trim() ?? '')
    return
  }
  closeEnhancePanel()
  input.value = ''
  pendingText.value = text
  sending.value = true
  scrollToBottom()
  try {
    const plannerId = selectedPlanner.value === '__auto__' ? null : (selectedPlanner.value || null)
    // S1 流式分流：已有 CHAT 会话的普通消息走 SSE 流式（token 增量渲染 + done 后收敛）；
    // 新会话（create）/ CLARIFY 模式老会话（同步 send）/ 结构化卡提交（handleStructuredSubmit）保持同步链路
    if (activeId.value != null && isChatMode.value) {
      await streamSendActiveMessage(activeId.value, text)
      return
    }
    // V34：仅新会话向 create 传联网搜索开关；老会话发送消息接口忽略此值
    // 新会话始终 CHAT 模式（LLM auto 意图路由 + /planner 命令触发转方案）
    const result = activeId.value == null
      ? await clarifyApi.create(text, plannerId, webSearchEnabled.value)
      : await clarifyApi.send(activeId.value, text)
    detail.value = result
    activeId.value = String(result.conversation.id)
    loadList()
    scrollToBottom()
    // §6.169 /task 直达拆解：后端已自动建任务，联动提交拆解并打开草案审阅
    if (result.conversation.status === 'FINALIZED' && result.conversation.taskId != null) {
      await triggerTaskReview(String(result.conversation.taskId))
    }
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
        const fid = String(found.id)
        activeId.value = fid
        try { detail.value = await clarifyApi.detail(fid) } catch { /* 拦截器已弹错 */ }
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

// ── S1 Chat SSE 流式发送：节流渲染 + done 后 detail 收敛 ──

// 60ms 节流：token 累积到 buffer，定时 flush 进 streamText 驱动 MarkdownView 全量重渲染
function flushStreamText() {
  streamFlushTimer = null
  if (streamBuffer) {
    streamText.value += streamBuffer
    streamBuffer = ''
    scrollToBottom()
  }
}

function appendStreamToken(token: string) {
  streamBuffer += token
  if (streamFlushTimer == null) {
    streamFlushTimer = window.setTimeout(flushStreamText, 60)
  }
}

// 流收尾：冲残留 buffer → 拉 detail 对齐落库全文（同 tick 内 streamText 清空，无闪烁）；
// 失败场景刷新详情暴露重试条（user 消息已落库）
async function settleStream(id: LongId, success: boolean) {
  flushStreamText()
  try {
    detail.value = await clarifyApi.detail(id)
    activeId.value = String(detail.value?.conversation.id ?? id)
    loadList()
    streamText.value = ''
    scrollToBottom()
  } catch {
    // 拦截器已弹错；流式占位随 sending 结束不再渲染，重试条/重新进入会话兜底
    streamText.value = ''
    if (success) ElMessage.warning('回复已生成，但详情刷新失败，请重新进入会话查看')
  }
}

// 已有 CHAT 会话：普通消息走 SSE 流式（token 增量渲染 + done 后收敛）
async function streamSendActiveMessage(id: LongId, text: string) {
  streamText.value = ''
  streamBuffer = ''
  try {
    await streamSendConversation(id, text, null, {
      onToken: appendStreamToken,
      onDone: () => { void settleStream(id, true) },
      onError: (msg) => {
        ElMessage.error(msg)
        void settleStream(id, false)
      }
    })
  } finally {
    // 兜底清理（正常路径 settleStream 已清空/收敛）
    if (streamFlushTimer != null) {
      window.clearTimeout(streamFlushTimer)
      streamFlushTimer = null
    }
    streamBuffer = ''
    pendingText.value = ''
    sending.value = false
  }
}

// V40.2 /planner 命令处理：新会话先建 CHAT 会话再调 toClarify；已有会话直接调 toClarifyById
// （附加文本落库进上下文后切 CLARIFY，首轮强制 structured → 推荐卡片）
async function handlePlannerCommand(extra: string) {
  if (sending.value) return
  input.value = ''
  pendingText.value = extra || '/planner'
  sending.value = true
  scrollToBottom()
  try {
    const plannerId = selectedPlanner.value === '__auto__' ? null : (selectedPlanner.value || null)
    let result: ClarifyConversationDetail
    if (activeId.value == null) {
      // 新会话：先建 CHAT 会话，再调 toClarify 切换
      const initMsg = extra || '请帮我整理一份技术方案'
      result = await clarifyApi.create(initMsg, plannerId, webSearchEnabled.value)
      activeId.value = String(result.conversation.id)
      result = await clarifyApi.toClarify(activeId.value, extra || null)
    } else {
      result = await clarifyApi.toClarify(activeId.value, extra || null)
    }
    detail.value = result
    activeId.value = String(result.conversation.id)
    loadList()
    scrollToBottom()
  } catch {
    // 拦截器已弹错；附加文本可能已落库（切换失败在 LLM 轮），刷新详情靠重试按钮续跑
    if (activeId.value != null) {
      try { detail.value = await clarifyApi.detail(activeId.value) } catch { /* 拦截器已弹错 */ }
    }
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
    activeId.value = String(detail.value?.conversation.id ?? activeId.value)
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
    activeId.value = String(result.conversation.id)
    scrollToBottom()
  } catch {
    // 拦截器已弹错；user 消息多半已落库，刷新详情后靠重试按钮续跑
    try { detail.value = await clarifyApi.detail(id) } catch { /* 拦截器已弹错 */ }
  } finally {
    pendingText.value = ''
    sending.value = false
  }
}

// §6.169 任务创建后联动：提交拆解 + 打开草案审阅（/task 直达拆解与「创建任务并自动拆解」共用）
async function triggerTaskReview(taskId: string) {
  ElMessage.success('任务已创建，正在后台拆解…')
  try {
    await taskApi.plan(taskId)
    router.push({ path: '/tasks', query: { review: taskId } })
  } catch {
    // 拦截器已弹错；任务已创建成功，跳任务列表可手动重拆
    router.push('/tasks')
  }
}

async function handleFinalize() {
  const conv = conversation.value
  // 并发守卫：对话轮在跑（sending）或已在终稿确认中（finalizing）时拒绝重复提交，
  // 防重复点击与轮中并发 finalize 竞态（后端另有 CAS + 幂等兜底）
  if (!conv || sending.value || finalizing.value) return
  try {
    await ElMessageBox.confirm(
      `将以终稿「${conv.finalTitle}」创建任务，并提交 AI 拆解（草案在后台生成，通常需要一段时间：几十秒到几分钟不等，视任务复杂程度而定）。是否继续？`,
      '创建任务并自动拆解',
      { type: 'info', confirmButtonText: '创建并拆解', cancelButtonText: '取消' }
    )
  } catch { return }
  finalizing.value = true
  try {
    const task = await clarifyApi.finalize(conv.id)
    await triggerTaskReview(String(task.id))
  } catch { /* 拦截器已弹错（无终稿/非 ACTIVE 等） */ }
  finally { finalizing.value = false }
}

async function handleRegenerate() {
  const conv = conversation.value
  // 并发守卫：与 handleFinalize 同语义，防对话轮在跑时重复提交重建任务
  if (!conv || sending.value || finalizing.value) return
  try {
    await ElMessageBox.confirm(
      `原任务已不存在，将以终稿「${conv.finalTitle}」重新创建任务，并提交 AI 拆解（草案在后台生成，通常需要一段时间：几十秒到几分钟不等，视任务复杂程度而定）。是否继续？`,
      '重新生成任务和子任务',
      { type: 'info', confirmButtonText: '重新生成', cancelButtonText: '取消' }
    )
  } catch { return }
  finalizing.value = true
  try {
    const task = await clarifyApi.regenerate(conv.id)
    ElMessage.success('任务已重新创建，正在后台拆解…')
    try {
      await taskApi.plan(String(task.id))
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

// 删除已放弃会话：软删（会话与全部消息 deleted=1），列表刷新后自动隐藏
async function handleDeleteConversation(id: LongId, title?: string | null) {
  try {
    await ElMessageBox.confirm(
      `删除后会话「${title || '(无标题)'}」及其全部对话记录将不可再查看（逻辑删除，不可恢复）。仅已放弃的会话可删除。是否删除？`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await clarifyApi.deleteConversation(id)
    ElMessage.success('会话已删除')
    // 当前详情正是被删会话时回到新会话占位
    if (activeId.value != null && String(activeId.value) === String(id)) {
      startNew()
    }
    loadList()
  } catch { /* 拦截器已弹错（非 ABANDONED / 不存在等） */ }
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

/* 已放弃会话删除按钮：默认隐藏，悬停列表项时可见（防误触；abandoned 半透明不作用于按钮） */
.conv-del-btn { visibility: hidden; opacity: 1 !important; }
.conv-item:hover .conv-del-btn { visibility: visible; }

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

/* V41 联网搜索查验条宽度对齐气泡 */
.ws-wrap { max-width: 72%; min-width: 320px; margin-bottom: 4px; }

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
.input-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 8px; }
.input-actions-right { display: flex; gap: 8px; }
.chat-readonly-tip { color: var(--ha-muted); font-size: 13px; text-align: center; padding: 8px 0; }

/* ── 输入优化（PromptEnhancer）预览面板 ── */
.enhance-panel {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface-elevated);
  padding: 10px 12px;
  margin-bottom: 8px;
}
.enhance-panel-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; }
.enhance-panel-title { font-size: 13px; font-weight: 600; flex-shrink: 0; }
.enhance-panel-tip { font-size: 12px; color: var(--ha-muted); }
.enhance-panel-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 8px; }

/* ── V34 联网搜索开关（仿 ima copilot 风格） ── */
.web-search-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.web-search-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ha-muted);
  cursor: default;
  user-select: none;
}
.web-search-icon { font-size: 14px; }

/* ── Planner 选择 / 重试条 ── */
.planner-select { display: flex; align-items: center; gap: 8px; }
.planner-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.planner-picker { width: 260px; max-width: 100%; }
.msg-retry { display: flex; align-items: center; gap: 10px; color: var(--ha-muted); }

/* ── V39 新会话开始模式选择 ── */
.mode-select { display: flex; align-items: center; flex-shrink: 0; }

@media (max-width: 768px) {
  .chat-layout { flex-direction: column; height: auto; }
  .conv-list { width: 100%; border-right: none; border-bottom: 1px solid var(--ha-border-light); max-height: 180px; padding-right: 0; padding-bottom: 8px; }
}
</style>
