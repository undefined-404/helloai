<template>
  <div class="page ha-entrance-up">
    <el-card class="chat-card">
      <template #header>
        <div class="card-header chat-header">
          <div class="chat-header-title">
            <span class="chat-header-main">对话新建（AI 助手）</span>
            <span class="chat-header-sub">与 AI 助手自由对话；输入 /planner 进入方案整理，/task 直达拆解</span>
          </div>
          <div class="header-actions">
            <!-- 模式 chip + 操作组（刷新 / 分享 / 更多）；新建会话入口收敛在左栏顶部「新建对话」 -->
            <el-tag
              v-if="conversation"
              :type="isChatMode ? 'info' : 'warning'"
              size="small"
            >
              {{ isChatMode ? '自由对话' : '方案澄清' }}
            </el-tag>
            <!-- 操作组：刷新 / 分享 / 更多（更多 = dropdown） -->
            <el-button
              size="small"
              :icon="Refresh"
              @click="loadList"
            >
              刷新
            </el-button>
            <el-button
              size="small"
              :icon="Share"
              @click="handleShare"
            >
              分享
            </el-button>
            <el-dropdown trigger="click" @command="handleMoreAction">
              <el-button
                size="small"
                :icon="MoreFilled"
                aria-label="更多操作"
              />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="export">
                    <el-icon><Download /></el-icon>导出全部会话
                  </el-dropdown-item>
                  <el-dropdown-item command="clear-all" divided>
                    <span class="dropdown-danger">清空已放弃会话</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="docs">
                    <el-icon><Document /></el-icon>查看使用文档
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </template>

      <div class="chat-layout">
        <!-- 左栏：会话列表（设计图：新建对话 + 搜索 + 状态 tabs + 分组） -->
        <div class="conv-list">
          <el-button
            class="conv-new-btn"
            type="primary"
            size="default"
            @click="startNew"
          >
            <el-icon style="margin-right: 6px"><Plus /></el-icon>
            新建对话
          </el-button>
          <el-input
            v-model="searchKeyword"
            placeholder="搜索历史对话"
            clearable
            class="conv-search"
          >
            <template #prefix>
              <el-icon style="color: var(--ha-muted)"><Search /></el-icon>
            </template>
          </el-input>
          <div class="conv-tabs">
            <button
              type="button"
              class="conv-tab"
              :class="{ active: statusTab === 'all' }"
              @click="statusTab = 'all'"
            >
              全部
            </button>
            <button
              type="button"
              class="conv-tab"
              :class="{ active: statusTab === 'ACTIVE' }"
              @click="statusTab = 'ACTIVE'"
            >
              进行中
            </button>
            <button
              type="button"
              class="conv-tab"
              :class="{ active: statusTab === 'FINALIZED' }"
              @click="statusTab = 'FINALIZED'"
            >
              已完成
            </button>
          </div>

          <div class="conv-scroll">
            <template v-if="filteredConversations.length">
              <template
                v-for="group in groupedConversations"
                :key="group.label"
              >
                <div class="conv-group-label">
                  {{ group.label }}
                </div>
                <div
                  v-for="conv in group.items"
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
              </template>
            </template>
            <el-empty
              v-else-if="!conversations.length"
              description="暂无会话"
              :image-size="60"
            />
            <el-empty
              v-else
              description="无匹配会话"
              :image-size="50"
            />
          </div>
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
            <div class="thread">
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
                  <div class="msg-avatar">
                    AI
                  </div>
                  <div class="msg-col">
                    <WebSearchBar
                      class="ws-wrap"
                      :trace="row.webSearch"
                    />
                  </div>
                </div>
                <!-- 结构化追问：引导语气泡（问题正文由卡片呈现，不重复展示） -->
                <div
                  v-if="row.intro"
                  class="msg-row"
                  :class="row.msg.role === 'user' ? 'from-user' : 'from-assistant'"
                >
                  <div class="msg-avatar">
                    {{ row.msg.role === 'user' ? '我' : 'AI' }}
                  </div>
                  <div class="msg-col">
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
                    <!-- 助手消息下的轻量操作工具条 -->
                    <div
                      v-if="row.msg.role === 'assistant'"
                      class="msg-actions"
                    >
                      <button
                        type="button"
                        class="msg-action-btn"
                        title="复制内容"
                        @click="copyMsgContent(row.msg.content)"
                      >
                        <el-icon><CopyDocument /></el-icon>
                      </button>
                      <button
                        type="button"
                        class="msg-action-btn"
                        :class="{ 'is-on': row.liked }"
                        title="有帮助"
                        @click="likeMsg(row.msg.id)"
                      >
                        <el-icon><CaretTop /></el-icon>
                      </button>
                      <button
                        type="button"
                        class="msg-action-btn"
                        title="没帮助"
                        @click="dislikeMsg(row.msg.id)"
                      >
                        <el-icon><CaretBottom /></el-icon>
                      </button>
                      <button
                        type="button"
                        class="msg-action-btn"
                        title="重新生成"
                        @click="regenerateMsg(row.msg.id)"
                      >
                        <el-icon><Refresh /></el-icon>
                      </button>
                      <button
                        type="button"
                        class="msg-action-btn"
                        title="更多"
                        @click="moreMsg(row.msg.id)"
                      >
                        <el-icon><MoreFilled /></el-icon>
                      </button>
                    </div>
                  </div>
                </div>
                <!-- V33 历史结构化追问：只读卡片回显当时的选项与选择 -->
                <div
                  v-if="row.structured"
                  class="msg-row from-assistant"
                >
                  <div class="msg-avatar">
                    AI
                  </div>
                  <div class="msg-col">
                    <StructuredQuestionCard
                      class="sq-wrap"
                      :questions="row.structured.questions!"
                      readonly
                      :selections="row.selections"
                    />
                  </div>
                </div>
              </template>
              <!-- V33 结构化选项卡片：仅最后一条 assistant 结构化追问且会话 ACTIVE 时可交互 -->
              <div
                v-if="activeStructured"
                class="msg-row from-assistant"
              >
                <div class="msg-avatar">
                  AI
                </div>
                <div class="msg-col">
                  <StructuredQuestionCard
                    :key="String(lastMessageId)"
                    class="sq-wrap"
                    :questions="activeStructured.questions!"
                    :disabled="sending || finalizing"
                    :loading="sending"
                    @submit="handleStructuredSubmit"
                  />
                </div>
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
              <div class="msg-avatar">
                AI
              </div>
              <div class="msg-col">
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
            </div>
            <!-- 发送中占位气泡 -->
            <div
              v-if="sending && pendingText"
              class="msg-row from-user"
            >
              <div class="msg-avatar">
                我
              </div>
              <div class="msg-col">
                <div class="msg-bubble">
                  {{ pendingText }}
                </div>
              </div>
            </div>
            <div
              v-if="sending"
              class="msg-row from-assistant"
            >
              <div class="msg-avatar">
                AI
              </div>
              <div class="msg-col">
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
          </div>

          <div class="chat-input">
            <div class="chat-input-inner">
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
                <div class="composer-area">
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
                    <div class="input-actions-left">
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
                </div>
              </template>
              <div
                v-else
                class="chat-readonly-tip"
              >
                会话已{{ conversation.status === 'FINALIZED' ? '生成任务' : '放弃' }}，只读展示；点击「新会话」开始新的需求澄清。
              </div>
              <!-- 页脚：内容由 AI 生成，仅供参考 -->
              <div class="chat-disclaimer">
                内容由 AI 生成，仅供参考
              </div>
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
import { Loading, Connection, Delete, MagicStick, CopyDocument, CaretTop, CaretBottom, Refresh, MoreFilled, Plus, Search, Share, Download, Document } from '@element-plus/icons-vue'
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
// 设计图：左栏顶部 + 搜索框 + 状态 tabs
const searchKeyword = ref('')
const statusTab = ref<'all' | 'ACTIVE' | 'FINALIZED'>('all')
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
    if (!structured) return { msg, structured: null, intro: msg.content, selections: null, webSearch, liked: false }
    const interactive = i === msgs.length - 1 && activeStructured.value != null
    const next = msgs[i + 1]
    return {
      msg,
      structured: interactive ? null : structured,
      intro: introOf(msg.content),
      selections: next ? userSelectionsOf(next) : null,
      webSearch,
      liked: false
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

// 设计图左栏：搜索 + 状态 tabs 过滤后的会话列表
const filteredConversations = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return conversations.value.filter(c => {
    if (statusTab.value !== 'all' && c.status !== statusTab.value) return false
    if (!kw) return true
    return (c.title || '').toLowerCase().includes(kw)
  })
})

