<template>
  <div class="page ha-entrance-up">
    <el-card class="chat-card">
      <template #header>
        <div class="chat-header">
          <!-- 标题区（设计图）：26×26 图标 + 17px 标题 + 状态徽标；副标题行跟随其下 -->
          <div class="chat-head-main">
            <div class="chat-head-title-row">
              <span class="chat-head-icon">
                <el-icon><MagicStick /></el-icon>
              </span>
              <h2
                class="chat-head-title"
                :title="headTitle"
              >
                {{ headTitle }}
              </h2>
              <span
                v-if="conversation"
                class="chat-head-badge"
              >{{ statusLabel(conversation.status) }}</span>
            </div>
            <div
              v-if="conversation"
              class="chat-head-sub-row"
            >
              <el-icon class="chat-head-sub-icon">
                <Clock />
              </el-icon>
              <span class="chat-head-sub">{{ headSubtitle }}</span>
            </div>
          </div>
          <div class="header-actions">
            <!-- 操作组（设计图）：产物 / 刷新 / 分享 / 更多；模式与状态已上移到左侧标题区 -->
            <!-- 右栏开关：本会话产物。与用户上传附件（输入框上方胶囊条）严格分离。有产物时高亮 + 角标数字。 -->
            <button
              type="button"
              class="artifact-toggle-btn"
              :class="{ 'is-active': artifactPanelOpen, 'has-artifacts': artifactCount > 0 }"
              :title="artifactToggleTitle"
              @click="toggleArtifactPanel"
            >
              <el-icon><Document /></el-icon>
              <span class="artifact-toggle-label">产物</span>
              <span
                v-if="artifactCount"
                class="artifact-toggle-badge"
              >{{ artifactCount }}</span>
            </button>
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
            <el-dropdown
              trigger="click"
              @command="handleMoreAction"
            >
              <el-button
                class="head-more-btn"
                size="small"
                :icon="MoreFilled"
                aria-label="更多操作"
              />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="export">
                    <el-icon><Download /></el-icon>导出全部会话
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-auth="'conversation:delete'"
                    command="clear-all"
                    divided
                  >
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
        <!-- 左栏：会话列表（设计图：新建对话 + 搜索 + 状态 tabs + 分组）。 -->
        <div class="conv-list">
          <el-button
            v-auth="'conversation:add'"
            class="conv-new-btn"
            type="primary"
            size="default"
            @click="startNew"
          >
            <el-icon style="margin-right: 6px">
              <Plus />
            </el-icon>
            新建对话
          </el-button>
          <el-input
            v-model="searchKeyword"
            placeholder="搜索历史对话"
            clearable
            class="conv-search"
          >
            <template #prefix>
              <el-icon style="color: var(--ha-muted)">
                <Search />
              </el-icon>
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
                  <div
                    class="conv-title"
                    :title="conv.title || ''"
                  >
                    {{ displayTitle(conv.title) || '(无标题)' }}
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
                      v-auth="'conversation:delete'"
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
            <div
              class="thread"
              :class="{ 'is-welcome': !detail }"
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
                      <!-- 历史消息的过程区（2026-10-18）：思考 + 检索步骤从 payload 重建，
                           默认折叠、可展开回看（对齐 DeepSeek「已深度思考」/ ChatGPT「Thinking」） -->
                      <div
                        v-if="row.process && (row.process.thinking || (row.process.steps && row.process.steps.length))"
                        class="msg-bubble msg-thinking msg-thinking-history"
                      >
                        <div
                          class="msg-thinking-head"
                          @click="toggleHistoryProcess(row.msg.id)"
                        >
                          <span>{{ historyProcessHeadline(row.process) }}</span>
                          <span class="msg-thinking-toggle">
                            {{ expandedHistoryProcess === String(row.msg.id) ? '收起' : '展开' }}
                          </span>
                        </div>
                        <div
                          v-if="expandedHistoryProcess === String(row.msg.id)"
                          class="msg-thinking-body"
                        >
                          <div
                            v-for="(step, si) in row.process.steps || []"
                            :key="`hstep-${si}`"
                            class="msg-process-step"
                          >
                            {{ step }}
                          </div>
                          <div
                            v-if="row.process.thinking"
                            class="msg-process-thinking"
                          >
                            <MarkdownView
                              compact
                              :content="row.process.thinking"
                            />
                          </div>
                        </div>
                      </div>
                      <div class="msg-bubble">
                        <!-- 用户消息：编辑态显示 textarea（对齐 ChatGPT/DeepSeek 行内编辑），
                             展示态原样渲染；assistant 回复渲染 Markdown -->
                        <template v-if="row.msg.role === 'user'">
                          <div
                            v-if="editingMsgId === String(row.msg.id)"
                            class="msg-edit-wrap"
                          >
                            <!-- 技能 chip：编辑重发不能丢技能——后端按 `[skill:name] ` 前缀
                                 决定是否走技能轮，而技能名只存在于 payload 里，正文看不见。
                                 点 × 表示「这次当普通对话发」，后端会一并清掉 payload.skill。 -->
                            <div
                              v-if="editingSkill"
                              class="msg-edit-skill"
                            >
                              <span
                                class="skill-token skill-token--package"
                                :title="`重新发送仍使用技能 ${editingSkill}`"
                              >
                                <el-icon class="skill-token-icon"><Grid /></el-icon>
                                <span class="skill-token-name">{{ editingSkill }}</span>
                                <button
                                  type="button"
                                  class="skill-token-close"
                                  title="移除技能（这次按普通对话重新发送）"
                                  :disabled="editingSaving"
                                  @click="editingSkill = null"
                                >
                                  <el-icon><Close /></el-icon>
                                </button>
                              </span>
                            </div>
                            <el-input
                              v-model="editingDraft"
                              type="textarea"
                              :rows="3"
                              resize="none"
                              placeholder="编辑消息…"
                              class="msg-edit-input"
                              @keydown.enter.exact.prevent="submitEdit(row.msg)"
                              @keydown.esc="cancelEdit"
                            />
                          </div>
                          <span
                            v-else
                            class="msg-user-text"
                          >
                            <span
                              v-if="row.skill"
                              class="msg-skill-chip"
                              :title="`本条通过技能 ${row.skill} 生成`"
                            >
                              <el-icon class="msg-skill-chip-icon"><Grid /></el-icon>{{ row.skill }}
                            </span>{{ row.intro }}
                          </span>
                        </template>
                        <MarkdownView
                          v-else
                          :content="row.intro"
                        />
                        <!-- 2026-10-27：技能产物已从消息流移入右侧「产物侧栏」——
                            平台生成的附件与用户上传附件分开；这里不再内联卡片。
                            生成后若侧栏未展开，自动展开一次提示用户查看。 -->
                        <div
                          v-if="row.artifact && !artifactPanelOpen"
                          class="msg-artifact-tip"
                        >
                          <span class="msg-artifact-tip-text">已生成文件 <b>{{ row.artifact.fileName }}</b><template v-if="row.artifact.degraded">（兼容布局）</template>，见右侧「产物」栏</span>
                          <button
                            type="button"
                            class="msg-artifact-tip-btn"
                            @click="artifactPanelOpen = true"
                          >
                            查看
                          </button>
                        </div>
                        <!-- 用户消息展示态：hover 出现编辑铅笔（对齐 ChatGPT/DeepSeek 交互） -->
                        <button
                          v-if="row.msg.role === 'user' && editingMsgId !== String(row.msg.id)"
                          type="button"
                          class="msg-action-btn msg-edit-hover"
                          title="编辑消息"
                          :disabled="activeSending || finalizing || editingSaving"
                          @click="startEdit(row.msg)"
                        >
                          <el-icon><EditPen /></el-icon>
                        </button>
                      </div>
                      <!-- 用户消息：编辑操作条（仅编辑态显示；展示态操作条在 msg-actions） -->
                      <div
                        v-if="row.msg.role === 'user' && editingMsgId === String(row.msg.id)"
                        class="msg-actions msg-edit-actions"
                      >
                        <button
                          type="button"
                          class="msg-action-btn msg-edit-cancel"
                          title="取消编辑 (Esc)"
                          :disabled="editingSaving"
                          @click="cancelEdit"
                        >
                          取消
                        </button>
                        <el-button
                          size="small"
                          type="primary"
                          :loading="editingSaving"
                          :disabled="!editingDraft.trim()"
                          @click="submitEdit(row.msg)"
                        >
                          重新发送
                        </el-button>
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
                          v-auth="'conversation:regenerate'"
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
                      :disabled="activeSending || finalizing"
                      :loading="activeSending"
                      @submit="handleStructuredSubmit"
                    />
                  </div>
                </div>
              </template>
              <!-- 新建对话空态（设计图）：品牌图标 + 主副标题 + 能力卡片 + 快捷指令。
                   卡片全部映射到真实能力（pickSkill / pickFiles），不做无动作的假入口。 -->
              <div
                v-else
                class="chat-welcome"
              >
                <div class="cw-main">
                  <div class="cw-hero">
                    <span class="cw-hero-icon">
                      <el-icon><MagicStick /></el-icon>
                    </span>
                    <h3 class="cw-hero-title">
                      开始一段新对话
                    </h3>
                    <p class="cw-hero-desc">
                      描述你想做的事，或直接向 AI 助手提问——它会解答疑问、帮你梳理思路。
                    </p>
                  </div>

                  <div
                    v-if="packageSkillsReady"
                    class="cw-cards"
                  >
                    <button
                      v-for="card in welcomeCards"
                      :key="card.key"
                      type="button"
                      class="cw-card"
                      :disabled="skillDisabled"
                      @click="card.run()"
                    >
                      <span
                        class="cw-card-icon"
                        :class="`is-${card.tone}`"
                      >
                        <el-icon><component :is="card.icon" /></el-icon>
                      </span>
                      <span class="cw-card-title">{{ card.title }}</span>
                      <span class="cw-card-desc">{{ card.desc }}</span>
                    </button>
                  </div>
                  <!-- 技能清单未就绪：等量骨架占位，避免卡片数量先 3 后 4 的跳变 -->
                  <div
                    v-else
                    class="cw-cards cw-cards-skeleton"
                    aria-hidden="true"
                  >
                    <span
                      v-for="i in 4"
                      :key="i"
                      class="cw-card-skeleton"
                    />
                  </div>
                </div>

                <div class="cw-cmds">
                  <button
                    v-for="cmd in welcomeCommands"
                    :key="cmd.key"
                    type="button"
                    class="cw-cmd"
                    :class="{ 'is-on': cmd.active() }"
                    @click="cmd.run()"
                  >
                    <el-icon class="cw-cmd-icon">
                      <component :is="cmd.icon" />
                    </el-icon>
                    <span>{{ cmd.label }}</span>
                  </button>
                </div>
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
                      v-auth="'conversation:retry'"
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
                v-if="activeSending && pendingText"
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
                v-if="activeSending"
                class="msg-row from-assistant"
              >
                <div class="msg-avatar">
                  AI
                </div>
                <div class="msg-col">
                  <!-- 过程区（2026-10-18）：联网检索等平台步骤 + 推理模型思考内容，
                       按到达顺序混排；正文开始后自动折叠为一行可展开（对齐 DeepSeek/ChatGPT） -->
                  <div
                    v-if="currentStream.processSteps.length || currentStream.thinkingText"
                    class="msg-bubble msg-thinking"
                  >
                    <div
                      class="msg-thinking-head"
                      @click="currentStream.thinkingExpanded = !currentStream.thinkingExpanded"
                    >
                      <el-icon
                        v-if="isProcessRunning"
                        class="is-loading"
                      >
                        <Loading />
                      </el-icon>
                      <span>{{ processHeadline }}</span>
                      <span class="msg-thinking-toggle">
                        {{ currentStream.thinkingExpanded || isProcessRunning ? '收起' : '展开' }}
                      </span>
                    </div>
                    <div
                      v-if="currentStream.thinkingExpanded || isProcessRunning"
                      class="msg-thinking-body"
                    >
                      <div
                        v-for="(step, si) in currentStream.processSteps"
                        :key="`step-${si}`"
                        class="msg-process-step"
                      >
                        {{ step }}
                      </div>
                      <div
                        v-if="currentStream.thinkingText"
                        class="msg-process-thinking"
                      >
                        <MarkdownView
                          compact
                          :content="currentStream.thinkingText"
                        />
                      </div>
                    </div>
                  </div>
                  <!-- S1 流式回复：token 增量渲染 Markdown；尚未产出 token 时保持思考中占位 -->
                  <div
                    v-if="currentStream.streamText"
                    class="msg-bubble msg-streaming"
                  >
                    <MarkdownView :content="currentStream.streamText" />
                  </div>
                  <div
                    v-else-if="!currentStream.thinkingText && !currentStream.processSteps.length"
                    class="msg-bubble msg-loading"
                  >
                    <el-icon class="is-loading">
                      <Loading />
                    </el-icon>
                    思考中…
                  </div>
                </div>
              </div>

              <!-- SKX-13：技能执行进行中占位（提交即返回，后台跑；阶段文案随轮询更新） -->
              <div
                v-if="skillRunId"
                class="msg-row from-assistant"
              >
                <div class="msg-avatar">
                  AI
                </div>
                <div class="msg-col">
                  <div class="msg-bubble msg-loading">
                    <el-icon class="is-loading">
                      <Loading />
                    </el-icon>
                    <span>{{ skillRunPhaseDetail || '正在生成…' }}</span>
                    <!-- A2：取消入口。取消是协作式（后端置 CANCELLED，worker 在阶段边界停），
                         但用户不必等那一个阶段——这里立即停轮询、清占位。 -->
                    <el-button
                      class="skill-cancel-btn"
                      size="small"
                      type="danger"
                      plain
                      :disabled="skillCancelling"
                      @click="requestCancelSkill"
                    >
                      {{ skillCancelling ? '正在停止…' : '停止生成' }}
                    </el-button>
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
                      v-auth="'conversation:finalize'"
                      type="primary"
                      :loading="finalizing"
                      :disabled="activeSending || finalizing"
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
                      @click="router.push({ path: '/tasks', query: { review: String(producedTaskId) } })"
                    >
                      查看任务
                    </el-button>
                    <template v-else>
                      <el-button
                        v-auth="'conversation:regenerate'"
                        type="primary"
                        :loading="finalizing"
                        :disabled="activeSending || finalizing"
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
                <div
                  class="composer-area"
                  :class="{ 'is-dragover': dragOver }"
                  @dragover.prevent="onComposerDragOver"
                  @dragleave.prevent="onComposerDragLeave"
                  @drop.prevent="onComposerDrop"
                >
                  <!-- 已选技能：以胶囊形式固定在光标前（对齐 WorkBuddy），选中 ≠ 立即发送，
                       用户在同一输入框里补完需求再 Enter；发送时才翻译成后端既有斜杠协议。 -->
                  <!-- 会话附件（V121）：上传后以胶囊列在输入框上方；解析态必须可见——
                       SKIPPED 画成「加载中」会让用户一直等一个永远不会好的文件。 -->
                  <div
                    v-if="userAttachments.length || uploading"
                    class="attachment-bar"
                  >
                    <!-- ATT-3：点击胶囊切换「本轮是否参与」；未参与的不注入正文也不进检索池 -->
                    <span
                      v-for="item in userAttachments"
                      :key="item.id"
                      class="attachment-chip"
                      :class="attachmentChipClass(item)"
                      :title="attachmentChipTitle(item)"
                      @click="toggleAttachmentParticipation(item)"
                    >
                      <!-- 实心圆形勾 = 参与本轮；未参与回落文件图标（勾只在选中时出现，语义不反） -->
                      <el-icon class="attachment-icon">
                        <CircleCheckFilled v-if="!isAttachmentExcluded(item)" />
                        <Document v-else />
                      </el-icon>
                      <span class="attachment-name">{{ item.fileName }}</span>
                      <!-- MA-8：命中疑似敏感内容时在胶囊上打标（悬停看明细；不阻断、不弹窗） -->
                      <el-icon
                        v-if="hasSensitiveHit(item)"
                        class="attachment-sensitive-flag"
                        :title="`检测到 ${item.sensitiveScanHitCount} 处疑似敏感信息（${sensitiveRulesText(item)}）`"
                      >
                        <WarningFilled />
                      </el-icon>
                      <span class="attachment-state">{{ attachmentStateText(item) }}</span>
                      <button
                        type="button"
                        class="attachment-close"
                        title="移除附件"
                        @click.stop="removeAttachment(item.id)"
                      >
                        <el-icon><Close /></el-icon>
                      </button>
                    </span>
                    <span
                      v-if="uploading"
                      class="attachment-chip attachment-chip--uploading"
                    >
                      <el-icon class="attachment-icon is-loading"><Loading /></el-icon>
                      <span class="attachment-name">上传中…</span>
                    </span>
                  </div>
                  <!-- MA-8：敏感命中的汇总提示条。
                       为什么单独一条而不是逐个胶囊挂 tooltip：
                       「文件里有密钥」这件事的价值在于**发送前**让用户看见，
                       tooltip 要悬停才出现，多数人不会去看；但也不能做成阻塞弹窗
                       （log-only 阶段误报率未知，弹窗会打断正常业务）。 -->
                  <div
                    v-if="sensitiveHitSummary.count > 0"
                    class="attachment-sensitive-tip"
                    role="status"
                  >
                    <el-icon><WarningFilled /></el-icon>
                    <span class="attachment-sensitive-text">
                      检测到{{ sensitiveHitSummary.fileCount }} 份附件共
                      {{ sensitiveHitSummary.count }} 处疑似敏感信息（{{
                        sensitiveHitSummary.rules
                      }}），内容仍会正常发送
                    </span>
                  </div>
                  <div class="composer-input-row">
                    <transition name="skill-token-fade">
                      <span
                        v-if="activeSkill"
                        class="skill-token"
                        :class="activePackageName ? 'skill-token--package' : `skill-token--${activeSkill}`"
                        :title="activeSkillHint"
                      >
                        <el-icon class="skill-token-icon">
                          <EditPen v-if="activeSkill === 'planner'" />
                          <Grid v-else-if="activePackageName" />
                          <MagicStick v-else />
                        </el-icon>
                        <span class="skill-token-name">{{ activeSkillLabel }}</span>
                        <button
                          type="button"
                          class="skill-token-close"
                          title="移除技能（输入为空时按退格亦可）"
                          @click="clearSkill"
                        >
                          <el-icon><Close /></el-icon>
                        </button>
                      </span>
                    </transition>
                    <el-input
                      ref="composerRef"
                      v-model="input"
                      type="textarea"
                      :rows="4"
                      resize="none"
                      :disabled="activeSending || finalizing"
                      :placeholder="inputPlaceholder"
                      @keydown.enter.exact.prevent="handleSend"
                      @keydown="onComposerKeydown"
                      @paste="onComposerPaste"
                    />
                  </div>
                  <div class="input-actions">
                    <div class="input-actions-left">
                      <!-- 「＋」能力入口（2026-10-22，对齐 WorkBuddy 交互）：一个 + 收纳可选能力，
                           当前只有「技能」一组；技能语义等价于旧斜杠命令 /planner 与 /task，
                           语法不再外露，命令仅作高级别名保留。 -->
                      <el-popover
                        v-model:visible="plusMenuVisible"
                        placement="top-start"
                        :width="300"
                        trigger="click"
                        popper-class="plus-menu-popover"
                      >
                        <template #reference>
                          <button
                            type="button"
                            class="plus-btn"
                            :disabled="skillDisabled"
                            title="选择技能"
                          >
                            <el-icon><Plus /></el-icon>
                          </button>
                        </template>
                        <div class="plus-menu">
                          <div class="plus-menu-group-title">
                            技能
                          </div>
                          <button
                            type="button"
                            class="plus-menu-item"
                            :class="{ 'is-active': activeSkill === 'planner' }"
                            @click="pickSkill('planner')"
                          >
                            <span class="plus-menu-icon"><el-icon><EditPen /></el-icon></span>
                            <span class="plus-menu-text">
                              <span class="plus-menu-name">方案整理</span>
                              <span class="plus-menu-desc">逐轮问清边界与取舍，再产出可执行方案</span>
                            </span>
                            <span
                              v-if="activeSkill === 'planner'"
                              class="plus-menu-check"
                            >
                              <el-icon><Check /></el-icon>
                            </span>
                          </button>
                          <button
                            type="button"
                            class="plus-menu-item"
                            :class="{ 'is-active': activeSkill === 'task' }"
                            @click="pickSkill('task')"
                          >
                            <span class="plus-menu-icon"><el-icon><MagicStick /></el-icon></span>
                            <span class="plus-menu-text">
                              <span class="plus-menu-name">直接拆解</span>
                              <span class="plus-menu-desc">跳过澄清，按当前描述直接产出方案并建任务</span>
                            </span>
                            <span
                              v-if="activeSkill === 'task'"
                              class="plus-menu-check"
                            >
                              <el-icon><Check /></el-icon>
                            </span>
                          </button>
                          <!-- 产出型技能（2026-10-26）：来自平台技能目录（/api/skills/catalog），
                               只列「声明了 internal_run_skill」的包——它们是能在沙箱里真跑出产物的技能
                               （archify 出 HTML、biz-process-lifecycle 出状态机图）；eng-* 规范类不在此列。 -->
                          <button
                            v-for="pkg in packageSkills"
                            :key="pkg.name"
                            type="button"
                            class="plus-menu-item"
                            :class="{ 'is-active': activeSkill === `pkg:${pkg.name}` }"
                            @click="pickSkill(`pkg:${pkg.name}`)"
                          >
                            <span class="plus-menu-icon"><el-icon><Grid /></el-icon></span>
                            <span class="plus-menu-text">
                              <span class="plus-menu-name">{{ pkg.name }}</span>
                              <span class="plus-menu-desc">{{ shortSkillDesc(pkg.description) }}</span>
                            </span>
                            <span
                              v-if="activeSkill === `pkg:${pkg.name}`"
                              class="plus-menu-check"
                            >
                              <el-icon><Check /></el-icon>
                            </span>
                          </button>
                          <div class="plus-menu-group-title">
                            文件
                          </div>
                          <button
                            type="button"
                            class="plus-menu-item"
                            @click="pickFiles"
                          >
                            <span class="plus-menu-icon"><el-icon><Document /></el-icon></span>
                            <span class="plus-menu-text">
                              <span class="plus-menu-name">添加文件</span>
                              <span class="plus-menu-desc">PDF / Word / 文本 / 代码；单文件 20MB，单次 5 个</span>
                            </span>
                          </button>
                        </div>
                      </el-popover>
                      <input
                        ref="fileInputRef"
                        type="file"
                        multiple
                        class="attachment-input"
                        accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.json,.log,.html,.htm,.png,.jpg,.jpeg,.gif,.webp,.bmp"
                        @change="onFileInputChange"
                      >
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
                      <!-- 放弃会话：会话级次要动作，收到左侧工具栏末尾，右侧只留输出类图标（对齐工作台） -->
                      <button
                        v-if="activeId && conversation?.status === 'ACTIVE'"
                        v-auth="'conversation:abandon'"
                        type="button"
                        class="composer-text-btn is-danger"
                        :disabled="activeSending || finalizing"
                        @click="handleAbandon"
                      >
                        放弃会话
                      </button>
                    </div>
                    <div class="input-actions-right">
                      <!-- PA-12 上下文窗口：圆形占用环置于输入框内；点击展开五项明细 + 手动压缩 -->
                      <el-popover
                        placement="top-end"
                        :width="300"
                        trigger="click"
                        @show="loadContextUsage"
                      >
                        <template #reference>
                          <button
                            type="button"
                            class="ctx-ring"
                            :class="{ 'is-warn': contextRatioPct >= 80, 'is-empty': !contextUsage }"
                            :title="ctxRingTitle"
                            aria-label="上下文窗口占用"
                          >
                            <svg
                              class="ctx-ring-svg"
                              viewBox="0 0 32 32"
                              aria-hidden="true"
                            >
                              <circle
                                class="ctx-ring-track"
                                cx="16"
                                cy="16"
                                r="13"
                              />
                              <circle
                                class="ctx-ring-fill"
                                cx="16"
                                cy="16"
                                r="13"
                                :stroke-dasharray="ctxRingCircumference"
                                :stroke-dashoffset="ctxRingOffset"
                              />
                            </svg>
                            <el-icon
                              v-if="!contextUsage"
                              class="ctx-ring-icon"
                            >
                              <Histogram />
                            </el-icon>
                            <span
                              v-else
                              class="ctx-ring-label"
                            >{{ contextRatioPct }}%</span>
                          </button>
                        </template>
                        <div class="ctx-panel">
                          <div class="ctx-head">
                            <span>上下文窗口</span>
                            <span class="ctx-ratio">{{ contextUsage ? contextRatioPct : 0 }}%</span>
                          </div>
                          <div
                            v-if="contextUsage"
                            class="ctx-bar"
                          >
                            <div
                              class="ctx-bar-fill"
                              :style="{ width: contextRatioPct + '%' }"
                            />
                          </div>
                          <template v-if="contextUsage">
                            <div class="ctx-row">
                              <span>系统提示词</span><span>{{ contextUsage.systemPromptTokens }}</span>
                            </div>
                            <div class="ctx-row">
                              <span>系统工具</span><span>{{ contextUsage.toolTokens }}</span>
                            </div>
                            <div class="ctx-row">
                              <span>Skill</span><span>{{ contextUsage.skillTokens }}</span>
                            </div>
                            <div class="ctx-row">
                              <span>消息</span><span>{{ contextUsage.messageTokens }}</span>
                            </div>
                            <div class="ctx-row">
                              <span>其他</span><span>{{ contextUsage.otherTokens }}</span>
                            </div>
                            <div class="ctx-row ctx-total">
                              <span>合计 / 窗口</span><span>{{ contextUsage.totalTokens }} / {{ contextUsage.contextSize }}</span>
                            </div>
                            <div class="ctx-meta">
                              已压缩到 seq {{ contextUsage.compactedUntilSeq }} · 摘要 v{{ contextUsage.summaryVersion }}
                            </div>
                          </template>
                          <div
                            v-else
                            class="ctx-empty"
                          >
                            打开会话后可查看占用
                          </div>
                          <el-button
                            size="small"
                            type="primary"
                            plain
                            class="ctx-compact-btn"
                            :loading="contextCompacting"
                            :disabled="!activeId"
                            @click="compactContext"
                          >
                            压缩上下文
                          </el-button>
                        </div>
                      </el-popover>
                      <!-- 输入优化（工作台口径：28×28 图标按钮；调 LLM 改写为结构化表达，仅预览、不自动发送） -->
                      <button
                        type="button"
                        class="composer-icon-btn"
                        title="优化输出（把当前输入改写为更清晰的结构化表达，仅预览）"
                        :disabled="!input.trim() || activeSending || finalizing"
                        @click="handleEnhanceInput"
                      >
                        <el-icon :class="{ 'is-loading': enhancing }">
                          <MagicStick />
                        </el-icon>
                      </button>
                      <!-- 语音输入：与工作台一致，暂未开放，置灰不可点 -->
                      <button
                        type="button"
                        class="composer-icon-btn is-mic"
                        title="语音输入（暂未开放）"
                        disabled
                      >
                        <el-icon><Microphone /></el-icon>
                      </button>
                      <!-- 发送 / 停止 复用同一按钮（DeepSeek/Kimi 风格，2026-10-19）：
                           流式生成中按钮变红色圆钮（停止生成），点击中止生成；
                           同步路径（/task 等）无法中止 → 转圈禁用 -->
                      <button
                        v-if="activeSending"
                        type="button"
                        class="composer-send-btn"
                        :class="{ 'is-stop': currentStream.streamAbort, 'is-busy': !currentStream.streamAbort }"
                        :title="currentStream.streamAbort ? '停止生成，当前回复将不会保存' : '发送中'"
                        :disabled="!currentStream.streamAbort || finalizing"
                        @click="currentStream.streamAbort && stopGenerating()"
                      >
                        <el-icon v-if="currentStream.streamAbort">
                          <CircleClose />
                        </el-icon>
                        <el-icon
                          v-else
                          class="is-loading"
                        >
                          <Loading />
                        </el-icon>
                      </button>
                      <button
                        v-else
                        v-auth="'conversation:send'"
                        type="button"
                        class="composer-send-btn"
                        title="发送"
                        :disabled="!input.trim() || finalizing"
                        @click="handleSend"
                      >
                        <el-icon><ArrowUp /></el-icon>
                      </button>
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
              <!-- 页脚：与 Kimi / DeepSeek 网页版同口径，输入框正下方居中一行 -->
              <div class="chat-disclaimer">
                内容由 AI 生成，请仔细甄别
              </div>
            </div>
          </div>
        </div>
        <!-- 右侧可收缩面板：本会话的平台生成产物。与用户上传附件（输入框上方胶囊条）严格分离。
             展开 = 列表 + 内嵌预览，收起 = 仅保留顶部开关按钮。 -->
        <transition name="artifact-panel">
          <aside
            v-show="artifactPanelOpen"
            class="artifact-sidebar"
          >
            <div class="artifact-sidebar-head">
              <span class="artifact-sidebar-title">产物</span>
              <div class="artifact-sidebar-head-right">
                <span class="artifact-sidebar-count">{{ conversationArtifacts.length }} 个</span>
                <button
                  type="button"
                  class="artifact-sidebar-close"
                  title="收起产物栏"
                  @click="closeArtifactPanel"
                >
                  <el-icon><Close /></el-icon>
                </button>
              </div>
            </div>
            <div class="artifact-sidebar-body">
              <div class="artifact-list">
                <template v-if="conversationArtifacts.length">
                  <button
                    v-for="item in conversationArtifacts"
                    :key="item.artifact.attachmentId"
                    type="button"
                    class="artifact-item"
                    :class="{ 'is-current': artifactPreview?.fileName === item.artifact.fileName }"
                    @click="previewArtifactInPanel(item)"
                  >
                    <span class="artifact-item-icon"><el-icon><Document /></el-icon></span>
                    <span class="artifact-item-meta">
                      <span class="artifact-item-name">{{ item.artifact.fileName }}</span>
                      <span class="artifact-item-sub">
                        {{ artifactSizeLabel(item.artifact.fileSize) }}<template v-if="item.artifact.chartType"> · {{ item.artifact.chartType }}</template>
                        <span
                          v-if="item.artifact.degraded"
                          class="artifact-item-degraded"
                          :title="item.artifact.degradeReason || '严格布局门未通过，已用兼容布局产出'"
                        > · 兼容布局</span>
                      </span>
                    </span>
                    <span class="artifact-item-actions">
                      <!-- 2026-10-27：打开/下载必须走 blob 通道 —— 裸 <a href> 导航不带
                             X-Admin-Token 会被 401（浏览器导航请求无法携带自定义头）。
                             blob 经 request 拦截器补鉴权头，再 createObjectURL 交给新标签页 / 落盘。 -->
                      <button
                        type="button"
                        class="artifact-item-btn"
                        title="新标签页打开"
                        @click.stop="openArtifactInNewTab(item)"
                      >
                        <el-icon><TopRight /></el-icon>
                      </button>
                      <button
                        type="button"
                        class="artifact-item-btn"
                        title="下载"
                        @click.stop="downloadArtifact(item)"
                      >
                        <el-icon><Download /></el-icon>
                      </button>
                    </span>
                  </button>
                </template>
                <el-empty
                  v-else
                  description="暂无产物"
                  :image-size="48"
                />
              </div>
            </div>
            <!-- 内嵌预览区：点击产物条目后在此渲染（图片/PDF 走 iframe blob，文本/源码走 pre） -->
            <div
              v-if="artifactPreview"
              class="artifact-preview"
            >
              <div class="artifact-preview-head">
                <span class="artifact-preview-name">{{ artifactPreview.fileName }}</span>
                <button
                  type="button"
                  class="artifact-preview-close"
                  title="关闭预览"
                  @click="closeArtifactPreview"
                >
                  <el-icon><Close /></el-icon>
                </button>
              </div>
              <div
                v-loading="artifactPreviewLoading"
                class="artifact-preview-body"
              >
                <iframe
                  v-if="artifactPreview.mode === 'iframe' && artifactPreview.blobUrl"
                  :src="artifactPreview.blobUrl"
                  :sandbox="artifactPreview.isHtml ? 'allow-scripts' : undefined"
                  class="artifact-preview-iframe"
                />
                <pre
                  v-else-if="artifactPreview.mode === 'text' && artifactPreview.textContent"
                  class="artifact-preview-pre"
                >{{ artifactPreview.textContent }}</pre>
                <el-empty
                  v-else
                  description="暂无预览内容"
                  :image-size="40"
                />
              </div>
            </div>
          </aside>
        </transition>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Connection, Delete, MagicStick, CopyDocument, CaretTop, CaretBottom, Refresh, MoreFilled, Plus, Search, Share, Download, Document, CircleClose, EditPen, Close, Check, CircleCheckFilled, Grid, TopRight, Histogram, Clock, Microphone, ArrowUp, WarningFilled } from '@element-plus/icons-vue'