// 设计图左栏：按日期分组（今天 / 昨天 / 7天内 / 更早）
const groupedConversations = computed(() => {
  const list = filteredConversations.value
  const now = new Date()
  const today0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterday0 = today0 - 86400000
  const week0 = today0 - 7 * 86400000

  const groups: { label: string; items: RequirementConversation[] }[] = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '7天内', items: [] },
    { label: '更早', items: [] }
  ]
  for (const c of list) {
    const ms = c.createTime ? Date.parse(c.createTime) : NaN
    if (Number.isNaN(ms)) { groups[3].items.push(c); continue }
    if (ms >= today0) groups[0].items.push(c)
    else if (ms >= yesterday0) groups[1].items.push(c)
    else if (ms >= week0) groups[2].items.push(c)
    else groups[3].items.push(c)
  }
  return groups.filter(g => g.items.length > 0)
})

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

// ── 消息行操作工具条（复制 / 点赞 / 点踩 / 重新生成 / 更多）：前端占位 UI，未接后端 ──
// 设计图与同类 AI 对话界面均提供此套交互；当前无后端记录，后端有埋点再迁移到消息表。
async function copyMsgContent(content: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(content)
    } else {
      // 旧浏览器降级：临时 textarea + execCommand
      const ta = document.createElement('textarea')
      ta.value = content
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function likeMsg(id: LongId) {
  ElMessage.info('反馈已记录（前端占位，待后端埋点）')
}
function dislikeMsg(id: LongId) {
  ElMessage.info('反馈已记录（前端占位，待后端埋点）')
}
function regenerateMsg(id: LongId) {
  ElMessage.info('重新生成（前端占位，待接后端）')
}
function moreMsg(id: LongId) {
  ElMessage.info('更多操作（前端占位，待接后端）')
}

// ─ 设计图右上角动作：分享 / 更多 ─
async function handleShare() {
  const conv = conversation.value
  const title = conv?.title || '当前会话'
  const url = window.location.href
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
      ElMessage.success(`已复制「${title}」链接到剪贴板`)
    } else {
      ElMessage.info(`分享链接：${url}`)
    }
  } catch {
    ElMessage.info(`分享链接：${url}`)
  }
}

function handleMoreAction(cmd: string) {
  if (cmd === 'clear-all') {
    ElMessageBox.confirm('确认清空所有已放弃会话？此操作不可恢复。', '清空已放弃会话', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    }).then(async () => {
      const abandoned = conversations.value.filter(c => c.status === 'ABANDONED')
      for (const c of abandoned) {
        try { await clarifyApi.deleteConversation(String(c.id)) } catch { /* 拦截器已弹错 */ }
      }
      await loadList()
      ElMessage.success('已清空已放弃会话')
    }).catch(() => { /* 用户取消 */ })
  } else if (cmd === 'export') {
    ElMessage.info('导出全部会话（前端占位，待后端导出接口）')
  } else if (cmd === 'docs') {
    ElMessage.info('查看使用文档（前端占位）')
  }
}

onMounted(() => {
  loadList()
  loadPlannerOptions()
})
</script>

<style scoped>
/* ============================================================
   Chat 布局规范（参考设计图与 chat-layout.html 骨架）
   ① 外层 100dvh + flex-column
   ② 中间滚动区必须加 min-height:0（flex 子项默认 min-height:auto，
      会被内容撑开，整页跟着滚，这是 chat 页面最经典的一个坑）
   ③ 消息内容区 max-width:768px 居中（宽屏上一行不拉成一条长河）
   ④ 一行消息 = 头像 + 内容列；用户行整体 flex-direction:row-reverse
   ⑤ 长英文/长链接 → .col min-width:0 + .bubble overflow-wrap:anywhere
   ⑥ 代码块 / 表格 / 图片溢出三件套
   ⑦ 智能滚动：贴底才追，用户手动往上翻时不拽回
   ============================================================ */

.page {
  max-width: var(--ha-content-width);
  height: calc(100dvh - 0px);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 把 el-card 本身也接管成三段式骨架（顶 header / 中间滚动 / 底 composer） */
:deep(.chat-card) {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}
:deep(.chat-card .el-card__header) { flex: 0 0 auto; }
:deep(.chat-card .el-card__body) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 !important;
}

.card-header { display: flex; align-items: center; justify-content: space-between; }
.header-actions { display: flex; gap: 8px; }