import { clarifyApi } from '@/api/clarify'
import { promptEnhanceApi } from '@/api/promptEnhance'
import { taskApi } from '@/api/task'
import { skillApi, type ChatSkillOption } from '@/api/skill'
import { streamCreateConversation, streamSendConversation } from '@/api/chatStream'
import { fmtTime } from '@/utils/tableConfig'
import { saveBlobResponse } from '@/utils/download'
import StructuredQuestionCard from './StructuredQuestionCard.vue'
import WebSearchBar from './WebSearchBar.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import type { ChatAttachmentItem, ClarifyArtifactRef, ClarifyAssistantPayload, ClarifyConversationDetail, ClarifySelection, ContextUsage, PlannerOption, RequirementConversation, RequirementConversationStatus, RequirementMessage, LongId, RoundProcessTrace, WebSearchTrace } from '@/types'
import { sameId } from '@/utils/id'

const route = useRoute()
const router = useRouter()

const conversations = ref<RequirementConversation[]>([])
const activeId = ref<LongId | null>(null)
const detail = ref<ClarifyConversationDetail | null>(null)
const conversation = computed(() => detail.value?.conversation ?? null)
/* 头部标题区（设计图）：标题 = 会话标题（去掉 [skill:xxx] 前缀），未选会话时回落「新对话」 */
const headTitle = computed(() => {
  const t = displayTitle(conversation.value?.title)
  if (t) return t
  return conversation.value ? '(无标题)' : '新建对话'
})
/* 头部副标题（设计图）：模式 · 轮次 · 创建时间；数据全部取自会话实体，不造字段 */
const headSubtitle = computed(() => {
  const c = conversation.value
  if (!c) return ''
  const mode = c.mode === 'CHAT' ? '自由对话' : '方案澄清'
  const rounds = c.roundCount > 0 ? `第 ${c.roundCount} 轮` : '尚未开始'
  return `${mode} · ${rounds} · 创建于 ${fmtTime(c.createTime)}`
})
// 设计图：左栏顶部 + 搜索框 + 状态 tabs
const searchKeyword = ref('')
const statusTab = ref<'all' | 'ACTIVE' | 'FINALIZED'>('all')
// 会话关联任务是否仍存在（仅 detail 返回）；FINALIZED 且为 false 时展示「重新生成」
const taskExists = computed(() => detail.value?.taskExists === true)
// 本会话「产出的任务」：派生会话（草案/执行期派生）的 taskId 是父任务 X，产出任务在
// derivedTaskId（V150）。统一走这里，「查看任务」才不会被带到父任务上。
const producedTaskId = computed(() => conversation.value?.derivedTaskId ?? conversation.value?.taskId ?? null)

// V39 双模式：CHAT 自由对话 / CLARIFY 方案澄清（mode 为 null 的老数据视为 CLARIFY）
const isChatMode = computed(() => conversation.value?.mode === 'CHAT')

// 输入框占位文案按会话模式区分（三态口径统一：新建 / 自由对话 / 方案澄清）。
// 斜杠语法不再出现在文案里——能力入口收进输入框左下角「＋」菜单的「技能」组，
// 命令本身仍是同一套后端协议（/plan 与 /task），只作为高级别名保留、不再对外宣传。
const inputPlaceholder = computed(() => {
  // 已挂载技能：占位直接说明「补需求 + Enter 会发生什么」，用户不用猜胶囊是什么意思
  if (activeSkill.value === 'planner') return '写一句你想梳理的事，Enter 进入方案整理'
  if (activeSkill.value === 'task') return '写一句需求，Enter 直接拆解并建任务'
  if (activeSkill.value?.startsWith('pkg:')) return '写一句要画什么（越具体越准），Enter 直接生成文件，产物可在对话里下载'
  if (activeId.value == null) {
    return '描述你想做的事，或直接提问；点左下角「＋」选技能可直接建任务，Enter 发送'
  }
  return isChatMode.value
    ? '和 AI 助手自由对话；想成体系地梳理，点左下角「＋」→ 技能，Enter 发送'
    : '继续补充需求，Enter 发送；「＋」→ 技能 里的「直接拆解」此时仍可用'
})

// V40.2 计划类斜杠命令（/planner|/plan）：显式进入方案澄清模式（CLARIFY）；可带附加文本（落库进上下文后再切）。
// §6.169 /task 直达拆解命令不在此列：后端 doRound 按前缀分流终稿直出轮，前端原样发送不拦截
const PLANNER_COMMAND_RE = /^(?:\/planner|\/plan)(?:\s+([\s\S]+))?$/i