/* ── 顶bar（设计图）：左侧标题 + 副标题双行，右侧操作按钮组 ── */
.chat-header {
  align-items: center;
  gap: 16px;
  padding-top: 2px;
}
.chat-header-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.chat-header-main {
  font-size: 17px;
  font-weight: 600;
  color: var(--ha-ink);
  line-height: 1.3;
  letter-spacing: -0.01em;
}
.chat-header-sub {
  font-size: 13px;
  color: var(--ha-muted);
  line-height: 1.5;
  letter-spacing: 0;
}
/* 设计图：右组操作按钮紧凑 + 与左侧内容上下居中对齐 */
.chat-header .header-actions {
  padding-top: 0;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
}
/* 设计图：刷新 / 分享按钮 = 白底 + 边框（el-button 默认中性态） */
.chat-header .header-actions .el-button:not(.el-button--primary) {
  /* 与设计图标按钮同款：浅灰边 + 中性色 */
  --el-button-bg-color: var(--ha-surface);
  --el-button-border-color: var(--ha-border-light);
  --el-button-text-color: var(--ha-ink-secondary);
  --el-button-hover-bg-color: var(--ha-surface-hover);
  --el-button-hover-border-color: var(--ha-border);
  --el-button-hover-text-color: var(--ha-primary);
}
/* 设计图：自由对话 chip（info）放在新会话左侧；新会话 primary 在左组最右 */
.chat-header .header-actions .el-tag { margin-right: 2px; }

/* ── 主体三段式：左栏列表 / 右栏 chat main ── */
.chat-layout {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  gap: 0;
}

/* ── 左栏会话列表 ── */
/* ── 左栏会话列表 ── 设计图布局：
   flex-column 三段：顶部（新建按钮 / 搜索 / 状态 tabs）固定不动 + 下方滚动区 */
.conv-list {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--ha-border-light);
  padding: 14px 14px 12px 16px;
  background: var(--ha-surface-elevated);
  gap: 10px;
}

/* 设计图顶部：紫色 primary「+ 新建对话」按钮（全宽，圆角 10px） */
.conv-new-btn {
  width: 100%;
  border-radius: 10px !important;
  font-weight: 500 !important;
}

/* 设计图：搜索框（带 🔍 前缀图标） */
.conv-search {
  width: 100%;
}

/* 设计图：状态 tabs（全部 / 进行中 / 已完成），自定义按钮 + 高亮态 */
.conv-tabs {
  display: flex;
  gap: 6px;
  padding: 2px 0 4px;
  border-bottom: 1px solid var(--ha-border-light);
}
.conv-tab {
  border: 0;
  background: transparent;
  color: var(--ha-muted);
  font-size: 13px;
  padding: 6px 12px;
  border-radius: var(--ha-radius-md);
  cursor: pointer;
  transition: all var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-tab:hover { color: var(--ha-ink); background: var(--ha-surface-hover); }
.conv-tab.active {
  color: var(--ha-primary);
  background: var(--ha-primary-muted);
  font-weight: 600;
}

/* 设计图：会话滚动区（吃满剩余高度），按日期分组 */
.conv-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 2px 4px 0;
  margin-right: -2px;
}

/* 设计图：日期分组小标题（今天 / 昨天 / 7天内 / 更早） */
.conv-group-label {
  font-size: 12px;
  color: var(--ha-muted);
  padding: 10px 8px 6px;
  font-weight: 500;
  letter-spacing: 0;
}

.conv-item {
  padding: 10px 12px;
  border-radius: var(--ha-radius-md);
  cursor: pointer;
  margin-bottom: 4px;
  border: 1px solid transparent;
  transition: background var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-item:hover { background: var(--ha-surface-hover); }
.conv-item.active { background: var(--ha-primary-muted); border-color: var(--ha-primary-light); }
.conv-item.abandoned { opacity: 0.5; }

.conv-title {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 6px;
  color: var(--ha-ink);
}
.conv-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.conv-time { font-size: 12px; color: var(--ha-muted); }

.conv-del-btn { visibility: hidden; opacity: 1 !important; }
.conv-item:hover .conv-del-btn { visibility: visible; }

/* ── 右栏：垂直三段（顶 progress / 中间滚动 / 底 composer） ── */
.chat-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--ha-bg);
}

/* ── V33 澄清进度条 ── */
.clarify-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px 8px;
  border-bottom: 1px solid var(--ha-border-light);
  flex-shrink: 0;
  background: var(--ha-surface-elevated);
}
.progress-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.progress-bar { flex: 1; }