const input = ref('')
// 待发用户气泡文本：按会话隔离（键同 sendingSet，null/'' = 新建占位），读写见 setPendingText
const pendingTexts = ref<Record<string, string>>({})
// S1 发送态（RC-5）：改为「每会话」布尔表，而非单份全局 sending —— 否则一个会话
// 在跑会把「切到别的会话发送」也拦掉。写入按目标会话 id 合并（只动自己那位）。
const sendingSet = ref<Record<string, boolean>>({})
const finalizing = ref(false)
const streamEl = ref<HTMLElement | null>(null)

// 技能入口可用性：生成中 / 定稿中 / 会话已非 ACTIVE 时禁用（避免点了没反应）
const skillDisabled = computed(() => activeSending.value
  || finalizing.value
  || (activeId.value != null && conversation.value?.status !== 'ACTIVE'))

// ── SKX-13：技能执行异步化（提交即返回 + 后台跑 + 轮询进度）────────────────
// 技能执行实测一次 377s（LLM 两轮 + 注入 + 三段门禁 + 回读 + 落库），
// 挂在 HTTP 线程上会「前端超时 + 服务端刷一屏 Broken pipe」，
// 故后端改为提交即返回 runId，这里轮询进度，终态再拉一次 detail 收产物。
const skillRunId = ref<LongId | null>(null)
const skillRunStatus = ref<string>('')
const skillRunPhaseDetail = ref<string>('')
/** 是否正在向服务端确认「本会话有无后台技能在执行」（C 修复：未确认完前不判「回复失败」）。 */
const skillCheckPending = ref(false)
/** 取消请求进行中（防重复点击；成功后气泡会整体消失）。 */
const skillCancelling = ref(false)
let skillPollTimer: number | null = null
let skillPollDelay = 1500
/** 本次轮询归属的会话：切到别的会话就停轮询，避免把旧会话的结果塞进新会话 */
let skillPollConversationId: LongId | null = null

/** 停止技能轮询（切换会话 / 组件卸载 / 终态都调它）。 */
function stopSkillPolling() {
  if (skillPollTimer != null) {
    window.clearTimeout(skillPollTimer)
    skillPollTimer = null
  }
  skillPollConversationId = null
}

/**
 * 开始轮询一次技能执行。
 *
 * <p>轮询失败不打断：退避重试（后端可能正在重启 / 网络抖动），
 * 但连续失败也不该无限转圈 —— 退避上限 10s，用户总能看见「还在跑」。</p>
 */
function startSkillPolling(runId: LongId, conversationId: LongId | null) {
  stopSkillPolling()
  skillRunId.value = runId
  skillRunStatus.value = 'RUNNING'
  skillRunPhaseDetail.value = '正在生成…'
  skillCancelling.value = false
  skillPollConversationId = conversationId
  skillPollDelay = 1500
  const tick = async () => {
    if (skillRunId.value !== runId) return
    // 已切到别的会话：这次轮询没有意义，停掉（别把旧结果塞进新会话）
    if (skillPollConversationId != null && activeId.value != null
      && String(activeId.value) !== String(skillPollConversationId)) {
      stopSkillPolling()
      skillRunId.value = null
      skillRunPhaseDetail.value = ''
      return
    }
    try {
      const st = await clarifyApi.skillRun(runId)
      if (skillRunId.value !== runId) return
      // 记录已不存在（库被重置 / 行被清理）：继续轮询只会空转，且 st.status 会抛错被
      // 当成「网络抖动」无限退避重试 —— 气泡就永远转下去。这里直接收尾。
      if (!st) {
        stopSkillPolling()
        skillRunId.value = null
        skillRunPhaseDetail.value = ''
        return
      }
      skillRunStatus.value = st.status
      if (st.errorMessage) skillRunPhaseDetail.value = st.errorMessage
      else if (st.phaseDetail) skillRunPhaseDetail.value = st.phaseDetail
      if (st.terminal) {
        stopSkillPolling()
        skillRunId.value = null
        skillRunPhaseDetail.value = ''
        // 终态：拉一次 detail，把产物消息 / 失败消息收进对话
        try {
          if (activeId.value != null) detail.value = await clarifyApi.detail(activeId.value)
        } catch { /* 拦截器已弹错 */ }
        loadList()
        scrollToBottom()
        return
      }
    } catch {
      // 轮询失败：退避后重试，不打断用户
    }
    skillPollDelay = Math.min(Math.round(skillPollDelay * 1.5), 10_000)
    skillPollTimer = window.setTimeout(tick, skillPollDelay)
  }
  skillPollTimer = window.setTimeout(tick, skillPollDelay)
}

/** 会话详情里带 runId ⇒ 本轮是技能执行且尚未出结果，启动轮询。 */
function maybeStartSkillPolling(result: ClarifyConversationDetail | null | undefined) {
  if (result?.skillRunId != null) {
    startSkillPolling(result.skillRunId, result.conversation?.id ?? activeId.value)
  }
}

/**
 * 用户点「停止生成」：协作式取消。
 *
 * <p>后端先置 CANCELLED，worker 在<b>下一个阶段边界</b>才停 ⇒ 服务端可能还要多跑一个阶段（1~2s）。
 * 但用户点的是「我不想等了」，不该再盯着转圈：这里立即停轮询、清占位，再拉一次详情补齐已有消息。
 * 取消不落「生成失败」消息（ADR-007：用户自己叫停的，再报一次失败是噪音），所以只给一个轻提示。</p>
 */
async function requestCancelSkill() {
  const runId = skillRunId.value
  if (runId == null || skillCancelling.value) return
  skillCancelling.value = true
  try {
    await clarifyApi.cancelSkillRun(runId)
    stopSkillPolling()
    skillRunId.value = null
    skillRunPhaseDetail.value = ''
    ElMessage.info('已停止生成')
    try {
      if (activeId.value != null) detail.value = await clarifyApi.detail(activeId.value)
    } catch { /* 拦截器已弹错 */ }
    loadList()
  } catch {
    // 拦截器已弹错：保持轮询，用户可以再点一次
  } finally {
    skillCancelling.value = false
  }
}

/**
 * 打开会话时恢复进度（A3）。
 *
 * <p>runId 只存在于提交响应里，刷新页面/换设备就丢了 —— 后台还在跑，界面上却没有任何痕迹；
 * 更糟的是这时候用户再发一条技能会被互斥拒掉（「已有技能在执行」），看起来就是卡死。
 * 故打开会话后问一次「本会话有没有活跃执行」，有就接着轮询。</p>
 */
async function resumeActiveSkillRun(conversationId: LongId) {
  // C 修复：确认前先把 `skillCheckPending` 置位，让 canRetry 不在这个窗口误判「回复失败」
  //（否则 F5 后 skillRunId 尚未接回，一条无 assistant 回复的 user 消息会被当成失败）
  skillCheckPending.value = true
  try {
    const active = await clarifyApi.activeSkillRun(conversationId)
    // 请求期间用户可能已切走：切走了就别再挂气泡
    if (!active || String(activeId.value) !== String(conversationId)) return
    startSkillPolling(active.id, conversationId)
    // 首屏直接显示服务端已推进到的阶段，不等第一次轮询（1.5s）
    if (active.phaseDetail) skillRunPhaseDetail.value = active.phaseDetail
  } catch {
    // 静默：恢复进度是增强能力，失败不该影响正常浏览会话
  } finally {
    skillCheckPending.value = false
  }
}

// 「＋」能力菜单展开态（点技能项后先收起，再挂载到输入框）
const plusMenuVisible = ref(false)
// 首次展开菜单时拉一次对话技能清单（失败静默降级为只剩流程技能）
watch(plusMenuVisible, (visible) => {
  if (visible) void loadPackageSkills()
})

// 已挂载的技能（对齐 WorkBuddy「技能 chip + 光标」）：
// 选技能 ≠ 立即发送——挂成输入框里的胶囊，用户补完需求再 Enter；
// 发送时才翻译成后端既有协议（planner → /planner，task → /task 前缀），语法始终不外露。
// 新增产出型技能（`pkg:<技能包名>`），发送时翻译成 `[skill:<name>] ` 前缀，
// 后端据此把技能写进任务 required_skills（子任务里真跑技能 CLI 并导出可下载产物）。
const activeSkill = ref<string | null>(null)
const composerRef = ref<any>(null)

/**
 * 菜单里可选的「产出型技能」：服务端只下发「能独立出产物」的健康技能包
 * （`internal_run_skill` ∧ 无依赖）——archify 出 HTML；依赖别人的规范补充包（如
 * biz-process-lifecycle）不进菜单，改为被依赖时由执行链自动并入。
 *
 * <p><b>每次展开菜单都拉一次</b>（不做「一次性缓存」）：菜单项由服务端决定，后端新增/下架技能、
 * 或调整过滤规则后，不该要求用户刷新整个页面才能看到——一次 GET 几十字节，比「改了看不见」便宜。</p>
 *
 * <p>加载失败静默降级为空：菜单只剩两个流程技能，对话主链完全不受影响。</p>
 */
const packageSkills = ref<ChatSkillOption[]>([])
/**
 * 技能清单是否已就绪（成功或静默失败都算就绪）。
 *
 * <p>空态能力卡片的数据源包含该清单：若未就绪就渲染成品卡，首屏会先画 3 张
 * （内置两项 + 上传文件、末张横跨两列），请求回来再补成 4 张（2×2）——
 * 用户看到的是「卡片数量凭空变了」。故未就绪时渲染等量骨架，就绪后一次成型。</p>
 */
const packageSkillsReady = ref(false)
let packageSkillsLoading = false

async function loadPackageSkills(): Promise<void> {
  if (packageSkillsLoading) return
  packageSkillsLoading = true
  try {
    packageSkills.value = await skillApi.chatOptions()
  } catch {
    // 静默：权限未开 / 网络失败时菜单不展示产出型技能，不打断用户
  } finally {
    packageSkillsLoading = false
    packageSkillsReady.value = true
  }
}

function shortSkillDesc(desc: string | null | undefined): string {
  const text = (desc ?? '').trim()
  return text.length > 46 ? `${text.slice(0, 46)}…` : text
}

/** 当前挂的是否为产出型技能（`pkg:name`）。 */
const activePackageName = computed(() => (activeSkill.value?.startsWith('pkg:')
  ? activeSkill.value.slice(4) : null))

/** 空态能力卡片 / 快捷指令的条目形状（tone 决定图标底色走哪个语义 token）。 */
interface WelcomeCard {
  key: string
  icon: Component
  tone: 'primary' | 'info' | 'success' | 'warning'
  title: string
  desc: string
  run: () => void
}

/**
 * 新建对话空态的能力卡片（设计图 2×2 栅格）。
 *
 * <p>每一项都映射到**已有真实能力**，不做无动作的装饰入口：两个内置技能走 {@code pickSkill}，
 * 产出型技能与「＋」菜单同源（{@code packageSkills}），最后一项兜底文件上传。
 * 设计稿里的「生成文档 / 指派 Agent」在本页没有对应实现，故不照抄文案。</p>
 */
const welcomeCards = computed<WelcomeCard[]>(() => {
  const cards: WelcomeCard[] = [
    {
      key: 'planner',
      icon: EditPen,
      tone: 'primary',
      title: '整理成方案',
      desc: '逐轮问清边界与取舍，产出可落地方案',
      run: () => pickSkill('planner')
    },
    {
      key: 'task',
      icon: MagicStick,
      tone: 'info',
      title: '直接拆解',
      desc: '跳过澄清，直接出方案并建任务',
      run: () => pickSkill('task')
    }
  ]
  // 产出型技能（archify 出 HTML / biz-process-lifecycle 出状态机图等）：有几个列几个，最多 2 个
  packageSkills.value.slice(0, 2).forEach((pkg) => {
    cards.push({
      key: `pkg:${pkg.name}`,
      icon: Grid,
      tone: 'success',
      title: pkg.name,
      desc: shortSkillDesc(pkg.description),
      run: () => pickSkill(`pkg:${pkg.name}`)
    })
  })
  cards.push({
    key: 'files',
    icon: Document,
    tone: 'warning',
    title: '上传文件',
    desc: 'PDF / Word / 文本 / 代码，单次 5 个',
    run: () => pickFiles()
  })
  return cards
})

/**
 * 新建对话空态的快捷指令条（设计图底部 /planner /task /search 三枚）。
 *
 * <p>斜杠语法按产品既定口径不再外露（见「＋」菜单注释），故用技能中文名呈现；
 * 第三枚对应联网搜索开关（设计稿的 /search），点击即切换，不做无实现的命令。</p>
 */
const welcomeCommands = computed<Array<{
  key: string
  icon: Component
  label: string
  active: () => boolean
  run: () => void
}>>(() => [
  {
    key: 'planner',
    icon: EditPen,
    label: '方案整理',
    active: () => activeSkill.value === 'planner',
    run: () => pickSkill('planner')
  },
  {
    key: 'task',
    icon: MagicStick,
    label: '直接拆解',
    active: () => activeSkill.value === 'task',
    run: () => pickSkill('task')
  },
  {
    key: 'web',
    icon: Connection,
    label: '联网搜索',
    active: () => webSearchEnabled.value,
    run: () => { webSearchEnabled.value = !webSearchEnabled.value }
  }
])

const activeSkillLabel = computed(() => {
  if (activeSkill.value === 'planner') return '方案整理'
  if (activeSkill.value === 'task') return '直接拆解'
  if (activePackageName.value) return activePackageName.value
  return ''
})
const activeSkillHint = computed(() => (activeSkill.value === 'planner'
  ? '方案整理：逐轮问清边界与取舍，再产出可执行方案'
  : activeSkill.value === 'task'
    ? '直接拆解：跳过澄清，按当前描述直接产出方案并建任务'
    : activePackageName.value
      ? `${activePackageName.value}：按该技能当场生成文件，产物在对话里直接下载`
      : ''))

/** 从「＋」菜单选技能：收起菜单后挂成输入框里的胶囊，光标回到输入框（不发送）。 */
function pickSkill(kind: string) {
  plusMenuVisible.value = false
  if (skillDisabled.value) return
  activeSkill.value = kind
  focusComposer()
}

/** 移除已挂载的技能（胶囊上的 ×，或空输入时退格）。 */
function clearSkill() {
  activeSkill.value = null
  focusComposer()
}

function focusComposer() {
  void nextTick(() => composerRef.value?.focus?.())
}

/** 空输入时按退格移除技能胶囊：与 WorkBuddy 的删除手势一致。 */
function onComposerKeydown(e: KeyboardEvent) {
  if (e.key === 'Backspace' && activeSkill.value && !input.value) {
    e.preventDefault()
    clearSkill()
  }
}

// 会话进入终态（FINALIZED/ARCHIVED）后技能不再可用，避免残留胶囊点了没反应
watch(() => conversation.value?.status, (s) => {
  if (s && s !== 'ACTIVE') activeSkill.value = null
})

// ══════════════════════════════════════════════════════════════
//  会话附件（V121 / 「＋→文件」）：上传 → 解析态轮询 → 胶囊管理
// ══════════════════════════════════════════════════════════════

/** 已挂到本会话（或本临时批次）的附件。 */
const attachments = ref<ChatAttachmentItem[]>([])

/**
 * 胶囊条只展示「用户上传」的附件：平台产物（source === 'platform'，
 * uploader_user_id 为 null）一律不进输入框，只出现在右侧「产物」侧栏。
 * 后端 list 已带 source 字段；极少数老数据无 source 时按用户上传处理（不误伤）。
 */
const userAttachments = computed(() =>
  attachments.value.filter((a) => a.source !== 'platform')
)
/**
 * ATT-3：「本轮不参与」的附件 id 集合（默认空 = 全部参与）。
 *
 * <p>只收集排除项而不是收集选中项：空集合天然表示「用户没动过」，
 * 此时请求不带 attachmentIds，让后端按模型判定收窄——两者语义才能区分。</p>
 */
const excludedAttachmentIds = ref<Set<string>>(new Set())

function isAttachmentExcluded(item: ChatAttachmentItem): boolean {
  return excludedAttachmentIds.value.has(String(item.id))
}

/**
 * `G-049`：附件相关状态的整体重置——<b>切会话 / 新建会话只调这一处</b>。
 *
 * <p><b>为什么必须收敛成一个函数</b>：这三份状态（清单 / 临时批次 token / 排除集合）
 * 属于<b>同一个语义</b>「当前这一轮的附件上下文」。分散在各处各清一半，一定会漏：
 * 原实现只清前两个，`excludedAttachmentIds` 从不清，于是从会话 A 切到 B 时 A 的排除项
 * 还留着。单看是「无害脏数据」（id 不跨会话匹配），但自从「空集」升格为
 * 「用户明确一个都不要」的有效意图（`G-045`）之后，残留集合就可能把 B 会话的附件
 * 误判成排除态——语义变更把无害脏数据变成了真实缺陷。</p>
 *
 * <p><b>不重置的时机同理</b>：建会认领（`clientToken` 被后端认领）时排除选择必须保留，
 * 那些 id 认领后仍然指向同一批附件，是用户刚做出的有效意图。所以只在「换会话主体」时清。</p>
 */
function resetAttachmentState() {
  clientToken.value = null
  attachments.value = []
  excludedAttachmentIds.value = new Set()
}