/* ── ② 关键中的关键：min-height:0 ── */
.msg-stream {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  /* 设计图：消息流用右栏全宽；仅留 16px 左右内边距，不居中 */
  padding: 16px 16px 20px;
}

/* ── ③ ④ ⑤ 消息行骨架 ──
   设计图：thread 不居中不设 max-width；用全宽让用户/助手气泡靠边贴齐 */
.thread {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-bottom: 8px;
}

.msg-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
/* 用户行：整体镜像（比手写 margin-left:auto 干净） */
.msg-row.from-user { flex-direction: row-reverse; }

.msg-avatar {
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 600;
  user-select: none;
  flex-shrink: 0;
}
.msg-row.from-assistant .msg-avatar {
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
  border: 1px solid var(--ha-primary-light);
}
.msg-row.from-user .msg-avatar {
  background: var(--ha-primary);
  color: #fff;
}

.msg-col {
  /* min-width:0 是防长内容爆 flex 子项的关键 */
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* 用户/助手气泡统一 75% 限宽对齐；
   气泡自身 width:100% 始终填满 col，所以两边视觉同宽 */
.msg-row.from-assistant .msg-col {
  max-width: 75%;
}
.msg-row.from-user .msg-col {
  max-width: 75%;
  align-items: flex-end;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: var(--ha-radius-lg);
  font-size: 14px;
  line-height: 1.65;
  /* ⑥ 长内容防爆 */
  overflow-wrap: anywhere;
  word-break: break-word;
  /* 气泡始终填满 col，保证用户/助手气泡视觉同宽；
     col 上限 75% 由 .msg-col 控制 */
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.msg-row.from-assistant .msg-bubble {
  background: var(--ha-surface-elevated);
  border: 1px solid var(--ha-border-light);
  color: var(--ha-ink);
  border-top-left-radius: 6px;
}
.msg-row.from-user .msg-bubble {
  background: var(--ha-primary);
  color: #fff;
  border-top-right-radius: 6px;
}

/* Markdown 溢出三件套（针对 MarkdownView 渲染内容） */
.msg-bubble :deep(pre) {
  overflow-x: auto;
  background: rgba(0, 0, 0, 0.06);
  color: var(--ha-ink);
  padding: 10px 12px;
  border-radius: var(--ha-radius-sm);
  font-size: 13px;
  margin: 8px 0;
}
.msg-row.from-user .msg-bubble :deep(pre) {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}
.msg-bubble :deep(table) {
  display: block;
  overflow-x: auto;
  max-width: 100%;
  border-collapse: collapse;
}
.msg-bubble :deep(th),
.msg-bubble :deep(td) {
  border: 1px solid var(--ha-border-light);
  padding: 6px 10px;
}
.msg-row.from-user .msg-bubble :deep(th),
.msg-row.from-user .msg-bubble :deep(td) {
  border-color: rgba(255, 255, 255, 0.2);
}
.msg-bubble :deep(img) { max-width: 100%; height: auto; border-radius: var(--ha-radius-sm); }
.msg-bubble :deep(p:first-child) { margin-top: 0; }
.msg-bubble :deep(p:last-child) { margin-bottom: 0; }
.msg-bubble :deep(ul) { padding-left: 1.2em; margin: 6px 0; }
.msg-bubble :deep(blockquote) {
  margin: 6px 0;
  padding: 4px 10px;
  border-left: 3px solid var(--ha-primary);
  color: var(--ha-ink-secondary);
  background: rgba(124, 58, 237, 0.04);
}
.msg-row.from-user .msg-bubble :deep(blockquote) {
  border-left-color: rgba(255, 255, 255, 0.5);
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.85);
}
.msg-bubble :deep(a) {
  color: var(--ha-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.msg-row.from-user .msg-bubble :deep(a) {
  color: #fff;
  text-decoration-color: rgba(255, 255, 255, 0.6);
}

/* 助手消息下的轻量操作工具条（复制 / 点赞 / 点踩 / 重生成） */
.msg-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px 0;
}
.msg-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--ha-radius-sm);
  color: var(--ha-muted);
  font-size: 14px;
  cursor: pointer;
  transition: background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
  background: transparent;
  border: 0;
}
.msg-action-btn:hover {
  background: var(--ha-surface-hover);
  color: var(--ha-ink);
}
.msg-action-btn.is-on { color: var(--ha-primary); }

/* V33 终稿按钮行：和 msg-actions 走同一容器（用户设计图中"已处理"一行） */
.msg-actions .final-shortcut {
  font-size: 13px;
  color: var(--ha-primary);
  background: transparent;
  border: 0;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: var(--ha-radius-sm);
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.msg-actions .final-shortcut:hover { background: var(--ha-primary-muted); }

.msg-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ha-muted);
  font-size: 13px;
}
.msg-streaming { font-size: 14px; }

/* 结构化问题卡片 / 联网搜索查验条：与气泡同宽上限 */
.sq-wrap,
.ws-wrap {
  max-width: 100%;
  min-width: 0;
  width: 100%;
}

/* 终稿卡 */
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
.final-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.final-tip { font-size: 12px; color: var(--ha-muted); }

/* 占位 */
.chat-placeholder {
  color: var(--ha-muted);
  text-align: center;
  padding: 80px 24px 0;
  font-size: 14px;
  line-height: 1.8;
}
.placeholder-tip { font-size: 12px; }

/* ── 输入区（composer） ──
   设计图：composer 占右栏全宽，圆角白卡；不要居中、不要 max-width 限制 */
.chat-input {
  flex: 0 0 auto;
  border-top: 1px solid var(--ha-border-light);
  background: var(--ha-bg);
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
}
.chat-input-inner {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 输入优化（PromptEnhancer）预览面板 */
.enhance-panel {
  border: 1px solid var(--ha-primary-light);
  border-radius: var(--ha-radius-md);
  background: var(--ha-primary-muted);
  padding: 10px 12px;
}
.enhance-panel-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.enhance-panel-title { font-size: 13px; font-weight: 600; flex-shrink: 0; color: var(--ha-primary); }
.enhance-panel-tip { font-size: 12px; color: var(--ha-muted); }
.enhance-panel-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 8px; }

/* textarea 容器：设计图用淡灰边 + 紫色 focus 光环 */
.composer-area {
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              box-shadow var(--ha-duration-fast) var(--ha-ease-out);
}
.composer-area:focus-within {
  border-color: var(--ha-primary);
  box-shadow: 0 0 0 2px var(--ha-primary-muted);
}
.composer-area :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  background: transparent !important;
  resize: none;
  font-size: 14px;
  line-height: 1.6;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.input-actions-left { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.input-actions-right { display: flex; align-items: center; gap: 8px; }

.chat-readonly-tip {
  color: var(--ha-muted);
  font-size: 13px;
  text-align: center;
  padding: 16px 8px;
}

/* V34 联网搜索开关 */
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

.planner-select { display: flex; align-items: center; gap: 8px; }
.planner-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.planner-picker { width: 240px; max-width: 100%; }

.msg-retry {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ha-muted);
}

/* V39 模式 chip（input 上方） */
.mode-select {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* 页脚 AI 生成声明 */
.chat-disclaimer {
  text-align: center;
  font-size: 12px;
  color: var(--ha-muted);
  margin-top: 8px;
}

/* ── 响应式 ── */
@media (max-width: 960px) {
  /* 窄屏：左栏缩到 200px */
  .conv-list {
    width: 200px;
    padding: 10px 10px 10px 12px;
  }
}
@media (max-width: 768px) {
  .chat-layout { flex-direction: column; }
  .conv-list {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
    border-right: none;
    border-bottom: 1px solid var(--ha-border-light);
    max-height: 220px;
    padding: 8px 12px;
    gap: 8px;
  }
  .conv-new-btn { width: auto; flex: 0 0 auto; }
  .conv-search { flex: 1 1 200px; }
  .conv-tabs { width: 100%; border-bottom: none; padding: 0; }
  .conv-scroll { display: none; } /* 窄屏隐藏滚动列表，简单用搜索 + tabs 切换 */
  /* 窄屏：气泡放宽到 94vw，避免文字过窄；用户/助手一致 */
  .msg-row.from-assistant .msg-col,
  .msg-row.from-user .msg-col {
    max-width: calc(94vw - 48px);
  }
  .thread { padding: 0 4px; gap: 14px; }
  .msg-stream { padding: 14px 8px 16px; }
  .chat-input { padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); }
}

/* 下拉菜单中的危险项：与 TaskList/SubTaskList 同构 */
.dropdown-danger { color: var(--ha-danger); }
</style>