function toggleAttachmentParticipation(item: ChatAttachmentItem): void {
  const key = String(item.id)
  const next = new Set(excludedAttachmentIds.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  excludedAttachmentIds.value = next
}

/**
 * 本轮参与的附件 id：全部参与时返回 null（= 未指定，交给后端模型判定），
 * 有排除项时返回剩余 id（= 用户显式指定，优先于模型判定）。
 */
function effectiveAttachmentIds(): string[] | null {
  if (!excludedAttachmentIds.value.size) return null
  return userAttachments.value
    .filter((a) => !excludedAttachmentIds.value.has(String(a.id)))
    .map((a) => String(a.id))
}
/** 上传中（本地占位胶囊，避免点了没反应）。 */
const uploading = ref(false)
/** 拖拽悬停态（高亮落区）。 */
const dragOver = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 新会话首句前的附件临时归属（前端 UUID）。
 *
 * <p>顺序是「先传文件、再发第一句」，此刻还没有 conversationId；后端用本 token 占位，
 * 建会时按 token 认领回填。首条消息发出后置空，避免下一轮误用旧批次。</p>
 */
const clientToken = ref<string | null>(null)
let parsePollTimer: number | null = null

const MAX_FILES_PER_UPLOAD = 5
const MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024

/** 生成临时归属 token（crypto.randomUUID 在 http 内网环境可能不可用，退化为时间戳随机串）。 */
function ensureClientToken(): string {
  if (clientToken.value) return clientToken.value
  const cryptoObj = window.crypto as Crypto | undefined
  clientToken.value = cryptoObj?.randomUUID
    ? cryptoObj.randomUUID()
    : `ct-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
  return clientToken.value
}

/** 「＋→添加文件」：打开系统文件选择框。 */
function pickFiles() {
  plusMenuVisible.value = false
  if (skillDisabled.value) return
  fileInputRef.value?.click()
}

function onFileInputChange(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  // 清空 value：同一文件连续选择两次也能触发 change
  target.value = ''
  void uploadFiles(files)
}

function onComposerDragOver() {
  if (skillDisabled.value) return
  dragOver.value = true
}

function onComposerDragLeave() {
  dragOver.value = false
}

function onComposerDrop(e: DragEvent) {
  dragOver.value = false
  if (skillDisabled.value) return
  const files = Array.from(e.dataTransfer?.files ?? [])
  void uploadFiles(files)
}

/** 粘贴上传（截图/复制的文件）：只有剪贴板里真有文件时才拦截默认粘贴。 */
function onComposerPaste(e: ClipboardEvent) {
  const files = Array.from(e.clipboardData?.files ?? [])
  if (!files.length) return
  e.preventDefault()
  void uploadFiles(files)
}

/** 上传：前端先做一次「单文件 20MB / 单次 5 个」预校验，给出即时反馈（后端仍会再校验一遍）。 */
async function uploadFiles(files: File[]) {
  if (!files.length || uploading.value || skillDisabled.value) return
  if (files.length > MAX_FILES_PER_UPLOAD) {
    ElMessage.warning(`单次最多 ${MAX_FILES_PER_UPLOAD} 个文件，本次已忽略多余的 ${files.length - MAX_FILES_PER_UPLOAD} 个`)
    files = files.slice(0, MAX_FILES_PER_UPLOAD)
  }
  const tooBig = files.find((f) => f.size > MAX_FILE_SIZE_BYTES)
  if (tooBig) {
    ElMessage.error(`「${tooBig.name}」超过 20MB 上限`)
    return
  }
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  if (activeId.value != null) {
    form.append('conversationId', String(activeId.value))
  } else {
    form.append('clientToken', ensureClientToken())
  }
  uploading.value = true
  try {
    const saved = await clarifyApi.uploadAttachments(form)
    // 去重合并：后端可能因同一文件重复上传返回既有记录
    const known = new Set(userAttachments.value.map((a) => String(a.id)))
    saved.forEach((item) => {
      if (!known.has(String(item.id))) attachments.value.push(item)
    })
    scheduleParsePoll()
  } catch {
    // 拦截器已弹错（400 会带可读原因：超限/不支持的类型）
  } finally {
    uploading.value = false
  }
}

/** 移除附件（软删；后端幂等，前端先摘胶囊保证手感）。 */
async function removeAttachment(id: LongId) {
  // 快照全量（含平台产物）：失败回滚时原样还原，不能只回滚 userAttachments 而丢平台产物
  const before = attachments.value
  attachments.value = attachments.value.filter((a) => String(a.id) !== String(id))
  try {
    await clarifyApi.deleteAttachment(id)
  } catch {
    attachments.value = before
  }
}

/**
 * 轮询解析状态：附件从 PENDING/PARSING 到 READY/FAILED/SKIPPED 通常 1~3s（本地解析）。
 * 2s 一次、最多 15 次（30s）——超时后按实际状态展示，不无限轮询。
 */
function scheduleParsePoll() {
  if (parsePollTimer != null) return
  let attempts = 0
  parsePollTimer = window.setInterval(async () => {
    attempts += 1
    await refreshAttachments()
    const pending = userAttachments.value.some((a) => a.parseStatus === 'PENDING' || a.parseStatus === 'PARSING')
    if (!pending || attempts >= 15) {
      if (parsePollTimer != null) {
        window.clearInterval(parsePollTimer)
        parsePollTimer = null
      }
    }
  }, 2000)
}

/** 拉一次附件清单（切会话、发完消息、轮询共用）。 */
async function refreshAttachments() {
  if (activeId.value == null && !clientToken.value) {
    attachments.value = []
    return
  }
  try {
    attachments.value = await clarifyApi.listAttachments(activeId.value, clientToken.value)
  } catch {
    // 清单拉取失败不打断对话：保留现有胶囊，下一轮再试
  }
}

/**
 * 胶囊状态文案（SKIPPED 必须显式可见，不能画成加载中）。
 *
 * <p>`VU-8`：SKIPPED 按是否图片分叉——图片的 SKIPPED 是「无文本正文、另经图像通道送入」，
 * 不是「未识别」。都写「未识别」会让用户以为平台看不了图（工作台同一文案，两处口径必须一致）。</p>
 */
function attachmentStateText(item: ChatAttachmentItem): string {
  switch (item.parseStatus) {
    case 'READY':
      return item.extractedChars ? `${item.extractedChars} 字` : '可读'
    case 'PENDING':
    case 'PARSING':
      return '解析中'
    case 'FAILED':
      return '解析失败'
    case 'SKIPPED':
      return isImageAttachment(item) ? '图片' : '未识别'
    default:
      return ''
  }
}

/** 是否图片附件：MIME 前缀优先，缺失 / octet-stream 时按扩展名兜底（与后端同一判据口径）。 */
function isImageAttachment(item: ChatAttachmentItem): boolean {
  const mime = (item.mimeType ?? '').toLowerCase()
  if (mime && mime !== 'application/octet-stream') {
    return mime.startsWith('image/')
  }
  const name = (item.fileName ?? '').toLowerCase()
  return ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'].some((ext) => name.endsWith(ext))
}

function attachmentChipClass(item: ChatAttachmentItem): string {
  const status = (item.parseStatus ?? '').toLowerCase()
  const base = status ? `attachment-chip--${status}` : ''
  // ATT-3：未参与本轮时叠加「降级态」（灰字 + 无底色；不用删除线，否则像已删除）
  const sensitive = hasSensitiveHit(item) ? 'attachment-chip--sensitive' : ''
  // MA-8：敏感命中标记与「本轮不参与」是两件事，可同时出现
  return [base, sensitive, isAttachmentExcluded(item) ? 'attachment-chip--excluded' : '']
    .filter(Boolean)
    .join(' ')
}

/**
 * `MA-8`：该附件是否命中疑似敏感内容。
 *
 * <p><b>必须按三态判，不能写 {@code !item.sensitiveScanHitCount}</b>：{@code null} 是
 * 「未扫描」、{@code 0} 是「扫过没命中」，两种都不该提示，但把 null 当 0 会让
 * 「解析失败没扫」被静默当成「干净」——用户会以为文件已经检查过了。</p>
 */
function hasSensitiveHit(item: ChatAttachmentItem): boolean {
  return typeof item.sensitiveScanHitCount === 'number' && item.sensitiveScanHitCount > 0
}

/**
 * 规则 code → 人话标签（与后端 `SensitiveScanRule.label` 对齐；未知 code 原样显示，不吞掉）。
 */
const SENSITIVE_RULE_LABELS: Record<string, string> = {
  id_card: '身份证号',
  mobile: '手机号',
  bank_card: '银行卡号',
  private_key: '私钥',
  cloud_ak: '云厂商密钥',
  api_token: 'API 令牌',
  config_secret: '配置项密码',
}

/**
 * `MA-8`：本次已上传附件里的敏感命中汇总（驱动输入框上方那条提示）。
 *
 * <p>只在<b>发送前</b>统计 {@code userAttachments}——这是用户还来得及处置的唯一时刻。
 * 命中条数为 0 或未扫描（null）都不计入，也不存在「未知条数」这个显示态。</p>
 */
const sensitiveHitSummary = computed(() => {
  let count = 0
  let fileCount = 0
  const rules = new Set<string>()
  for (const item of userAttachments.value) {
    if (!hasSensitiveHit(item)) continue
    count += item.sensitiveScanHitCount ?? 0
    fileCount += 1
    for (const code of (item.sensitiveScanRules ?? '').split(',')) {
      const trimmed = code.trim()
      if (trimmed) rules.add(trimmed)
    }
  }
  const labels = Array.from(rules)
    .map((code) => SENSITIVE_RULE_LABELS[code] ?? code)
    // 排序让同一份文件重复渲染时文案稳定（否则每次刷新顺序可能不同，看起来像内容变了）
    .sort()
  return { count, fileCount, rules: labels.join('、') }
})

/** 命中规则的人话描述（用于胶囊 tooltip 与提示条文案）。 */
function sensitiveRulesText(item: ChatAttachmentItem): string {
  const raw = (item.sensitiveScanRules ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  if (raw.length === 0) return ''
  return raw.map((code) => SENSITIVE_RULE_LABELS[code] ?? code).join('、')
}

function attachmentChipTitle(item: ChatAttachmentItem): string {
  const size = item.fileSize ? `${(item.fileSize / 1024).toFixed(0)}KB` : ''
  const reason = item.parseError ? `；${item.parseError}` : ''
  const participation = isAttachmentExcluded(item) ? '本轮不参与（点击恢复）' : '本轮参与（点击取消）'
  // MA-8：把命中信息放进 tooltip——用户悬停即可知道「哪份文件、哪里可疑」，不必点开
  const sensitive = hasSensitiveHit(item)
    ? `；⚠ 检测到 ${item.sensitiveScanHitCount} 处疑似敏感信息（${sensitiveRulesText(item)}），不阻断`
    : ''
  return `${item.fileName}${size ? ' · ' + size : ''}${reason}${sensitive} · ${participation}`
}

// 编辑已发送消息（对齐 ChatGPT/DeepSeek/Kimi「编辑消息」交互）：
// editingMsgId 非空 = 该消息气泡处于行内编辑态；editingDraft 为 textarea 草稿；editingSaving 防重复提交
const editingMsgId = ref<string>('')
const editingDraft = ref('')
const editingSaving = ref(false)
/**
 * 编辑态携带的技能名。
 *
 * <p>为什么必须有它：`editMessage` 后端只解析正文里的 `[skill:name] ` 前缀，
 * <b>不读</b>消息 payload（`target.getPayload()` 只作为普通轮次的上下文透传）。
 * 编辑框里不把技能拼回去，重新发送就一定退化成普通对话。</p>
 */
const editingSkill = ref<string | null>(null)

// 输入优化（PromptEnhancer）：先预览、用户自行回填；不自动发送、不自动覆盖原文本输入框
const enhancing = ref(false)
const enhancePanelVisible = ref(false)
const enhancedDraft = ref('')

// S1 Chat SSE 流式（RC-4）：流式态按「会话」隔离成 Map，随 activeId 惰性取用。
// 原先这些是单份全局 ref（RequirementChat.vue:1711-1727），切换会话时下一轮会直接把上一会话
// 的流式内容覆盖掉；Map 化后各会话的流式过程互不串扰，切走再切回仍能继续看（流收尾后保留）。
// 模板统一经 computed `currentStream` 投影当前激活会话的实例，模板绑定改动最小。
interface PerConversationStream {
  streamText: string
  streamBuffer: string
  streamFlushTimer: number | null
  // CLARIFY 模式流式：LLM 输出「正文 + ```json 块」——检测到围栏后截断显示（只展示正文打字机，
  // JSON 结构不裸露，done 后收敛为卡片/终稿）；CHAT 模式不截断（正文可能含用户要求的代码块）
  jsonFenceSeen: boolean
  thinkingText: string
  reasoningBuffer: string
  reasoningFlushTimer: number | null
  // reasoningActive 标记「当前是否仍处于思考阶段」（正文首帧到达即置 false）
  reasoningActive: boolean
  thinkingExpanded: boolean
  // 过程步骤：联网检索等平台动作，与思考内容按到达顺序混排展示
  // （对齐 DeepSeek「已搜索 N 个网页」/ ChatGPT「Searching the web」）
  processSteps: string[]
  // 停止生成：该会话当前流式发送的 AbortController 句柄；非空 = 本会话流在跑
  streamAbort: AbortController | null
}
const streamStates = new Map<string, PerConversationStream>()

/** 流式态实例必须是响应式的：Map 里的普通对象不会被 Vue 追踪，打字机/思考/展开都更新不了视图。 */
function createStream(): PerConversationStream {
  return reactive<PerConversationStream>({
    streamText: '',
    streamBuffer: '',
    streamFlushTimer: null,
    jsonFenceSeen: false,
    thinkingText: '',
    reasoningBuffer: '',
    reasoningFlushTimer: null,
    reasoningActive: false,
    thinkingExpanded: true,
    processSteps: [],
    streamAbort: null
  }) as PerConversationStream
}

/** 无激活会话时模板读到的空投影（模块级常量，避免每次求值新建对象）。 */
const EMPTY_STREAM = createStream()

/** 惰性取某会话的流式态（不存在则建空实例；流收尾后保留在 Map 上，供切回继续展示）。 */
function getOrCreateStream(convId: string): PerConversationStream {
  let cs = streamStates.get(convId)
  if (!cs) {
    cs = createStream()
    streamStates.set(convId, cs)
  }
  return cs
}

// 新建会话在首帧 session 回来前没有 id，发送态与气泡文本先存在占位键上，
// 首帧到达后由 migratePlaceholderState 迁到真实 id。
const PLACEHOLDER_KEY = ''

/** 会话级发送态（RC-5）：仅当 targetId 对应的会话正在跑时置 true；null = 新建窗口的占位键。 */
function sendingOf(targetId: string | number | null | undefined): boolean {
  return !!sendingSet.value[String(targetId ?? '')]
}

const currentStream = computed(() =>
  activeId.value != null ? getOrCreateStream(String(activeId.value)) : EMPTY_STREAM
)
/** 当前激活会话是否正在发送（模板 disabled / loading / 占位统一走它）。 */
const activeSending = computed(() => sendingOf(activeId.value))

/** 当前激活会话的待发气泡文本（模板 `v-if="activeSending && pendingText"` 直接读它）。 */
const pendingText = computed(() => pendingTexts.value[String(activeId.value ?? '')] ?? '')

/** 写入某会话的发送态（覆盖式，见 sendingSet 注释；显式合并，保留其他会话的位）。 */
function setSending(targetId: string | number | null | undefined, on: boolean) {
  sendingSet.value = { ...sendingSet.value, [String(targetId ?? '')]: on }
}

/** 用户气泡文本按会话隔离（自由切换后全局单份会把 A 会话的待发文本显示到 B 会话里）。 */
function setPendingText(targetId: string | number | null | undefined, text: string) {
  pendingTexts.value = { ...pendingTexts.value, [String(targetId ?? '')]: text }
}

/** 新建会话首帧到达：把占位键上的发送态与气泡文本迁到真实 id（否则闸门与气泡都留在占位键上）。 */
function migratePlaceholderState(newId: LongId) {
  const realKey = String(newId)
  const nextSending = { ...sendingSet.value }
  if (nextSending[PLACEHOLDER_KEY]) {
    nextSending[PLACEHOLDER_KEY] = false
    nextSending[realKey] = true
    sendingSet.value = nextSending
  }
  const nextPending = { ...pendingTexts.value }
  if (nextPending[PLACEHOLDER_KEY]) {
    nextPending[realKey] = nextPending[PLACEHOLDER_KEY]
    nextPending[PLACEHOLDER_KEY] = ''
    pendingTexts.value = nextPending
  }
}
// 历史消息的过程区展开态（存 msg.id；同时只展开一条，避免长会话页面被撑爆）
const expandedHistoryProcess = ref<string>('')

/** 历史过程区标题：N 步检索 / 有思考；两者都有时合并描述。 */
function historyProcessHeadline(process: RoundProcessTrace): string {
  const steps = process.steps?.length ?? 0
  const hasThinking = !!process.thinking
  if (steps > 0 && hasThinking) return `思考与检索过程（${steps} 步）`
  if (steps > 0) return `检索过程（${steps} 步）`
  return '思考过程'
}

function toggleHistoryProcess(id: string | number) {
  const key = String(id)
  expandedHistoryProcess.value = expandedHistoryProcess.value === key ? '' : key
}

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

// 上轮 LLM 失败后可重试：ACTIVE 且最后一条是 user 消息（数据驱动，刷新后仍可重试）。
// 技能执行例外（SKX 修复）：技能请求是「当场执行」而非 LLM 澄清轮——异步执行期间
// （skillRunId ≠ null）、以及结束后最后一条 user 消息仍带 skill（已取消 / 未出新消息）时，
// 都不该算「回复生成失败」。否则整轮执行会叠一条「回复生成失败」假行，把真实占位 / 结果盖掉。
const canRetry = computed(() => {
  if (activeSending.value || conversation.value?.status !== 'ACTIVE') return false
  // C 修复：仍在向服务端确认后台技能执行态时，不急着判「回复失败」——
  // F5 后 skillRunId 是空的（内存态被清），要等 resumeActiveSkillRun 把活跃执行接回才知道
  if (skillRunId.value != null || skillCheckPending.value) return false
  const msgs = detail.value?.messages ?? []
  if (!msgs.length) return false
  const last = msgs[msgs.length - 1]
  if (last.role === 'user' && skillOf(last) != null) return false
  return last.role === 'user'
})

const pinnedPlannerName = computed(() => {
  const id = conversation.value?.plannerAgentId
  if (id == null) return ''
  const opt = plannerOptions.value.find(o => sameId(o.id, id))
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
  if (conversation.value?.status !== 'ACTIVE' || activeSending.value) return null
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

/**
 * 用户消息 payload 里的技能名（键名与后端 `SkillDirective.PAYLOAD_KEY = "skill"` 对齐）。
 *
 * 技能名此前只被后端用于路由，前端完全看不见——于是「历史对话看不出这条消息
 * 用了技能」与「编辑重发时技能丢失」两个问题同时无解。把它读出来这两件事一次解决。
 */
function skillOf(msg: RequirementMessage): string | null {
  if (msg.role !== 'user' || !msg.payload) return null
  try {
    const parsed = JSON.parse(msg.payload)
    const value = parsed?.skill
    return typeof value === 'string' && value.trim() ? value.trim() : null
  } catch { return null }
}

/**
 * 会话列表/头部的标题展示：剥掉内部的技能前缀。
 *
 * <p>标题由后端按「用户发送原文」截断生成，而原文带 `[skill:archify] ` 前缀 ⇒ 列表里
 * 直接露出内部协议语法。只在<b>展示层</b>剥离，库里的标题一字不动——它还被
 * 「按标题找回刚建的会话」等逻辑用作精确匹配键（`c.title === title`）。</p>
 */
function displayTitle(title: string | null | undefined): string {
  const raw = (title ?? '').trim()
  if (!raw) return ''
  const stripped = raw.replace(/^\[skill:[A-Za-z0-9][A-Za-z0-9._-]*]\s*/i, '').trim()
  return stripped || raw
}

// 结构化 assistant content 由后端合成（引导语 + \n1. 问题列表），卡片呈现问题时气泡只留引导语
function introOf(content: string) {
  const idx = content.search(/(^|\n)1\. /)
  return idx === -1 ? content : content.slice(0, idx).trim()
}

/** 产物卡片上的大小文案（精确字节数在 payload 里，界面只给人类可读值）。 */
function artifactSizeLabel(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 KB'
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
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
    // 过程数据（思考 + 检索步骤）随 payload 落库 ⇒ 历史消息也能回看，
    // 这是「思考过程在回复完成后消失」的根治点（此前只存在于 SSE 流里）
    const process: RoundProcessTrace | null = p?.process ?? null
    // 技能产物（选 archify 等产出型技能时，后端在对话轮内直接跑出文件）
    const artifact: ClarifyArtifactRef | null = p?.artifact ?? null
    // user 消息用了哪个技能（展示态显示 chip、编辑态回填）
    const skill = msg.role === 'user' ? skillOf(msg) : null
    if (!structured) return { msg, structured: null, intro: msg.content, selections: null, webSearch, process, artifact, skill, liked: false }
    const interactive = i === msgs.length - 1 && activeStructured.value != null
    const next = msgs[i + 1]
    return {
      msg,
      structured: interactive ? null : structured,
      intro: introOf(msg.content),
      selections: next ? userSelectionsOf(next) : null,
      webSearch,
      process,
      artifact,
      skill,
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

// 会话内平台生成的产物集合（技能执行产出；从全部消息 payload 收集，
// 含来源消息 id 用于侧栏定位；按生成顺序倒序展示，最新产物在前）。
// 与「用户上传附件」（attachments，输入框上方胶囊条）严格分离：产物是 AI 输出，进右侧侧栏。
const conversationArtifacts = computed(() => {
  const msgs = detail.value?.messages ?? []
  const list: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }[] = []
  for (const msg of msgs) {
    const p = assistantPayloadOf(msg)
    if (p?.artifact?.attachmentId) {
      list.push({ artifact: p.artifact, msgId: String(msg.id), msgSeq: msg.seq })
    }
  }
  return list.sort((a, b) => b.msgSeq - a.msgSeq)
})

// 产物侧栏可见性：点击顶部「产物」按钮切换；会话切换时自动收起
const artifactPanelOpen = ref(false)

const artifactCount = computed(() => conversationArtifacts.value.length)
const artifactToggleTitle = computed(() => {
  if (!conversationArtifacts.value.length) return '暂无产物（平台生成的附件会出现在这里）'
  return artifactPanelOpen.value ? '收起产物栏' : `查看本次会话产物（${conversationArtifacts.value.length} 个）`
})

// ── PA-12：上下文窗口（占用查询 + 手动压缩）────────────────
const contextUsage = ref<ContextUsage | null>(null)
const contextCompacting = ref(false)
// 输入框内圆形占用环：r=13 的周长，按占用比例做 stroke-dashoffset
const ctxRingCircumference = 2 * Math.PI * 13
const contextRatioPct = computed(() => {
  const ratio = contextUsage.value?.ratio ?? 0
  return Math.min(100, Math.max(0, Math.round(ratio * 100)))
})
const ctxRingOffset = computed(() => ctxRingCircumference * (1 - contextRatioPct.value / 100))
const ctxRingTitle = computed(() => contextUsage.value
  ? `上下文窗口：已用 ${contextRatioPct.value}%（点击查看明细 / 手动压缩）`
  : '上下文窗口：打开会话后可查看')

async function loadContextUsage() {
  if (!activeId.value) {
    contextUsage.value = null
    return
  }
  try {
    contextUsage.value = await clarifyApi.contextWindow(activeId.value)
  } catch { /* 拦截器已弹错 */ }
}

// 会话切换 / 每轮消息落库后刷新占用（detail 只在轮次收尾时整体替换，不会随 token 抖动）
watch(
  () => `${detail.value?.conversation.id ?? ''}:${detail.value?.messages.length ?? 0}`,
  () => {
    if (activeId.value != null) void loadContextUsage()
  }
)

async function compactContext() {
  if (!activeId.value || contextCompacting.value) return
  contextCompacting.value = true
  try {
    const result = await clarifyApi.compact(activeId.value)
    if (result.compacted) {
      ElMessage.success(`已压缩 ${result.evictedMessages} 条（${Math.round(result.beforeRatio * 100)}% → ${Math.round(result.afterRatio * 100)}%）`)
    } else {
      ElMessage.info(`未压缩：${result.reason}`)
    }
    await loadContextUsage()
  } catch {
    // 拦截器已弹错
  } finally {
    contextCompacting.value = false
  }
}

// isHtml：iframe 预览的是否为 HTML。只有 HTML 才加 sandbox ——
//   产物是模型生成的，同源 iframe 里的脚本能读父页面 sessionStorage（admin token 就在那），
//   等价 XSS；而 PDF（Chrome 内置 viewer）加 sandbox 会白屏、图片无脚本风险，故两者不加。
const artifactPreview = ref<{
  blobUrl: string
  mode: 'iframe' | 'markdown' | 'text'
  textContent: string
  fileName: string
  isHtml: boolean
} | null>(null)
const artifactPreviewLoading = ref(false)
let artifactBlobUrl = ''

// 注意必须取 activeId.value：写成 `() => activeId` 拿到的是 ref 对象本身，
// 引用恒定不变，watch 永远不触发（切会话右栏不收起）。
watch(
  () => activeId.value,
  () => {
    artifactPanelOpen.value = false
    closeArtifactPreview()
  }
)

/** 切换右栏开关。 */
function toggleArtifactPanel() {
  artifactPanelOpen.value = !artifactPanelOpen.value
}

/** 关闭右栏。 */
function closeArtifactPanel() {
  artifactPanelOpen.value = false
}

/** 关闭内嵌预览并释放 blob URL。 */
function closeArtifactPreview() {
  if (artifactBlobUrl) {
    URL.revokeObjectURL(artifactBlobUrl)
    artifactBlobUrl = ''
  }
  artifactPreview.value = null
}

/** 侧栏内嵌预览：点击产物条目后拉取内容（iframe 通道用 blob，文本类用 text）。 */
async function loadArtifactPreview(id: LongId, name: string) {
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : ''
  const textLike = ['md', 'txt', 'log', 'json', 'xml', 'yml', 'yaml', 'csv', 'html', 'htm', 'svg',
    'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx'].includes(ext)
  closeArtifactPreview()
  artifactPreviewLoading.value = true
  try {
    if (textLike) {
      const resp = await clarifyApi.attachmentPreviewText(id)
      artifactPreview.value = { blobUrl: '', mode: 'text', textContent: resp.data, fileName: name, isHtml: false }
    } else {
      const resp = await clarifyApi.attachmentPreviewBlob(id)
      const mime = (resp.headers['content-type'] as string | undefined) || 'application/octet-stream'
      artifactBlobUrl = URL.createObjectURL(new Blob([resp.data], { type: mime }))
      artifactPreview.value = { blobUrl: artifactBlobUrl, mode: 'iframe', textContent: '', fileName: name, isHtml: mime.toLowerCase().startsWith('text/html') }
    }
  } catch {
    // 业务异常（413 / 404 / 500）由 axios 拦截器统一弹错；预览区置空
    artifactPreview.value = null
  } finally {
    artifactPreviewLoading.value = false
  }
}

function previewArtifactInPanel(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  return loadArtifactPreview(item.artifact.attachmentId, item.artifact.fileName || '')
}

/**
 * 下载产物：必须走 blob 通道 —— 裸 <a href> 的浏览器导航请求
 * 不带 X-Admin-Token，会被 AuthInterceptor 401 拒（已实测 11:39:07 日志）。
 * 经 request 拦截器带鉴权头拉 blob → 临时 <a download> 落盘 → revokeObjectURL。
 */
async function downloadArtifactByRef(id: LongId, fileName: string) {
  try {
    const resp = await clarifyApi.attachmentDownloadBlob(id)
    saveBlobResponse(resp, fileName || 'attachment')
  } catch {
    // 拦截器已统一弹错
  }
}

function downloadArtifact(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  return downloadArtifactByRef(item.artifact.attachmentId, item.artifact.fileName || '')
}

/**
 * 新标签页打开产物：同样走 blob 通道避免 401。
 * 拉 blob → createObjectURL → window.open（Content-Type 已由响应头决定，text/html 会正常渲染）
 * → 延迟 revoke（给新标签页留出加载时间）。
 */
async function openArtifactByRef(id: LongId) {
  try {
    const resp = await clarifyApi.attachmentPreviewBlob(id)
    const mime = (resp.headers['content-type'] as string | undefined) || 'application/octet-stream'
    const contentUrl = URL.createObjectURL(new Blob([resp.data], { type: mime }))
    if (!mime.toLowerCase().startsWith('text/html')) {
      // 图片 / PDF：无脚本风险；PDF 还依赖浏览器内置 viewer，套 sandbox 会白屏
      window.open(contentUrl, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(contentUrl), 60_000)
      return
    }
    // HTML 产物不能直接 window.open(blob)：那会让模型生成的脚本在**与主页面同源**的
    // 顶层上下文执行，读得到 sessionStorage 里的 admin token（等价 XSS）。
    // 套一层同样没有 allow-same-origin 的 sandbox 包装页 ⇒ 内层成唯一源，拿不到父存储；
    // 包装页本身只有 style + iframe，不含任何业务脚本。
    const wrapper = '<!doctype html><meta charset="utf-8">'
      + '<style>html,body{margin:0;height:100%;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>'
      + `<iframe sandbox="allow-scripts" src="${contentUrl}"></iframe>`
    const wrapperUrl = URL.createObjectURL(new Blob([wrapper], { type: 'text/html' }))
    window.open(wrapperUrl, '_blank', 'noopener')
    setTimeout(() => {
      URL.revokeObjectURL(wrapperUrl)
      URL.revokeObjectURL(contentUrl)
    }, 60_000)
  } catch {
    // 拦截器已统一弹错
  }
}

function openArtifactInNewTab(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  return openArtifactByRef(item.artifact.attachmentId)
}

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
  try {
    conversations.value = await clarifyApi.list('REQUIREMENT')
  } catch { /* 拦截器已弹错 */ }
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
  // RC-5：只拦「目标会话正在跑」，不拦其他会话在跑——从而自由切换到别的会话发送
  if (sendingOf(id)) return
  closeEnhancePanel()
  const sid = String(id)
  activeId.value = sid
  contextUsage.value = null
  // 跨会话切换：临时批次只属于「尚未建会」的那一轮，换会话即作废；
  // `G-049`：排除集合同批清空——上一会话的排除态对新会话没有语义，留着就是脏数据
  resetAttachmentState()
  // 上一轮的技能进度属于上一个会话：立即停表并清占位（A3：新会话若有活跃执行，下面再恢复）
  stopSkillPolling()
  skillRunId.value = null
  skillRunPhaseDetail.value = ''
  try {
    detail.value = await clarifyApi.detail(sid)
    scrollToBottom()
    // 种子会话（助理派生 / 执行期重构派生）从列表点进来同样没有 AI 首轮，一并补
    autoRunSeedRound(detail.value)
    // A3：刷新/切回一个正在跑的会话时，把轮询与阶段接回来
    void resumeActiveSkillRun(sid)
  } catch { /* 拦截器已弹错 */ }
  void refreshAttachments()
}

function startNew() {
  // RC-5：新会话没有在跑（sendingOf 目标为空恒 false），不拦
  closeEnhancePanel()
  // 新会话与上一个会话的技能进度无关：停表并清占位（否则气泡会留在新会话里）
  stopSkillPolling()
  skillRunId.value = null
  skillRunPhaseDetail.value = ''
  activeId.value = null
  detail.value = null
  input.value = ''
  // 新会话从零开始：清掉上一轮的临时批次、胶囊与排除态（避免把旧文件的「不参与」带进新需求）
  resetAttachmentState()
}

// ── 输入优化（PromptEnhancer）：独立辅助链路，不进会话/任务链路 ──

// 优化输入：基于当前输入框内容调 LLM 生成结构化表达，结果进预览面板（不改动输入框、不发送）
async function handleEnhanceInput() {
  const text = input.value.trim()
  if (!text || enhancing.value || activeSending.value || finalizing.value) return
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

/**
 * `/技能名 需求` 快捷（对齐既有的 `/plan`、`/planner`）。
 *
 * <p><b>为什么动态匹配而不是写死正则</b>：可用技能由服务端下发（`packageSkills`），
 * 写死意味着每加一个技能就得改前端。这里按「首段 `/xxx` 在菜单里存在」判定，
 * 不在菜单里的 `/xxx` 一律当普通文本交给模型——用户随手打的斜杠不该被劫持。</p>
 *
 * <p><b>为什么不抢 `/plan` `/planner` `/task`</b>：它们各有专门语义（进方案澄清 / 直接拆解），
 * 由后面既有分支处理，这里显式排除，避免两套命令语法打架。</p>
 *
 * @returns 命中的技能名与剩余需求；未命中返回 {@code null}
 */
function matchSkillCommand(raw: string): { name: string; rest: string } | null {
  const m = raw.match(/^\/([A-Za-z0-9][A-Za-z0-9._-]*)(?:\s+([\s\S]*))?$/)
  if (!m) return null
  const lower = m[1].toLowerCase()
  if (lower === 'plan' || lower === 'planner' || lower === 'task') return null
  const hit = packageSkills.value.find(s => s.name.toLowerCase() === lower)
  return hit ? { name: hit.name, rest: (m[2] ?? '').trim() } : null
}

async function handleSend() {
  const raw = input.value.trim()
  if (!raw) {
    if (activeSkill.value && !activeSending.value) {
      ElMessage.warning(`已选「${activeSkillLabel.value}」，先在输入框里写一句需求再按 Enter`)
    }
    return
  }
  // RC-5：只拦「目标会话正在跑」——当前无 activeId（新会话）或 activeId 会话不跑则放行，
  // 从而在别的会话生成时也能点「发送」进入本会话
  if (activeId.value != null && sendingOf(activeId.value)) return
  // `/技能名 需求`：菜单可能还没加载过（用户没点过「＋」），先补一次再判定
  if (!activeSkill.value && raw.startsWith('/') && !packageSkills.value.length) {
    await loadPackageSkills()
  }
  const skillCmd = activeSkill.value ? null : matchSkillCommand(raw)
  if (skillCmd && !skillCmd.rest) {
    ElMessage.warning(`已识别技能「${skillCmd.name}」，请在同一句里补上要生成什么（例如 /${skillCmd.name} 画一张支付流程图）`)
    return
  }
  // 已挂载技能：先按能力语义改写，再走各自链路（命令语法对用户不可见）
  if (activeSkill.value === 'planner') {
    // /planner 链路：附加文本先落库进上下文，再切 CLARIFY
    input.value = ''
    activeSkill.value = null
    await handlePlannerCommand(raw)
    return
  }
  // 直接拆解：补 /task 前缀走下方同步 create 分支（读 FINALIZED/taskId 联动审阅）
  // 产出型技能：补 [skill:name] 前缀，后端据此剥出技能名、落 payload、建任务时写 required_skills
  // 技能来源两处：已挂载的胶囊优先，其次 `/技能名` 命令 —— 两者归一到同一个前缀协议
  const pkgName = activePackageName.value ?? skillCmd?.name ?? null
  const need = skillCmd ? skillCmd.rest : raw
  const text = pkgName
    ? `[skill:${pkgName}] ${need}`
    : (activeSkill.value === 'task' ? `/task ${need}` : need)
  const skill = activeSkill.value ?? (pkgName ? `pkg:${pkgName}` : null)
  // V40.2 /planner 斜杠命令：显式进入方案澄清模式（命令前缀不落消息）
  const cmd = text.match(PLANNER_COMMAND_RE)
  if (cmd) {
    activeSkill.value = null
    await handlePlannerCommand(cmd[1]?.trim() ?? '')
    return
  }
  closeEnhancePanel()
  input.value = ''
  activeSkill.value = null
  // 本次发送的目标会话（null = 新建窗口的占位键）。finally 必须用它清位而不能读 activeId：
  // 自由切换后期间 activeId 可能已改（用户切走，或首帧把 activeId 换成新会话）。
  const sendTargetId = activeId.value
  setPendingText(sendTargetId, need)
  setSending(sendTargetId, true)
  scrollToBottom()
  try {
    const plannerId = selectedPlanner.value === '__auto__' ? null : (selectedPlanner.value || null)
    // §6.169 /task 直达拆解：必须走同步 create —— 后端在这条路径上「建会话 + 直接建任务 +
    // 终稿直出」，前端要读返回的 FINALIZED/taskId 去联动打开草案审阅；流式通道只吐文本，
    // 拿不到这个联动信号（服务端也明确拒绝斜杠命令 / 显式技能走流式）。
    // 产出型技能同理：它也要建任务（技能要写进 required_skills 才会真的被执行链跑起来）。
    if (isTaskCommand(text) || pkgName) {
      await sendSyncCreate(text, plannerId)
      return
    }
    // 流式分流（全新会话）：
    //   已有会话 → streamSend 通道
    //   新会话  → streamCreate 通道（首帧 session 回传 id，再增量渲染）
    //   原同步 create 必须等「意图决策 + 联网搜索 + 完整 LLM」才一次性返回，
    //   用户全程只看到「思考中」占位——这是「1 分钟后一次性刷出」的直接原因。
    await streamSendOrCreate(text, plannerId, effectiveAttachmentIds())
    return
  } catch {
    // 兜底：流式建连失败（非业务错误，多为网络/鉴权层）时退回同步链路，
    // 保证「新会话第一句」在任何情况下都能落地，不丢用户文本
    const plannerId = selectedPlanner.value === '__auto__' ? null : (selectedPlanner.value || null)
    try {
      if (activeId.value == null) {
        await sendSyncCreate(text, plannerId)
      } else {
        const result = await clarifyApi.send(activeId.value, text)
        detail.value = result
        // SKX-13：兜底路径同样可能命中技能执行（提交即返回 + 轮询）
        maybeStartSkillPolling(result)
        loadList()
        scrollToBottom()
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
      // 消息未落库（建会前就失败/未找回会话）时回填输入框，避免丢失用户文本；
      // 回填「纯需求 + 技能胶囊」而非命令组合文本，重试时语义不丢
      const msgs = detail.value?.messages ?? []
      const lastIsSameUserText = msgs.length > 0
        && msgs[msgs.length - 1].role === 'user'
        && (msgs[msgs.length - 1].content === text || msgs[msgs.length - 1].content === need)
      if (!lastIsSameUserText) {
        input.value = need
        activeSkill.value = skill
      }
    }
  } finally {
    setPendingText(sendTargetId, '')
    setSending(sendTargetId, false)
  }
}

/** /task 直达拆解命令判定（与后端 ClarifyCommandSearchServiceImpl.isTaskCommand 同口径）。 */
function isTaskCommand(text: string): boolean {
  return /^\/task(?:\s|$)/i.test(text.trim())
}

/**
 * 同步「新建会话 + 首轮」（create）：用于 /task 直达命令与流式建连失败兜底。
 * 成功后按 FINALIZED/taskId 联动打开草案审阅（§6.169）。
 */
async function sendSyncCreate(text: string, plannerId: string | null) {
  // 此前无条件走 create —— 在同一个对话里连画第二张图时会被弹到一个新会话，
  // 历史上下文、产物侧栏、技能 chip 全部断掉，用户看到的是「又开了一个对话」。
  // 判据：已有 activeId 就在当前会话 continue（send），只有真正的新会话才 create。
  // 注：create 才需要 clientToken / plannerId / 搜索开关（首句前附件认领、选人、开关初值），
  //     send 一律沿用会话上已保存的设置，不再透传。
  const existing = activeId.value
  const result = existing != null
    ? await clarifyApi.send(existing, text)
    : await clarifyApi.create(text, plannerId, webSearchEnabled.value, clientToken.value)
  detail.value = result
  // SKX-13：技能执行已改为提交即返回 —— 拿到 runId 就开始轮询，不再等同步结果
  maybeStartSkillPolling(result)
  if (existing == null) {
    activeId.value = String(result.conversation.id)
    // 建会已认领临时批次：清 token（避免下一轮误用）并按会话重新拉附件清单
    clientToken.value = null
    void refreshAttachments()
  }
  loadList()
  scrollToBottom()
  if (result.conversation.status === 'FINALIZED' && result.conversation.taskId != null) {
    await triggerTaskReview(String(result.conversation.taskId))
  }
}

// ── S1 Chat SSE 流式发送：节流渲染 + done 后 detail 收敛 ──
// 2026-10-30 RC-4：所有流式函数改为显式接收目标会话的 PerConversationStream 实例，
// 由发送入口在「发起时」解析绑死（而非每次取 currentStream）——否则切走会话后，旧会话
// 还在后台 flush 的 token 会被写进新会话的流状态里（正是 Map 化要堵的串扰）。

// 60ms 节流：token 累积到 buffer，定时 flush 进 streamText 驱动 MarkdownView 全量重渲染
function flushStreamText(cs: PerConversationStream) {
  cs.streamFlushTimer = null
  if (cs.streamBuffer) {
    cs.streamText += cs.streamBuffer
    cs.streamBuffer = ''
    scrollToBottom()
  }
}

function appendStreamToken(token: string, cs: PerConversationStream) {
  // 思考阶段结束、正文开始：思考区自动折叠（用户仍可点「展开」回看）
  if (cs.reasoningActive) {
    cs.reasoningActive = false
    cs.thinkingExpanded = false
  }
  // CHAT 模式：纯文本正文不截断（正文可能含用户要求的 ``` 代码块）
  if (!isChatMode.value) {
    if (cs.jsonFenceSeen) return
    cs.streamBuffer += token
    const shown = cs.streamText + cs.streamBuffer
    const fence = shown.indexOf('```')
    if (fence !== -1) {
      // 截断：只保留 json 围栏前的正文，后续 token 全部忽略（done 后收敛卡片/终稿）
      cs.streamText = shown.slice(0, fence)
      cs.streamBuffer = ''
      cs.jsonFenceSeen = true
      if (cs.streamFlushTimer != null) {
        window.clearTimeout(cs.streamFlushTimer)
        cs.streamFlushTimer = null
      }
      return
    }
  } else {
    cs.streamBuffer += token
  }
  if (cs.streamFlushTimer == null) {
    cs.streamFlushTimer = window.setTimeout(() => flushStreamText(cs), 60)
  }
}

// 思考过程增量（reasoning 帧）：独立缓冲 + 更短节流（思考流高频、用户希望「看到在动」）
function appendStreamReasoning(text: string, cs: PerConversationStream) {
  cs.reasoningActive = true
  cs.reasoningBuffer += text
  if (cs.reasoningFlushTimer == null) {
    cs.reasoningFlushTimer = window.setTimeout(() => {
      cs.reasoningFlushTimer = null
      if (cs.reasoningBuffer) {
        cs.thinkingText += cs.reasoningBuffer
        cs.reasoningBuffer = ''
        scrollToBottom()
      }
    }, 80)
  }
}

function flushReasoningText(cs: PerConversationStream) {
  if (cs.reasoningFlushTimer != null) {
    window.clearTimeout(cs.reasoningFlushTimer)
    cs.reasoningFlushTimer = null
  }
  if (cs.reasoningBuffer) {
    cs.thinkingText += cs.reasoningBuffer
    cs.reasoningBuffer = ''
  }
}

// 流收尾：冲残留 buffer → 拉 detail 对齐落库全文（同 tick 内 streamText 清空，无闪烁）；
// 失败场景刷新详情暴露重试条（user 消息已落库）
async function settleStream(id: LongId, success: boolean, cs: PerConversationStream) {
  flushStreamText(cs)
  try {
    detail.value = await clarifyApi.detail(id)
    activeId.value = String(detail.value?.conversation.id ?? id)
    loadList()
    cs.streamText = ''
    scrollToBottom()
  } catch {
    // 拦截器已弹错；流式占位随 sending 结束不再渲染，重试条/重新进入会话兜底
    cs.streamText = ''
    if (success) ElMessage.warning('回复已生成，但详情刷新失败，请重新进入会话查看')
  }
}

// 已有会话：普通消息与结构化卡提交统一走 SSE 流式（token 增量渲染 + done 后收敛）
// ★：结构化卡提交此前走同步 clarifyApi.send —— 而同步链路要先跑完
// 联网搜索（数秒~数十秒）+ 完整 LLM 输出才一次性返回，用户全程无反馈；改走同一 SSE
// 通道后可见打字机增量（后端 runStreamRound 早已支持 selections 参数）。
async function streamSendActiveMessage(id: LongId, text: string,
                                      selections: ClarifySelection[] | null = null) {
  // 流收尾的 settle 要落地到「本次发送的目标会话」，故先取再传（切走后仍写回原会话，不污染新会话）
  const targetId = String(id)
  const cs = streamStates.get(targetId) ?? getOrCreateStream(targetId)
  resetStreamState(cs)
  const abort = new AbortController()
  cs.streamAbort = abort
  try {
    await streamSendConversation(id, text, selections, {
      onToken: (t) => appendStreamToken(t, cs),
      onReasoning: (t) => appendStreamReasoning(t, cs),
      onProgress: (t) => appendProcessStep(t, cs),
      onDone: () => { void settleStream(id, true, cs) },
      onError: (msg) => {
        ElMessage.error(msg)
        void settleStream(id, false, cs)
      },
      onAbort: () => { void settleStream(id, false, cs) }
    }, abort.signal)
  } finally {
    cs.streamAbort = null
    cleanupStreamState(cs, targetId)
  }
}

/**
 * 流式发送「自适应」入口：activeId 为空走 streamCreate（首帧 session 拿新 id），
 * 否则走 streamSend。两条通道共用同一套流式状态与收敛逻辑，避免「新会话无流式」的分叉。
 */
async function streamSendOrCreate(text: string, plannerId: string | null,
                                  attachmentIds: string[] | null = null) {
  const existingId = activeId.value
  // 新建会话在首帧 session 前尚无 id，先建一个占位实例承接首帧到达前的流式增量
  const placeholderKey = '__pending__'
  const existingCs = existingId != null
    ? streamStates.get(String(existingId)) ?? getOrCreateStream(String(existingId))
    : null
  // 新会话：id 由首帧 session 事件带回；done 前可能仍为 null
  let streamCreatedId: LongId | null = existingId
  const settleId = () => streamCreatedId
  // 当前实际承载流式增量的实例：老会话从已有 Map 取；新会话用占位实例，首帧后迁移到真实 key
  let cs = existingCs ?? getOrCreateStream(placeholderKey)
  resetStreamState(cs)
  // 停止生成：新建会话与老会话共用同一个 AbortController（streamCreate 首帧回 id 后仍可中止）
  const abort = new AbortController()
  cs.streamAbort = abort
  try {
    if (existingId != null) {
      await streamSendConversation(existingId, text, null, {
        onToken: (t) => appendStreamToken(t, cs),
        onReasoning: (t) => appendStreamReasoning(t, cs),
        onProgress: (t) => appendProcessStep(t, cs),
        onDone: () => { void settleStream(existingId, true, cs) },
        onError: (msg) => {
          ElMessage.error(msg)
          void settleStream(existingId, false, cs)
        },
        onAbort: () => { void settleStream(existingId, false, cs) }
      }, abort.signal, attachmentIds)
    } else {
      await streamCreateConversation(text, plannerId, webSearchEnabled.value, {
        onSession: (newId) => {
          streamCreatedId = newId
          // 占位实例迁移到真实会话 key：切走后由 Map 按 id 取回，不再随占位丢失
          const realKey = String(newId)
          streamStates.set(realKey, cs)
          streamStates.delete(placeholderKey)
          // 发送闸门与气泡文本同迁移：否则新会话的 activeSending 一直是 false（按钮可点、无停止）
          migratePlaceholderState(newId)
          // 立刻切到新会话（左侧列表随后由 settleStream 的 detail 拉取刷新）
          activeId.value = newId
          // 后端已在建会时认领临时批次：清 token 并按会话重拉附件
          clientToken.value = null
          void refreshAttachments()
        },
        onToken: (t) => appendStreamToken(t, cs),
        onReasoning: (t) => appendStreamReasoning(t, cs),
        onProgress: (t) => appendProcessStep(t, cs),
        onDone: () => {
          const id = settleId()
          if (id == null) { setSending(null, false); return }
          void settleStream(id, true, cs)
        },
        onError: (msg) => {
          ElMessage.error(msg)
          const id = settleId()
          if (id != null) void settleStream(id, false, cs)
        },
        onAbort: () => {
          const id = settleId()
          if (id != null) {
            void settleStream(id, false, cs)
            return
          }
          // 极端时序：session 事件尚未到达即被中止——会话已落库（含 user 消息），
          // 按标题找回并打开，靠重试条续跑；找不回则回填输入框保留用户文本
          void recoverAbortedNewConversation(text)
        }
      }, abort.signal,
        // 必须传 clientToken（第 6 参）：新会话首句前上传的附件靠它建会时被认领。
        // 漏传则后端 claimByClientToken 因空值早退，附件永远停在临时态
        // （conversation_id 为 null），正文进不了 Prompt，模型只能说「本轮没有附件」。
        // 取值发生在调用瞬间，早于 onSession 回调里的 clientToken 清空。
        clientToken.value,
        // 第 7 参：本轮参与的附件 id（ATT-3）；null = 未指定，由后端按模型判定收窄
        attachmentIds)
    }
  } finally {
    cs.streamAbort = null
    // 清「本次实际占用」的那个键：首帧后已迁移到新 id，仍按占位键清会漏掉真实会话的闸门
    cleanupStreamState(cs, streamCreatedId != null ? String(streamCreatedId) : null)
  }
}

/** 过程步骤增量（progress 帧）：一行一条，直接追加并保持展开（用户要看到「在做什么」）。 */
function appendProcessStep(text: string, cs: PerConversationStream) {
  if (!text) return
  // 同一行重复推送（正在检索 → 已检索）不去重：它们语义不同，按序展示更符合「过程」直觉
  cs.processSteps = [...cs.processSteps, text]
  scrollToBottom()
}

/** 过程区是否仍在进行（思考流未结束且正文未开始）——决定转圈图标与是否强制展开。 */
const isProcessRunning = computed(() =>
  !currentStream.value.streamText && (currentStream.value.reasoningActive || currentStream.value.processSteps.length > 0)
)

/** 过程区标题：进行中显示「思考中…（已 N 步）」，结束后显示「思考与检索过程」。 */
const processHeadline = computed(() => {
  const stepCount = currentStream.value.processSteps.length
  if (isProcessRunning.value) {
    return stepCount > 0 ? `思考中…（已 ${stepCount} 步）` : '思考中…'
  }
  return stepCount > 0 ? `思考与检索过程（${stepCount} 步）` : '思考过程'
})

/** 流式状态复位（正文/思考/步骤/围栏标记/缓冲）——只复位指定会话，不影响其他会话的流式态。 */
function resetStreamState(cs: PerConversationStream) {
  cs.streamText = ''
  cs.thinkingText = ''
  cs.thinkingExpanded = true
  cs.processSteps = []
  cs.streamBuffer = ''
  cs.reasoningBuffer = ''
  cs.reasoningActive = false
  cs.jsonFenceSeen = false
}

/** 流式状态清理（定时器/缓冲/发送态）——只清理指定会话的发送闸门。 */
function cleanupStreamState(cs: PerConversationStream, targetId: string | null | undefined) {
  if (cs.streamFlushTimer != null) {
    window.clearTimeout(cs.streamFlushTimer)
    cs.streamFlushTimer = null
  }
  flushReasoningText(cs)
  cs.streamBuffer = ''
  cs.reasoningBuffer = ''
  setPendingText(targetId, '')
  setSending(targetId, false)
}

/**
 * 停止生成：abort 当前流式连接。前端中断 SSE → 后端 dispose LLM 订阅，
 * 生成立即停止且未完成回复不落库；随后 settleStream 拉详情（user 消息已落库 → 出现重试条）。
 */
function stopGenerating() {
  const cs = currentStream.value
  if (!cs.streamAbort) return
  cs.streamAbort.abort()
  ElMessage.info('已停止生成')
}

// ── 编辑已发送消息（对齐 ChatGPT/DeepSeek/Kimi）──

/** 进入编辑态：预填原文与技能。发送中/终稿中/已放弃不可编辑。 */
function startEdit(msg: RequirementMessage) {
  if (activeSending.value || finalizing.value) return
  editingMsgId.value = String(msg.id)
  editingDraft.value = msg.content ?? ''
  // 原文里已不含技能前缀（后端落库时剥掉了），技能只存在于 payload ⇒ 从 payload 读回
  editingSkill.value = skillOf(msg)
}

/** 取消编辑：清空编辑态，原文不动。 */
function cancelEdit() {
  if (editingSaving.value) return
  editingMsgId.value = ''
  editingDraft.value = ''
  editingSkill.value = null
}

/**
 * 保存并重新生成：调后端「编辑消息 + 截断重跑」。编辑期间禁用（防重复提交），
 * 完成后收起编辑态并用后端返回的完整详情刷新界面。失败保留编辑态让用户重试。
 */
async function submitEdit(msg: { id: string | number }) {
  const text = editingDraft.value.trim()
  if (!text || editingSaving.value) return
  if (activeId.value == null) { cancelEdit(); return }
  // 技能拼回 `[skill:name] ` 前缀：后端据此走技能轮（不拼则退化成普通对话）。
  // 用户在编辑态点 × 清掉 chip ⇒ editingSkill 为 null ⇒ 后端收到纯文本，
  // 并把消息 payload 里的 skill 一并清掉，界面与行为保持一致。
  const outgoing = editingSkill.value ? `[skill:${editingSkill.value}] ${text}` : text
  editingSaving.value = true
  try {
    const result = await clarifyApi.editMessage(activeId.value, String(msg.id), outgoing)
    detail.value = result
    // SKX-13：编辑重发带技能时同样是「提交即返回」，需轮询进度
    maybeStartSkillPolling(result)
    editingMsgId.value = ''
    editingDraft.value = ''
    editingSkill.value = null
    loadList()
    scrollToBottom()
  } catch {
    // 拦截器已弹错；保留编辑态与草稿，用户可修正后重试
  } finally {
    editingSaving.value = false
  }
}

/** 新会话在 session 首帧到达前被中止：按标题找回已落库的会话并打开（无 id 无法拉详情）。 */
async function recoverAbortedNewConversation(text: string) {
  try {
    await loadList()
    const title = text.length <= 50 ? text : text.slice(0, 50)
    const found = conversations.value.find(c => c.status === 'ACTIVE' && c.title === title)
    if (found) {
      const fid = String(found.id)
      activeId.value = fid
      detail.value = await clarifyApi.detail(fid)
      scrollToBottom()
      return
    }
  } catch { /* 拦截器已弹错 */ }
  // 找不回会话：回填输入框，避免用户文本丢失
  const msgs = detail.value?.messages ?? []
  const lastIsSameUserText = msgs.length > 0
    && msgs[msgs.length - 1].role === 'user'
    && msgs[msgs.length - 1].content === text
  if (!lastIsSameUserText) input.value = text
}

// V40.2 /planner 命令处理：新会话先建 CHAT 会话再调 toClarify；已有会话直接调 toClarifyById
// （附加文本落库进上下文后切 CLARIFY，首轮强制 structured → 推荐卡片）
async function handlePlannerCommand(extra: string) {
  if (activeSending.value) return
  input.value = ''
  // 同 handleSend：目标会话先取定，finally 不读可能已切换的 activeId
  const targetId = activeId.value
  // 气泡展示用户意图文案，不再回显 /planner 语法
  setPendingText(targetId, extra || '请帮我整理一份技术方案')
  setSending(targetId, true)
  scrollToBottom()
  try {
    const plannerId = selectedPlanner.value === '__auto__' ? null : (selectedPlanner.value || null)
    let result: ClarifyConversationDetail
    if (activeId.value == null) {
      // 新会话：先建 CHAT 会话（首条消息即 extra），再调 toClarify 切换。
      // 首条消息已在 create 轮落库，切换时不再重复带 extra——否则同一段文本会落两条 user 消息
      // （旧版只在斜杠命令下暴露，技能入口上线后这条路径变成主入口，故一并修正）。
      const initMsg = extra || '请帮我整理一份技术方案'
      result = await clarifyApi.create(initMsg, plannerId, webSearchEnabled.value)
      activeId.value = String(result.conversation.id)
      // 新 id 出现即迁移占位闸门：toClarify 首轮是同步 LLM（数百秒），
      // 闸门留在占位键上会让新会话的发送按钮在整个切换轮期间都可点。
      migratePlaceholderState(activeId.value)
      result = await clarifyApi.toClarify(activeId.value, null)
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
    setPendingText(targetId, '')
    setSending(targetId, false)
    // 新会话首轮：闸门已随 migratePlaceholderState 迁到真实 id，这里补清，
    // 否则迁移过去的那一位永远为 true，输入框会被永久禁用
    const currentId = activeId.value
    if (currentId != null && String(currentId) !== String(targetId ?? '')) {
      setPendingText(currentId, '')
      setSending(currentId, false)
    }
  }
}

async function handleRetry() {
  if (activeId.value == null || activeSending.value) return
  // 技能执行期间禁止重试（canRetry 已隐藏按钮，这里兜底防竞态双击）
  if (skillRunId.value != null) return
  const targetId = activeId.value
  setSending(targetId, true)
  scrollToBottom()
  try {
    detail.value = await clarifyApi.retry(activeId.value)
    activeId.value = String(detail.value?.conversation.id ?? activeId.value)
    scrollToBottom()
  } catch { /* 拦截器已弹错；保持现状可再次重试 */ }
  finally { setSending(targetId, false) }
}

// V33 结构化选项提交：可读文本走 content（LLM 上下文），选择快照走 payload（回显）
// 改走 SSE 流式（与普通消息同一通道）——原同步链路要等搜索 + 完整 LLM 输出，
// 用户体感是「卡住很久然后一次性刷出」，且看不到思考过程。
async function handleStructuredSubmit(payload: { text: string; selections: ClarifySelection[] }) {
  const id = activeId.value
  if (id == null || activeSending.value) return
  setPendingText(id, payload.text)
  setSending(id, true)
  scrollToBottom()
  try {
    await streamSendActiveMessage(id, payload.text, payload.selections)
  } finally {
    setPendingText(id, '')
    setSending(id, false)
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
  // 并发守卫：对话轮在跑（activeSending）或已在终稿确认中（finalizing）时拒绝重复提交，
  // 防重复点击与轮中并发 finalize 竞态（后端另有 CAS + 幂等兜底）
  if (!conv || activeSending.value || finalizing.value) return
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
  if (!conv || activeSending.value || finalizing.value) return
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
    if (sameId(activeId.value, id)) {
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
  const title = displayTitle(conv?.title) || '当前会话'
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

/**
 * 种子会话进页面自动补跑第一轮。
 *
 * <p>「种子会话」= 由别处派生、只带一条 user 种子消息的会话，两个来源：
 * {@code parentConversationId}（PA-11 助理对话派生任务）、{@code sourceSubTaskId}
 * （`ADR-008` §19.6 执行期重构派生）。这类会话的种子是**后端直接落库**的，没走
 * `send`，所以 LLM 从来没被调用过——不在这里补一轮，用户从「拆出去」跳进来只会
 * 看到自己那段上下文，模型一个字都不回。</p>
 *
 * <p>判据只认这两个标记，不用「最后一条是 user」：真正 LLM 失败的普通会话也符合
 * 后者，每次进页面都自动重跑会白烧 token，那种交给「回复失败 → 重试」按钮。</p>
 */
function autoRunSeedRound(d: ClarifyConversationDetail | null) {
  if (d == null) return
  const conversation = d.conversation
  if (conversation == null) return
  const seedConversation = conversation.parentConversationId != null
    || conversation.sourceSubTaskId != null
    || conversation.sourceDraftCode != null
  if (!seedConversation) return
  const msgs = d.messages ?? []
  const last = msgs[msgs.length - 1]
  if (last != null && last.role === 'user') void handleRetry()
}

onMounted(() => {
  loadList()
  loadPlannerOptions()
  // 产出型技能清单必须**挂载即拉**：空态能力卡片（`welcomeCards`）与「＋」菜单同源，
  // 若只等首次展开菜单才加载，首屏卡片会少一张、点「＋」后凭空多出一张（数量与布局双跳）。
  void loadPackageSkills()
  // PA-11：从助理对话创建任务后跳转时按 query.id 打开目标需求会话
  const queryId = route.query.id
  if (typeof queryId === 'string' && queryId) {
    activeId.value = queryId
    void clarifyApi.detail(queryId).then(d => {
      detail.value = d
      autoRunSeedRound(d)
    }).catch(() => { /* 拦截器已弹错 */ })
  }
  // 附件清单：仅在已有会话时拉（新会话为空，等用户上传后才出现胶囊）
  if (activeId.value != null) void refreshAttachments()
  // C 修复：刷新落回当前会话时也要确认后台技能执行态并接回轮询——
  // 否则刷新后 skillRunId 一直为空，技能执行中的 user 消息会被 canRetry 误判成「回复失败」
  if (activeId.value != null) void resumeActiveSkillRun(activeId.value)
})

// SKX-13：组件卸载时停掉技能轮询（定时器泄漏会让切页后还在打后端）
onBeforeUnmount(() => {
  stopSkillPolling()
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

/* ── 顶bar「对话头部」（设计图图层逐项还原）──
   容器：白底 / padding 16 24 / gap 16 / align center / justify space-between
   左：26×26 图标(r8 浅紫底) + 17px 标题 + 状态徽标(h20 r5 10px)
       副标题行 padding-top 4 / gap 6 / 12px
   右：操作组 gap 8，按钮 h30 / r8 / padding 0 11 / 12px；「更多」纯图标 30×30
   注：全局 .el-card__header 的 padding 带 !important，此处必须同权清零后自绘 */
:deep(.chat-card .el-card__header) {
  flex: 0 0 auto;
  padding: 0 !important;
  border-bottom: 1px solid var(--ha-border-light) !important;
  font-size: inherit;
  font-weight: inherit;
}
.header-actions { display: flex; gap: 8px; }

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 24px;
  background: var(--ha-surface);
}

/* ---- 左侧标题区 ---- */
.chat-head-main {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
}
.chat-head-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
/* 26×26 圆角 8 浅紫底 + 紫色图标（设计图 fill #F3EEFF / icon #7C3AED） */
.chat-head-icon {
  flex: none;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--ha-primary-light);
  color: var(--ha-primary);
  font-size: 14px;
}
.chat-head-title {
  margin: 0;
  min-width: 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ha-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 状态徽标：h20 / padding 0 7 / r5 / 10px 灰字（设计图 fill #F5F3FC） */
.chat-head-badge {
  flex: none;
  height: 20px;
  padding: 0 7px;
  display: inline-flex;
  align-items: center;
  border-radius: 5px;
  background: var(--ha-surface-hover);
  color: var(--ha-muted);
  font-size: 10px;
  font-weight: 400;
  line-height: 1.2;
  white-space: nowrap;
}
/* 副标题行：padding-top 4 / gap 6 / 12px */
.chat-head-sub-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
  min-width: 0;
}
.chat-head-sub-icon {
  flex: none;
  font-size: 14px;
  color: var(--ha-muted);
  opacity: 0.8;
}
.chat-head-sub {
  font-size: 12px;
  line-height: 1.2;
  color: var(--ha-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- 右侧操作组 ---- */
.chat-header .header-actions {
  flex: none;
  align-items: center;
  gap: 8px;
}
/* 设计图：按钮 h30 / 圆角 8 / padding 0 11 / 字号 12 / 白底浅边 */
.chat-header .header-actions .el-button {
  height: 30px;
  padding: 0 11px;
  border-radius: 8px;
  font-size: 12px;
  --el-button-bg-color: var(--ha-surface);
  --el-button-border-color: var(--ha-border-light);
  --el-button-text-color: var(--ha-ink-secondary);
  --el-button-hover-bg-color: var(--ha-surface-hover);
  --el-button-hover-border-color: var(--ha-border);
  --el-button-hover-text-color: var(--ha-primary);
}
/* Element 相邻按钮默认 margin-left:12px，会与 gap 叠加撑空 ⇒ 归零交给 gap */
.chat-header .header-actions .el-button + .el-button { margin-left: 0; }
/* 「更多」= 纯图标 30×30（设计图 w30 h30） */
.chat-header .header-actions .head-more-btn {
  width: 30px;
  padding: 0;
}

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

/* ── 2026-10-27 产物侧栏（平台生成的附件，对齐 Kimi 右侧可收缩面板） ── */
/* 顶部「产物」开关按钮（header-actions 内）
   设计图规格：h30 / padding 0 11 / gap 6 / 圆角 8 / 12px / 1px 浅边
   激活态：浅紫底(#F6F2FF) + 浅紫边(#DDD1FA) + 深紫字(#6D28D9) */
.artifact-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--ha-border-light);
  border-radius: 8px;
  background: var(--ha-surface);
  color: var(--ha-ink-secondary);
  font-size: 12px;
  cursor: pointer;
  position: relative;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-toggle-btn:hover {
  border-color: rgba(124, 58, 237, 0.28);
  color: var(--ha-primary);
}
.artifact-toggle-btn.is-active {
  border-color: rgba(124, 58, 237, 0.28);
  background: var(--ha-primary-light);
  color: var(--ha-primary-hover);
}
.artifact-toggle-btn.has-artifacts .artifact-toggle-label {
  color: var(--ha-primary);
  font-weight: 600;
}
.artifact-toggle-label { font-size: 12px; }
.artifact-toggle-badge {
  display: inline-grid;
  place-items: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--ha-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

/* 侧栏本体：flex 子项，固定宽度；展开/收起走 v-show + 宽度过渡 */
.artifact-sidebar {
  width: 320px;
  flex: 0 0 320px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--ha-border-light);
  background: var(--ha-surface-elevated);
  overflow: hidden;
}
.artifact-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ha-border-light);
  flex-shrink: 0;
}
.artifact-sidebar-count {
  font-size: 12px;
  color: var(--ha-muted);
}
.artifact-sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ha-ink);
}
.artifact-sidebar-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.artifact-sidebar-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: var(--ha-radius-sm);
  background: transparent;
  color: var(--ha-muted);
  cursor: pointer;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-sidebar-close:hover {
  color: var(--ha-ink);
  background: var(--ha-surface-hover);
}
/* PA-12：上下文窗口浮层 */
.ctx-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: var(--ha-ink);
}
.ctx-head {
  display: flex;
  justify-content: space-between;
  font-weight: 600;
}
.ctx-ratio { color: var(--ha-muted); }
.ctx-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--ha-surface-hover);
  overflow: hidden;
}
.ctx-bar-fill {
  height: 100%;
  background: var(--ha-accent, var(--ha-ink));
  transition: width var(--ha-duration-fast) var(--ha-ease-out);
}
.ctx-row {
  display: flex;
  justify-content: space-between;
  color: var(--ha-muted);
}
.ctx-total {
  color: var(--ha-ink);
  border-top: 1px solid var(--ha-border);
  padding-top: 6px;
}
.ctx-meta {
  color: var(--ha-muted);
  font-size: 11px;
}
.ctx-empty { color: var(--ha-muted); }
.ctx-compact-btn { width: 100%; }

/* PA-12：输入框内的上下文占用环（点击展开浮层；≥80% 转警示色） */
.ctx-ring {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--ha-muted);
  cursor: pointer;
}
.ctx-ring:hover { background: var(--ha-surface-hover); }
.ctx-ring-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ctx-ring-track {
  fill: none;
  stroke: var(--ha-border-light);
  stroke-width: 2.5;
}
.ctx-ring-fill {
  fill: none;
  stroke: var(--ha-primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  transition: stroke-dashoffset var(--ha-duration-fast) var(--ha-ease-out),
              stroke var(--ha-duration-fast) var(--ha-ease-out);
}
.ctx-ring.is-warn .ctx-ring-fill { stroke: var(--ha-warning); }
.ctx-ring.is-warn .ctx-ring-label { color: var(--ha-warning-text); }
.ctx-ring-label {
  position: relative;
  font-size: 9px;
  font-weight: 600;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  color: var(--ha-ink-secondary);
}
.ctx-ring-icon {
  position: relative;
  font-size: 13px;
  color: var(--ha-muted);
}
/* ── 会话产物列表 ── */
.artifact-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.artifact-loading {
  padding: 16px;
  text-align: center;
  color: var(--ha-muted);
  font-size: 12px;
}

.artifact-sidebar-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.artifact-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--ha-radius-md);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background var(--ha-duration-fast) var(--ha-ease-out),
              border-color var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-item:hover {
  background: var(--ha-surface-hover);
}
.artifact-item.is-current {
  border-color: var(--ha-primary-light);
  background: var(--ha-primary-muted);
}
.artifact-item-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: var(--ha-radius-sm);
  background: var(--ha-primary-light);
  color: var(--ha-primary);
  font-size: 15px;
}
.artifact-item-meta {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.artifact-item-name {
  font-size: 13px;
  color: var(--ha-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-item-sub {
  font-size: 12px;
  color: var(--ha-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 降档标记：用信息态色而非危险色——它是「请核对」的提示，不是失败 */
.artifact-item-degraded {
  color: var(--ha-info-text);
}
.artifact-item-actions {
  display: flex;
  gap: 2px;
  flex: 0 0 auto;
  opacity: 0;
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-item:hover .artifact-item-actions,
.artifact-item.is-current .artifact-item-actions {
  opacity: 1;
}
.artifact-item-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: var(--ha-radius-sm);
  background: transparent;
  color: var(--ha-muted);
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-item-btn:hover {
  color: var(--ha-primary);
  background: var(--ha-primary-muted);
}

/* 内嵌预览区（侧栏下半部） */
.artifact-preview {
  flex: 0 0 45%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--ha-border-light);
  background: var(--ha-bg);
}
.artifact-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--ha-border-light);
  flex-shrink: 0;
  background: var(--ha-surface-elevated);
}
.artifact-preview-name {
  font-size: 12px;
  color: var(--ha-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-preview-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: var(--ha-radius-sm);
  background: transparent;
  color: var(--ha-muted);
  cursor: pointer;
  font-size: 13px;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-preview-close:hover {
  color: var(--ha-ink);
  background: var(--ha-surface-hover);
}
.artifact-preview-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.artifact-preview-iframe {
  width: 100%;
  flex: 1 1 auto;
  border: none;
  background: var(--ha-surface);
}
.artifact-preview-pre {
  flex: 1 1 auto;
  margin: 0;
  padding: 14px 16px;
  font-family: var(--ha-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-ink);
  background: var(--ha-surface);
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
  tab-size: 2;
}

/* 侧栏展开/收起过渡（宽度 + 透明度） */
.artifact-panel-enter-active,
.artifact-panel-leave-active {
  transition: width var(--ha-duration) var(--ha-ease-out),
              opacity var(--ha-duration) var(--ha-ease-out);
  overflow: hidden;
}
.artifact-panel-enter-from,
.artifact-panel-leave-to {
  width: 0;
  flex-basis: 0;
  opacity: 0;
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
/* 用户消息限宽 75%（气泡形态，太宽会失去「一句话」的视觉重量）；
   助手回复放开到 100%（文档流，长文每行字数太少会显著拖慢中文阅读） */
.msg-row.from-assistant .msg-col {
  max-width: 100%;
}
.msg-row.from-user .msg-col {
  max-width: 75%;
  align-items: flex-end;
}

.msg-bubble {
  position: relative;
  border-radius: var(--ha-radius-lg);
  font-size: 14px;
  line-height: 1.65;
  /* ⑥ 长内容防爆 */
  overflow-wrap: anywhere;
  word-break: break-word;
  /* 气泡始终填满 col，保证用户/助手气泡视觉同宽；
     col 上限由 .msg-col 控制 */
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
/* 产物生成提示条：产物已移入右侧侧栏，消息流内仅留一条轻提示，
   点击「查看」展开侧栏 —— 平台生成的附件不再内联在气泡里（与用户上传附件分离） */
.msg-artifact-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  border: 1px dashed var(--ha-primary-light);
  border-radius: var(--ha-radius-md);
  background: var(--ha-primary-muted);
  color: var(--ha-ink-secondary);
  font-size: 12px;
}
.msg-artifact-tip-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg-artifact-tip-text b {
  color: var(--ha-primary);
  font-weight: 600;
}
.msg-artifact-tip-btn {
  flex: 0 0 auto;
  padding: 3px 10px;
  border: 1px solid var(--ha-primary);
  border-radius: var(--ha-radius-sm);
  background: var(--ha-primary);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out);
}
.msg-artifact-tip-btn:hover {
  opacity: 0.9;
}
/*
 * 助手回复 = **文档流**，不是卡片（对齐 DeepSeek / Kimi）
 *
 * 原实现：消息外面套「浅底 + 1px 描边 + 10/14 内边距」的卡片，正文字号 14px。
 * 两个后果：① 长文被边框切成一格一格，越读越像「卡片列表」而不是「一篇文章」；
 * ② 卡片内边距 + 75% 限宽叠加后，中文每行只剩十几个字，折行极密。
 * 现改为：气泡透明、无内边距、无边框，正文完全交给 MarkdownView 的排版体系
 * （15px / 1.75 / 标题 22-19-17 / 段距 14px），列宽放开到 100%。
 */
.msg-row.from-assistant .msg-bubble {
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 0;
  color: var(--ha-ink);
}

/* 用户消息保留气泡形态（右侧、紫底白字）：它是「一句话」，用气泡区分说话人 */
.msg-row.from-user .msg-bubble {
  padding: 10px 14px;
  background: var(--ha-primary);
  color: #fff;
  border-top-right-radius: 6px;
}

/*
 * Markdown 内部的 code / table / blockquote / 列表样式统一由 MarkdownView 负责，
 * 本文件不再覆盖（此前这里有一套 `pre` / `table{display:block}` / `th,td` 覆盖，
 * 会与 MarkdownView 的「代码块头部」「表格滚动容器」打架）。
 */
.msg-row.from-user .msg-bubble :deep(pre) {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}
.msg-row.from-user .msg-bubble :deep(a) {
  color: #fff;
  text-decoration: underline;
  text-underline-offset: 2px;
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

/* 编辑消息（对齐 ChatGPT/DeepSeek）：
   用户消息行是 row-reverse 镜像（气泡靠右），铅笔放气泡左侧外缘 */
.msg-edit-hover {
  position: absolute;
  left: -30px;
  top: 4px;
  opacity: 0;
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out);
}
.msg-row:hover .msg-edit-hover {
  opacity: 1;
}
.msg-edit-hover:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
/* 编辑态容器：技能 chip 与 textarea 竖排（textarea 自身无法内联胶囊） */
.msg-edit-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.msg-edit-skill {
  display: flex;
}
/* 胶囊里的 × 原本 hover 才显示；编辑框内没有胶囊 hover 目标，必须常显才有「可移除」的可发现性 */
.msg-edit-skill .skill-token-close {
  opacity: 1;
}
/* 历史用户消息上的技能徽标：比输入框胶囊小一号并弱化，避免喧宾夺主 */
.msg-skill-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
  font-size: 12px;
  line-height: 18px;
  vertical-align: 1px;
  white-space: nowrap;
  user-select: none;
}
.msg-skill-chip-icon {
  font-size: 11px;
  color: var(--ha-primary);
}
.msg-edit-input .el-textarea__inner {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
}
.msg-edit-actions {
  gap: 8px;
  padding-top: 6px;
}
.msg-edit-cancel {
  width: auto;
  padding: 0 8px;
  font-size: 13px;
  color: var(--ha-muted);
}
.msg-edit-cancel:hover { color: var(--ha-danger); }
.msg-edit-cancel:disabled { opacity: 0.4; cursor: not-allowed; }

.msg-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
  color: var(--ha-muted);
  font-size: 13px;
}
.msg-streaming { font-size: 15px; }
/* A2：技能执行占位里的「停止生成」——与阶段文案同行，不另起一行 */
.skill-cancel-btn { margin-left: 4px; }

/* 思考过程区（重做）：
   改前三处问题——① 用 Element Plus 默认值而非品牌 token：`border-left: 3px solid var(--el-color-info-light-5)`
   是**蓝色**侧条（蓝是已废弃的旧主色，且侧条强调是禁用的装饰手法）；② 正文用 --ha-muted(#8B96AB)
   压在 #FAFAFA 上实测只有 **2.85:1**，远低于 WCAG AA 的 4.5:1 —— 这就是「发灰、发糊」的来源；
   ③ 思考内容按纯文本插值，`**`/反引号原样露出，没有行内代码 chip。
   改后：品牌描边浅底 + --ha-ink-secondary(#4A5568 ≈ 6.8:1) + 走 MarkdownView 紧凑变体。 */
.msg-thinking {
  margin-bottom: 8px;
  padding: 10px 14px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface-hover);
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ha-ink-secondary);
}
.msg-thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  font-weight: 500;
  color: var(--ha-ink);
}
.msg-thinking-toggle {
  margin-left: auto;
  font-size: 12px;
  color: var(--ha-muted);
}
.msg-process-step {
  padding: 2px 0;
  color: var(--ha-ink-secondary);
  font-size: 13px;
  line-height: 1.7;
}
.msg-process-step::before {
  content: '·';
  margin-right: 6px;
  color: var(--ha-muted);
}
.msg-process-thinking {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--ha-border);
  color: var(--ha-ink-secondary);
}
.msg-thinking-history { margin-bottom: 6px; }
/* 正文交给 MarkdownView 紧凑变体排版，这里只承担「可折叠 + 定高滚动」两个职责；
   不再设 white-space: pre-wrap —— 否则会与 markdown 块级元素的空白处理叠加。 */
.msg-thinking-body {
  margin-top: 6px;
  max-height: 240px;
  overflow-y: auto;
  word-break: break-word;
}

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

/* ── 新建对话空态（设计图）──
   ① 品牌图标 48×48 圆角 14 紫色渐变 + 白色 sparkle
   ② 主标题 22px / 副标题 14px 居中
   ③ 能力卡片 2 列栅格（≤720px 降为 1 列），卡片 = 图标 + 标题 + 说明
   ④ 底部快捷指令胶囊贴底，激活态跟随真实状态（技能挂载 / 联网开关）
   结构：hero+cards 占满剩余高度并垂直居中，指令条 margin-top:auto 贴住 composer，
   与设计图「内容居中 + 指令条贴底」的构图一致。 */
.thread.is-welcome {
  min-height: 100%;
}
.chat-welcome {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: 24px 24px 10px;
}
.cw-main {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.cw-hero {
  text-align: center;
}
.cw-hero-icon {
  width: 48px;
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #8B5CF6 0%, #6D28D9 100%);
  color: #fff;
  font-size: 24px;
  box-shadow: 0 8px 20px rgba(124, 58, 237, 0.28);
}
.cw-hero-title {
  margin: 14px 0 0;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--ha-ink);
}
.cw-hero-desc {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ha-muted);
}

.cw-cards {
  margin: 24px auto 0;
  max-width: 760px;
  width: 100%;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
/* 技能清单加载中的等量骨架：尺寸与真实卡片一致（两行各 114 / gap 12 ⇒ 总高 240），
   就绪后替换成成品卡时布局零位移 */
.cw-card-skeleton {
  min-height: 114px;
  border: 1px solid var(--ha-border-light);
  border-radius: 10px;
  background: var(--ha-surface-hover);
  opacity: 0.6;
}
.cw-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  text-align: left;
  border: 1px solid var(--ha-border-light);
  border-radius: 10px;
  background: var(--ha-surface);
  cursor: pointer;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              box-shadow var(--ha-duration-fast) var(--ha-ease-out),
              transform var(--ha-duration-fast) var(--ha-ease-out);
}
.cw-card:hover:not(:disabled) {
  border-color: rgba(124, 58, 237, 0.28);
  box-shadow: var(--ha-shadow-sm);
  transform: translateY(-1px);
}
.cw-card:disabled { cursor: not-allowed; opacity: 0.55; }
/* 卡片数为奇数时（本环境产出型技能可能为 0 个），末枚横跨两列，避免 2 列栅格留空洞 */
.cw-card:last-child:nth-child(odd) { grid-column: 1 / -1; }
.cw-card-icon {
  width: 26px;
  height: 26px;
  margin-bottom: 2px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 14px;
}
/* 图标底色按语义分色（设计图四个卡片各不同色），全部走主题 token */
.cw-card-icon.is-primary { background: var(--ha-primary-light); color: var(--ha-primary); }
.cw-card-icon.is-info { background: var(--ha-info-bg); color: var(--ha-info-text); }
.cw-card-icon.is-success { background: var(--ha-success-bg); color: var(--ha-success-text); }
.cw-card-icon.is-warning { background: var(--ha-warning-bg); color: var(--ha-warning-text); }
.cw-card-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--ha-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cw-card-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--ha-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cw-cmds {
  margin-top: 20px;
  padding-top: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
}
.cw-cmd {
  height: 28px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--ha-border-light);
  border-radius: 14px;
  background: var(--ha-surface);
  color: var(--ha-ink-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.cw-cmd:hover {
  border-color: rgba(124, 58, 237, 0.28);
  color: var(--ha-primary);
}
.cw-cmd.is-on {
  border-color: rgba(124, 58, 237, 0.28);
  background: var(--ha-primary-light);
  color: var(--ha-primary-hover);
}
.cw-cmd-icon { font-size: 13px; }

@media (max-width: 720px) {
  .chat-welcome { padding: 28px 12px 8px; }
  .cw-cards { grid-template-columns: minmax(0, 1fr); }
  .cw-hero-title { font-size: 19px; }
}

/* ── 输入区（composer）
   对齐个人工作台 `wb-composer`：胶囊式卡片（圆角 16 / 1px 淡边 / 轻阴影 / padding 14 16 12），
   textarea 去边框融进卡片，工具栏在卡片内部贴底。 */
.chat-input {
  flex: 0 0 auto;
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

/* 卡片本体（工作台口径）；focus 只做边框微沉，不加紫色光环——与工作台一致 */
.composer-area {
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-xl);
  background: var(--ha-surface);
  box-shadow: 0 8px 24px rgba(16, 24, 40, 0.06);
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out);
}
.composer-area:focus-within {
  border-color: var(--ha-border);
}
.composer-area :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  padding: 0 4px 8px !important;
  background: transparent !important;
  resize: none;
  font-size: 14px;
  line-height: 1.6;
  font-family: inherit;
  color: var(--ha-ink);
}

/* 输入行：技能胶囊与正文同一行起点（对齐 WorkBuddy「技能 chip + 光标」），
   胶囊不吃文本宽度，正文从胶囊右侧开始；多行时整块右移保持左对齐一致。 */
/* 会话附件胶囊（V121）：状态可见是硬要求——SKIPPED 必须与「解析中」在视觉上区分开 */
.attachment-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 8px;
}
/* ATT-3 参与态：选中 = 主色勾 + 主色浅底 + 文件名加粗；
   未参与只做「降级」（灰字 + 无底色），不用删除线/虚线 —— 那会被读成「文件已删除」 */
.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 260px;
  padding: 3px 9px;
  border: 1px solid rgba(124, 58, 237, 0.28);
  border-radius: 999px;
  background: var(--ha-primary-light);
  color: var(--ha-ink);
  font-size: 12px;
  /* ATT-3：胶囊可点击切换「本轮是否参与」 */
  cursor: pointer;
  user-select: none;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
  line-height: 18px;
}
.attachment-chip:hover {
  border-color: rgba(124, 58, 237, 0.55);
}
.attachment-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.attachment-state {
  color: var(--ha-muted);
  white-space: nowrap;
}
.attachment-chip--ready .attachment-state {
  /* 就绪态不再用绿色：与主色浅底打架，「90385 字」本身已是就绪信号 */
  color: var(--ha-muted);
}
.attachment-chip--pending .attachment-state,
.attachment-chip--parsing .attachment-state {
  color: var(--ha-warning, #d97706);
}
.attachment-chip--failed {
  border-color: var(--ha-danger, #dc2626);
}
.attachment-chip--failed .attachment-state {
  color: var(--ha-danger, #dc2626);
}
.attachment-chip--skipped {
  border-style: dashed;
}
.attachment-chip--skipped .attachment-state {
  color: var(--ha-muted);
}
.attachment-icon {
  font-size: 15px;
  color: var(--ha-primary);
}
/* 未参与本轮：整颗胶囊降级为中性态（勾消失、文字转灰、底色去掉） */
.attachment-chip--excluded {
  border-color: var(--ha-border-light);
  background: transparent;
}
.attachment-chip--excluded .attachment-name {
  color: var(--ha-muted);
  font-weight: 400;
}
.attachment-chip--excluded .attachment-icon {
  font-size: 13px;
  color: var(--ha-muted);
}
/* 上传中占位胶囊不是「选中」语义，保持中性底 */
.attachment-chip--uploading {
  border-color: var(--ha-border-light);
  background: var(--ha-surface-hover);
  cursor: default;
}
/* MA-8：敏感命中标记。
   用琥珀色而非红色：命中的是「疑似」，平台未阻断、内容照常发送，
   红框会让用户以为文件被拒；琥珀色表达「请你自己看一眼」。 */
.attachment-chip--sensitive {
  border-color: rgba(217, 119, 6, 0.55);
}
.attachment-sensitive-flag {
  font-size: 14px;
  color: #d97706;
  flex-shrink: 0;
}
/* 汇总提示条：发送前的最后一次告知。刻意做成「信息条」而非「确认弹窗」——
   log-only 阶段误报率未知，弹窗会把正常业务打断（蓝图 §4 MA-8 ⑥）。 */
.attachment-sensitive-tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border: 1px solid rgba(217, 119, 6, 0.35);
  border-radius: 8px;
  background: rgba(217, 119, 6, 0.08);
  font-size: 12px;
  line-height: 1.6;
  color: #b45309;
}
.attachment-sensitive-tip .el-icon {
  font-size: 14px;
  margin-top: 1px;
  flex-shrink: 0;
}
.attachment-sensitive-text {
  flex: 1;
  min-width: 0;
}
.attachment-close {
  display: inline-flex;
  align-items: center;
  padding: 0;
  margin-left: 2px;
  border: none;
  background: transparent;
  color: var(--ha-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out);
}
.attachment-chip:hover .attachment-close {
  opacity: 1;
}
.attachment-close:hover {
  color: var(--ha-danger, #dc2626);
}
/* 原生 file input 只作为触发源，不对用户可见 */
.attachment-input {
  display: none;
}
/* 拖拽落区高亮：让用户知道可以松手（不覆盖卡片本身的投影） */
.composer-area.is-dragover {
  border-color: var(--ha-primary) !important;
  background: var(--ha-primary-light);
}

.composer-input-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
.composer-input-row :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.skill-token {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  margin-top: 1px;
  padding: 0 3px 0 7px;
  border-radius: 6px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink);
  font-size: 13px;
  line-height: 22px;
  white-space: nowrap;
  user-select: none;
}
/* 两条技能给图标一点颜色差，扫一眼能分清挂的是哪个 */
.skill-token--planner .skill-token-icon {
  color: var(--ha-primary);
}
.skill-token--task .skill-token-icon {
  color: var(--ha-accent-cyan);
}
/* 产出型技能（pkg:name）：类名不能直接拼技能名（冒号/连字符），统一用 package 变体 */
.skill-token--package .skill-token-icon {
  color: var(--ha-primary);
}
.skill-token-icon {
  font-size: 13px;
  color: var(--ha-ink-secondary);
}
.skill-token-name {
  font-weight: 500;
}
.skill-token-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--ha-muted);
  font-size: 11px;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.skill-token:hover .skill-token-close {
  opacity: 1;
}
.skill-token-close:hover {
  background: var(--ha-border-light);
  color: var(--ha-ink);
}
/* 挂载/移除时的轻量进出场，避免胶囊硬闪 */
.skill-token-fade-enter-active,
.skill-token-fade-leave-active {
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out),
              transform var(--ha-duration-fast) var(--ha-ease-out);
}
.skill-token-fade-enter-from,
.skill-token-fade-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}

/* 「＋」能力入口（工作台口径：28×28 圆形描边按钮） */
.plus-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  padding: 0;
  border: 1px solid var(--ha-border-light);
  border-radius: 50%;
  background: var(--ha-surface);
  color: var(--ha-muted);
  cursor: pointer;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.plus-btn:hover:not(:disabled) {
  border-color: var(--ha-primary);
  color: var(--ha-ink);
}
.plus-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

/* 菜单内容被 teleport 到 body，scoped 选择器命中不到 → 用 :global 锚定 popper-class */
:global(.plus-menu-popover) {
  padding: 6px !important;
}
/* 产出型技能是服务端下发的，条数不可预知（今天 2 个，将来可能更多）：
   超过视口高度就在菜单内滚动，而不是把菜单顶出屏幕 */
:global(.plus-menu-popover .plus-menu) {
  max-height: min(60vh, 520px);
  overflow-y: auto;
}
:global(.plus-menu-popover .plus-menu-group-title) {
  padding: 4px 8px 6px;
  font-size: 12px;
  color: var(--ha-muted);
  letter-spacing: 0.5px;
}
:global(.plus-menu-popover .plus-menu-item) {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: var(--ha-radius-md);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background var(--ha-duration-fast) var(--ha-ease-out);
}
:global(.plus-menu-popover .plus-menu-item:hover) {
  background: var(--ha-surface-hover);
}
:global(.plus-menu-popover .plus-menu-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 24px;
  height: 24px;
  border-radius: var(--ha-radius-sm);
  background: var(--ha-primary-light);
  color: var(--ha-primary);
  font-size: 14px;
}
:global(.plus-menu-popover .plus-menu-text) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
:global(.plus-menu-popover .plus-menu-name) {
  font-size: 13px;
  color: var(--ha-ink);
  line-height: 1.4;
  /* 技能包名可能较长（biz-process-lifecycle），断行而不是撑宽菜单 */
  word-break: break-word;
}
:global(.plus-menu-popover .plus-menu-desc) {
  font-size: 12px;
  color: var(--ha-muted);
  line-height: 1.4;
}
/* 菜单里回显「当前已挂载的技能」，避免用户忘了自己选过什么 */
:global(.plus-menu-popover .plus-menu-item.is-active) {
  background: var(--ha-primary-light);
}
:global(.plus-menu-popover .plus-menu-item.is-active .plus-menu-name) {
  color: var(--ha-primary);
  font-weight: 600;
}
:global(.plus-menu-popover .plus-menu-check) {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--ha-primary);
  font-size: 13px;
}

/* 工具栏（工作台口径：卡片内贴底，左右分组，上留 10） */
.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
  flex-wrap: wrap;
}
.input-actions-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}
/* 右侧图标组收紧到 6px，与工作台一致 */
.input-actions-right { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }

/* 右侧图标按钮（工作台口径：28×28 / 圆角 8 / 无边框，hover 出浅底） */
.composer-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ha-muted);
  font-size: 15px;
  cursor: pointer;
  transition: background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.composer-icon-btn:hover:not(:disabled) {
  background: var(--ha-surface-hover);
  color: var(--ha-ink);
}
.composer-icon-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
/* 麦克风：工作台给浅底，禁用态保持浅底只降字色（不整体变淡，避免像"坏了"） */
.composer-icon-btn.is-mic {
  background: var(--ha-surface-hover);
}
.composer-icon-btn.is-mic:disabled {
  opacity: 1;
  color: var(--ha-muted);
}

/* 发送按钮（工作台口径：28×28 / 圆角 10 / 主色实心 / 白图标 + 主色投影） */
.composer-send-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: 10px;
  background: var(--ha-primary);
  color: #fff;
  font-size: 16px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(124, 58, 237, 0.28);
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.composer-send-btn:hover:not(:disabled) {
  background: var(--ha-primary-hover);
}
.composer-send-btn:disabled {
  opacity: 0.45;
  box-shadow: none;
  cursor: not-allowed;
}
/* 生成中：红色 = 可中止；紫色转圈 = 同步路径不可中止 */
.composer-send-btn.is-stop {
  background: var(--ha-danger);
  box-shadow: 0 4px 12px rgba(239, 68, 68, 0.28);
}
.composer-send-btn.is-busy {
  cursor: default;
}
/* 不可中止的「发送中」：转圈本身就是状态信号，不能跟着 disabled 一起变淡 */
.composer-send-btn.is-busy:disabled {
  opacity: 1;
}

/* 文字型次要动作（放弃会话）：与 28px 图标按钮同高，不破坏工具栏基线 */
.composer-text-btn {
  height: 28px;
  padding: 0 8px;
  flex: none;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ha-muted);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.composer-text-btn.is-danger:hover:not(:disabled) {
  background: var(--ha-danger-bg);
  color: var(--ha-danger);
}
.composer-text-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.chat-readonly-tip {
  color: var(--ha-muted);
  font-size: 13px;
  text-align: center;
  padding: 16px 8px;
}

/* 联网搜索开关（工作台口径：一体化胶囊，图标 + 文案 + 小开关） */
.web-search-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px 0 10px;
  border: 1px solid var(--ha-border-light);
  border-radius: 9999px;
  background: var(--ha-surface-hover);
  flex-shrink: 0;
}
.web-search-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ha-muted);
  cursor: default;
  user-select: none;
}
.web-search-icon { font-size: 13px; color: var(--ha-primary); }

/* 规划者选择（保留「只筛 planner」的既有逻辑，仅把下拉外观做成工作台的胶囊 chip） */
.planner-select { display: flex; align-items: center; gap: 6px; }
.planner-label { font-size: 12px; color: var(--ha-muted); flex-shrink: 0; }
.planner-picker { width: 216px; max-width: 100%; }
.planner-picker :deep(.el-select__wrapper) {
  min-height: 28px;
  height: 28px;
  padding: 0 10px;
  border-radius: 9999px;
  background: var(--ha-surface-hover);
  box-shadow: 0 0 0 1px var(--ha-border-light) inset;
  font-size: 12px;
}
.planner-picker :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--ha-primary) inset;
}

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

/* 页脚 AI 生成声明（工作台口径：输入卡片正下方居中一行） */
.chat-disclaimer {
  text-align: center;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ha-muted);
  margin-top: 0;
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
  .chat-layout { flex-direction: column; position: relative; }
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
  /* 窄屏：产物侧栏改为覆盖在对话区之上的底部抽屉。
     不能留在 flex 流里——侧栏占高后 .chat-main 被压到接近 0，
     输入框会溢出叠到抽屉上（工作台 65vh 时尤其明显）。 */
  .artifact-sidebar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    width: auto;
    flex: none;
    max-height: 78vh;
    border-left: none;
    border-top: 1px solid var(--ha-border-light);
    box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.28);
    z-index: 30;
  }
  .artifact-sidebar-body {
    overflow-y: auto;
  }
  /* 覆盖层不再走宽度过渡，改为自底部滑入 */
  .artifact-panel-enter-active,
  .artifact-panel-leave-active {
    transition: transform var(--ha-duration) var(--ha-ease-out),
                opacity var(--ha-duration) var(--ha-ease-out);
  }
  .artifact-panel-enter-from,
  .artifact-panel-leave-to {
    width: auto;
    flex-basis: auto;
    transform: translateY(100%);
    opacity: 0;
  }
}

/* 下拉菜单中的危险项：与 TaskList/SubTaskList 同构 */
.dropdown-danger { color: var(--ha-danger); }
</style>
