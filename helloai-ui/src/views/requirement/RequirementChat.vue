<template>
  <div class="page ha-entrance-up">
    <el-card class="chat-card">
      <template #header>
        <div class="card-header chat-header">
          <div class="chat-header-title">
            <span class="chat-header-main">新建对话（AI 助手）</span>
            <span class="chat-header-sub">与 AI 助手自由对话；点「＋」→ 技能：要成体系选「方案整理」，跳过澄清直接建任务选「直接拆解」，要产出文件（如架构图 / 业务流程图）选对应的产出型技能</span>
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
            <!-- 2026-10-27 产物侧栏开关：平台生成的附件收进右侧可收缩侧栏（对齐 Kimi）；
                 与用户上传附件（输入框上方胶囊条）严格分离。有产物时高亮 + 角标数字。 -->
            <button
              type="button"
              class="artifact-toggle-btn"
              :class="{ 'is-active': artifactPanelOpen, 'has-artifacts': conversationArtifacts.length > 0 }"
              :title="artifactToggleTitle"
              @click="toggleArtifactPanel"
            >
              <el-icon><Document /></el-icon>
              <span class="artifact-toggle-label">产物</span>
              <span
                v-if="conversationArtifacts.length"
                class="artifact-toggle-badge"
              >{{ conversationArtifacts.length }}</span>
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
        <!-- 左栏：会话列表（设计图：新建对话 + 搜索 + 状态 tabs + 分组） -->
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
                          <el-input
                            v-if="editingMsgId === String(row.msg.id)"
                            v-model="editingDraft"
                            type="textarea"
                            :rows="3"
                            resize="none"
                            placeholder="编辑消息…"
                            class="msg-edit-input"
                            @keydown.enter.exact.prevent="submitEdit(row.msg)"
                            @keydown.esc="cancelEdit"
                          />
                          <span v-else class="msg-user-text">{{ row.intro }}</span>
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
                          <span class="msg-artifact-tip-text">已生成文件 <b>{{ row.artifact.fileName }}</b>，见右侧「产物」栏</span>
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
                          :disabled="sending || finalizing || editingSaving"
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
                  <!-- 过程区（2026-10-18）：联网检索等平台步骤 + 推理模型思考内容，
                       按到达顺序混排；正文开始后自动折叠为一行可展开（对齐 DeepSeek/ChatGPT） -->
                  <div
                    v-if="processSteps.length || thinkingText"
                    class="msg-bubble msg-thinking"
                  >
                    <div
                      class="msg-thinking-head"
                      @click="thinkingExpanded = !thinkingExpanded"
                    >
                      <el-icon class="is-loading" v-if="isProcessRunning">
                        <Loading />
                      </el-icon>
                      <span>{{ processHeadline }}</span>
                      <span class="msg-thinking-toggle">
                        {{ thinkingExpanded || isProcessRunning ? '收起' : '展开' }}
                      </span>
                    </div>
                    <div
                      v-if="thinkingExpanded || isProcessRunning"
                      class="msg-thinking-body"
                    >
                      <div
                        v-for="(step, si) in processSteps"
                        :key="`step-${si}`"
                        class="msg-process-step"
                      >
                        {{ step }}
                      </div>
                      <div
                        v-if="thinkingText"
                        class="msg-process-thinking"
                      >
                        <MarkdownView
                          compact
                          :content="thinkingText"
                        />
                      </div>
                    </div>
                  </div>
                  <!-- S1 流式回复：token 增量渲染 Markdown；尚未产出 token 时保持思考中占位 -->
                  <div
                    v-if="streamText"
                    class="msg-bubble msg-streaming"
                  >
                    <MarkdownView :content="streamText" />
                  </div>
                  <div
                    v-else-if="!thinkingText && !processSteps.length"
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
                      v-auth="'conversation:finalize'"
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
                        v-auth="'conversation:regenerate'"
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
                      :disabled="sending || finalizing"
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
                          <div class="plus-menu-group-title">技能</div>
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
                          <div class="plus-menu-group-title">文件</div>
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
                        accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.json,.log,.html,.htm"
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
                        <el-icon v-if="!enhancing">
                          <MagicStick />
                        </el-icon>
                        优化输入
                      </el-button>
                      <el-button
                        v-if="activeId && conversation?.status === 'ACTIVE'"
                        v-auth="'conversation:abandon'"
                        size="small"
                        type="danger"
                        plain
                        :disabled="sending || finalizing"
                        @click="handleAbandon"
                      >
                        放弃会话
                      </el-button>
                      <!-- 发送 / 停止 复用同一按钮（DeepSeek/Kimi 风格，2026-10-19）：
                           流式生成中按钮变红色「停止生成」态，点击中止生成；
                           同步路径（/task 等）无法中止 → 显示「发送中」loading 态 -->
                      <el-button
                        v-if="sending"
                        :type="streamAbort ? 'danger' : 'primary'"
                        :loading="!streamAbort"
                        :disabled="finalizing"
                        title="停止生成，当前回复将不会保存"
                        @click="streamAbort && stopGenerating()"
                      >
                        <el-icon v-if="streamAbort" style="margin-right: 4px"><CircleClose /></el-icon>
                        {{ streamAbort ? '停止生成' : '发送中' }}
                      </el-button>
                      <el-button
                        v-else
                        v-auth="'conversation:send'"
                        type="primary"
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
        <!-- 2026-10-27 产物侧栏（平台生成的附件，对齐 Kimi 右侧可收缩面板）：
             与用户上传附件（输入框上方胶囊条）严格分离。展开 = 列表 + 内嵌预览，
             收起 = 仅保留顶部「产物」开关按钮。 -->
        <transition name="artifact-panel">
          <aside
            v-show="artifactPanelOpen"
            class="artifact-sidebar"
          >
            <div class="artifact-sidebar-head">
              <span class="artifact-sidebar-title">产物</span>
              <span class="artifact-sidebar-count">{{ conversationArtifacts.length }} 个</span>
            </div>
            <div class="artifact-sidebar-body">
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
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Connection, Delete, MagicStick, CopyDocument, CaretTop, CaretBottom, Refresh, MoreFilled, Plus, Search, Share, Download, Document, CircleClose, EditPen, Close, Check, CircleCheckFilled, Grid, TopRight } from '@element-plus/icons-vue'
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
import type { ChatAttachmentItem, ClarifyArtifactRef, ClarifyAssistantPayload, ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, RequirementConversationStatus, RequirementMessage, LongId, RoundProcessTrace, WebSearchTrace } from '@/types'

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

// 输入框占位文案按会话模式区分（三态口径统一：新建 / 自由对话 / 方案澄清）。
// 2026-10-22：斜杠语法不再出现在文案里——能力入口收进输入框左下角「＋」菜单的「技能」组，
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
const pendingText = ref('')
const sending = ref(false)
const finalizing = ref(false)
const streamEl = ref<HTMLElement | null>(null)
// 停止生成（2026-10-19）：当前流式发送的 AbortController 句柄；非空 = 流在跑，展示「停止生成」按钮
const streamAbort = ref<AbortController | null>(null)

// 技能入口可用性：生成中 / 定稿中 / 会话已非 ACTIVE 时禁用（避免点了没反应）
const skillDisabled = computed(() => sending.value
  || finalizing.value
  || (activeId.value != null && conversation.value?.status !== 'ACTIVE'))

// 「＋」能力菜单展开态（点技能项后先收起，再挂载到输入框）
const plusMenuVisible = ref(false)
// 首次展开菜单时拉一次对话技能清单（失败静默降级为只剩流程技能）
watch(plusMenuVisible, (visible) => {
  if (visible) void loadPackageSkills()
})

// 已挂载的技能（2026-10-22，对齐 WorkBuddy「技能 chip + 光标」）：
// 选技能 ≠ 立即发送——挂成输入框里的胶囊，用户补完需求再 Enter；
// 发送时才翻译成后端既有协议（planner → /planner，task → /task 前缀），语法始终不外露。
// 2026-10-26：新增产出型技能（`pkg:<技能包名>`），发送时翻译成 `[skill:<name>] ` 前缀，
// 后端据此把技能写进任务 required_skills（子任务里真跑技能 CLI 并导出可下载产物）。
const activeSkill = ref<string | null>(null)
const composerRef = ref<any>(null)

/**
 * 菜单里可选的「产出型技能」（2026-10-26）：服务端只下发「能独立出产物」的健康技能包
 * （`internal_run_skill` ∧ 无依赖）——archify 出 HTML；依赖别人的规范补充包（如
 * biz-process-lifecycle）不进菜单，改为被依赖时由执行链自动并入。
 *
 * <p><b>每次展开菜单都拉一次</b>（不做「一次性缓存」）：菜单项由服务端决定，后端新增/下架技能、
 * 或调整过滤规则后，不该要求用户刷新整个页面才能看到——一次 GET 几十字节，比「改了看不见」便宜。</p>
 *
 * <p>加载失败静默降级为空：菜单只剩两个流程技能，对话主链完全不受影响。</p>
 */
const packageSkills = ref<ChatSkillOption[]>([])
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
  }
}

function shortSkillDesc(desc: string | null | undefined): string {
  const text = (desc ?? '').trim()
  return text.length > 46 ? `${text.slice(0, 46)}…` : text
}

/** 当前挂的是否为产出型技能（`pkg:name`）。 */
const activePackageName = computed(() => (activeSkill.value?.startsWith('pkg:')
  ? activeSkill.value.slice(4) : null))

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
 * 胶囊条只展示「用户上传」的附件（2026-10-27）：平台产物（source === 'platform'，
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

/** 胶囊状态文案（SKIPPED 必须显式「未识别」，不能画成加载中）。 */
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
      return '未识别'
    default:
      return ''
  }
}

function attachmentChipClass(item: ChatAttachmentItem): string {
  const status = (item.parseStatus ?? '').toLowerCase()
  const base = status ? `attachment-chip--${status}` : ''
  // ATT-3：未参与本轮时叠加「降级态」（灰字 + 无底色；不用删除线，否则像已删除）
  return isAttachmentExcluded(item) ? `${base} attachment-chip--excluded`.trim() : base
}

function attachmentChipTitle(item: ChatAttachmentItem): string {
  const size = item.fileSize ? `${(item.fileSize / 1024).toFixed(0)}KB` : ''
  const reason = item.parseError ? `；${item.parseError}` : ''
  const participation = isAttachmentExcluded(item) ? '本轮不参与（点击恢复）' : '本轮参与（点击取消）'
  return `${item.fileName}${size ? ' · ' + size : ''}${reason} · ${participation}`
}

// 编辑已发送消息（2026-10-19，对齐 ChatGPT/DeepSeek/Kimi「编辑消息」交互）：
// editingMsgId 非空 = 该消息气泡处于行内编辑态；editingDraft 为 textarea 草稿；editingSaving 防重复提交
const editingMsgId = ref<string>('')
const editingDraft = ref('')
const editingSaving = ref(false)

// 输入优化（PromptEnhancer）：先预览、用户自行回填；不自动发送、不自动覆盖原文本输入框
const enhancing = ref(false)
const enhancePanelVisible = ref(false)
const enhancedDraft = ref('')

// S1 Chat SSE 流式：streamText 为流式回复增量全文（60ms 节流写入），done 后拉 detail 收敛清空
const streamText = ref('')
let streamBuffer = ''
let streamFlushTimer: number | null = null
// CLARIFY 模式流式：LLM 输出「正文 + ```json 块」——检测到围栏后截断显示（只展示正文打字机，
// JSON 结构不裸露，done 后收敛为卡片/终稿）；CHAT 模式不截断（正文可能含用户要求的代码块）
let jsonFenceSeen = false

// 思考过程流式（2026-10-18）：推理模型的 reasoning 帧独立展示，与正文分区渲染
// reasoningActive 标记「当前是否仍处于思考阶段」（正文首帧到达即置 false）
const thinkingText = ref('')
let reasoningBuffer = ''
let reasoningFlushTimer: number | null = null
let reasoningActive = false
const thinkingExpanded = ref(true)
// 过程步骤（2026-10-18）：联网检索等平台动作，与思考内容按到达顺序混排展示
// （对齐 DeepSeek「已搜索 N 个网页」/ ChatGPT「Searching the web」）
const processSteps = ref<string[]>([])
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
    // 2026-10-18：过程数据（思考 + 检索步骤）随 payload 落库 ⇒ 历史消息也能回看，
    // 这是「思考过程在回复完成后消失」的根治点（此前只存在于 SSE 流里）
    const process: RoundProcessTrace | null = p?.process ?? null
    // 2026-10-27：技能产物（选 archify 等产出型技能时，后端在对话轮内直接跑出文件）
    const artifact: ClarifyArtifactRef | null = p?.artifact ?? null
    if (!structured) return { msg, structured: null, intro: msg.content, selections: null, webSearch, process, artifact, liked: false }
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

// 2026-10-27：会话内平台生成的产物集合（技能执行产出；从全部消息 payload 收集，
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

// 产物侧栏可见性：点击顶部「文件」按钮切换；会话切换时自动收起
const artifactPanelOpen = ref(false)
const artifactToggleTitle = computed(() => {
  if (!conversationArtifacts.value.length) return '暂无产物（平台生成的附件会出现在这里）'
  return artifactPanelOpen.value ? '收起产物栏' : `查看产物（${conversationArtifacts.value.length} 个）`
})
const artifactPreview = ref<{ blobUrl: string; mode: 'iframe' | 'markdown' | 'text'; textContent: string; fileName: string } | null>(null)
const artifactPreviewLoading = ref(false)
let artifactBlobUrl = ''

watch(
  () => activeId,
  () => {
    artifactPanelOpen.value = false
    closeArtifactPreview()
  }
)

/** 切换产物侧栏开关；有产物时打开，无产物时仅收起（按钮灰态时也允许展开查看已加载历史）。 */
function toggleArtifactPanel() {
  artifactPanelOpen.value = !artifactPanelOpen.value
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
async function previewArtifactInPanel(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  const name = item.artifact.fileName || ''
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : ''
  const textLike = ['md', 'txt', 'log', 'json', 'xml', 'yml', 'yaml', 'csv', 'html', 'htm', 'svg',
    'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx'].includes(ext)
  closeArtifactPreview()
  artifactPreviewLoading.value = true
  try {
    if (textLike) {
      const resp = await clarifyApi.attachmentPreviewText(item.artifact.attachmentId)
      artifactPreview.value = { blobUrl: '', mode: 'text', textContent: resp.data, fileName: name }
    } else {
      const resp = await clarifyApi.attachmentPreviewBlob(item.artifact.attachmentId)
      const mime = (resp.headers['content-type'] as string | undefined) || 'application/octet-stream'
      artifactBlobUrl = URL.createObjectURL(new Blob([resp.data], { type: mime }))
      artifactPreview.value = { blobUrl: artifactBlobUrl, mode: 'iframe', textContent: '', fileName: name }
    }
  } catch {
    // 业务异常（413 / 404 / 500）由 axios 拦截器统一弹错；预览区置空
    artifactPreview.value = null
  } finally {
    artifactPreviewLoading.value = false
  }
}

/**
 * 下载产物（2026-10-27）：必须走 blob 通道 —— 裸 <a href> 的浏览器导航请求
 * 不带 X-Admin-Token，会被 AuthInterceptor 401 拒（已实测 11:39:07 日志）。
 * 经 request 拦截器带鉴权头拉 blob → 临时 <a download> 落盘 → revokeObjectURL。
 */
async function downloadArtifact(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  try {
    const resp = await clarifyApi.attachmentDownloadBlob(item.artifact.attachmentId)
    saveBlobResponse(resp, item.artifact.fileName || 'attachment')
  } catch {
    // 拦截器已统一弹错
  }
}

/**
 * 新标签页打开产物（2026-10-27）：同样走 blob 通道避免 401。
 * 拉 blob → createObjectURL → window.open（Content-Type 已由响应头决定，text/html 会正常渲染）
 * → 延迟 revoke（给新标签页留出加载时间）。
 */
async function openArtifactInNewTab(item: { artifact: ClarifyArtifactRef; msgId: string; msgSeq: number }) {
  try {
    const resp = await clarifyApi.attachmentPreviewBlob(item.artifact.attachmentId)
    const mime = (resp.headers['content-type'] as string | undefined) || 'application/octet-stream'
    const url = URL.createObjectURL(new Blob([resp.data], { type: mime }))
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch {
    // 拦截器已统一弹错
  }
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
  // 跨会话切换：临时批次只属于「尚未建会」的那一轮，换会话即作废
  clientToken.value = null
  attachments.value = []
  try {
    detail.value = await clarifyApi.detail(sid)
    scrollToBottom()
  } catch { /* 拦截器已弹错 */ }
  void refreshAttachments()
}

function startNew() {
  if (sending.value) return
  closeEnhancePanel()
  activeId.value = null
  detail.value = null
  input.value = ''
  // 新会话从零开始：清掉上一轮的临时批次与胶囊（避免把旧文件带进新需求）
  clientToken.value = null
  attachments.value = []
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
  const raw = input.value.trim()
  if (!raw) {
    if (activeSkill.value && !sending.value) {
      ElMessage.warning(`已选「${activeSkillLabel.value}」，先在输入框里写一句需求再按 Enter`)
    }
    return
  }
  if (sending.value) return
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
  const pkgName = activePackageName.value
  const text = pkgName
    ? `[skill:${pkgName}] ${raw}`
    : (activeSkill.value === 'task' ? `/task ${raw}` : raw)
  const skill = activeSkill.value
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
  // 气泡展示用户原文（不带 /task 前缀），保证斜杠语法不出现在可见面
  pendingText.value = raw
  sending.value = true
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
    // 流式分流（2026-10-18 补全新会话）：
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
      // 回填用户原文而非 /task 组合文本，并把技能胶囊一并恢复，重试时语义不丢
      const msgs = detail.value?.messages ?? []
      const lastIsSameUserText = msgs.length > 0
        && msgs[msgs.length - 1].role === 'user'
        && (msgs[msgs.length - 1].content === text || msgs[msgs.length - 1].content === raw)
      if (!lastIsSameUserText) {
        input.value = raw
        activeSkill.value = skill
      }
    }
  } finally {
    pendingText.value = ''
    sending.value = false
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
  const result = await clarifyApi.create(text, plannerId, webSearchEnabled.value, clientToken.value)
  detail.value = result
  activeId.value = String(result.conversation.id)
  // 建会已认领临时批次：清 token（避免下一轮误用）并按会话重新拉附件清单
  clientToken.value = null
  void refreshAttachments()
  loadList()
  scrollToBottom()
  if (result.conversation.status === 'FINALIZED' && result.conversation.taskId != null) {
    await triggerTaskReview(String(result.conversation.taskId))
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
  // 思考阶段结束、正文开始：思考区自动折叠（用户仍可点「展开」回看）
  if (reasoningActive) {
    reasoningActive = false
    thinkingExpanded.value = false
  }
  // CHAT 模式：纯文本正文不截断（正文可能含用户要求的 ``` 代码块）
  if (!isChatMode.value) {
    if (jsonFenceSeen) return
    streamBuffer += token
    const shown = streamText.value + streamBuffer
    const fence = shown.indexOf('```')
    if (fence !== -1) {
      // 截断：只保留 json 围栏前的正文，后续 token 全部忽略（done 后收敛卡片/终稿）
      streamText.value = shown.slice(0, fence)
      streamBuffer = ''
      jsonFenceSeen = true
      if (streamFlushTimer != null) {
        window.clearTimeout(streamFlushTimer)
        streamFlushTimer = null
      }
      return
    }
  } else {
    streamBuffer += token
  }
  if (streamFlushTimer == null) {
    streamFlushTimer = window.setTimeout(flushStreamText, 60)
  }
}

// 思考过程增量（reasoning 帧）：独立缓冲 + 更短节流（思考流高频、用户希望「看到在动」）
function appendStreamReasoning(text: string) {
  reasoningActive = true
  reasoningBuffer += text
  if (reasoningFlushTimer == null) {
    reasoningFlushTimer = window.setTimeout(() => {
      reasoningFlushTimer = null
      if (reasoningBuffer) {
        thinkingText.value += reasoningBuffer
        reasoningBuffer = ''
        scrollToBottom()
      }
    }, 80)
  }
}

function flushReasoningText() {
  if (reasoningFlushTimer != null) {
    window.clearTimeout(reasoningFlushTimer)
    reasoningFlushTimer = null
  }
  if (reasoningBuffer) {
    thinkingText.value += reasoningBuffer
    reasoningBuffer = ''
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

// 已有会话：普通消息与结构化卡提交统一走 SSE 流式（token 增量渲染 + done 后收敛）
// ★2026-10-18 修复：结构化卡提交此前走同步 clarifyApi.send —— 而同步链路要先跑完
// 联网搜索（数秒~数十秒）+ 完整 LLM 输出才一次性返回，用户全程无反馈；改走同一 SSE
// 通道后可见打字机增量（后端 runStreamRound 早已支持 selections 参数）。
async function streamSendActiveMessage(id: LongId, text: string,
                                      selections: ClarifySelection[] | null = null) {
  resetStreamState()
  const abort = new AbortController()
  streamAbort.value = abort
  try {
    await streamSendConversation(id, text, selections, {
      onToken: appendStreamToken,
      onReasoning: appendStreamReasoning,
      onProgress: appendProcessStep,
      onDone: () => { void settleStream(id, true) },
      onError: (msg) => {
        ElMessage.error(msg)
        void settleStream(id, false)
      },
      onAbort: () => { void settleStream(id, false) }
    }, abort.signal)
  } finally {
    streamAbort.value = null
    cleanupStreamState()
  }
}

/**
 * 流式发送「自适应」入口（2026-10-18）：activeId 为空走 streamCreate（首帧 session 拿新 id），
 * 否则走 streamSend。两条通道共用同一套流式状态与收敛逻辑，避免「新会话无流式」的分叉。
 */
async function streamSendOrCreate(text: string, plannerId: string | null,
                                  attachmentIds: string[] | null = null) {
  resetStreamState()
  const existingId = activeId.value
  // 新会话：id 由首帧 session 事件带回；done 前可能仍为 null，收敛时以 streamCreatedId 为准
  let streamCreatedId: LongId | null = existingId
  const settleId = () => streamCreatedId
  // 停止生成：新建会话与老会话共用同一个 AbortController（streamCreate 首帧回 id 后仍可中止）
  const abort = new AbortController()
  streamAbort.value = abort
  try {
    if (existingId != null) {
      await streamSendConversation(existingId, text, null, {
        onToken: appendStreamToken,
        onReasoning: appendStreamReasoning,
        onProgress: appendProcessStep,
        onDone: () => { void settleStream(existingId, true) },
        onError: (msg) => {
          ElMessage.error(msg)
          void settleStream(existingId, false)
        },
        onAbort: () => { void settleStream(existingId, false) }
      }, abort.signal, attachmentIds)
    } else {
      await streamCreateConversation(text, plannerId, webSearchEnabled.value, {
        onSession: (newId) => {
          streamCreatedId = newId
          // 立刻切到新会话（左侧列表随后由 settleStream 的 detail 拉取刷新）
          activeId.value = newId
          // 后端已在建会时认领临时批次：清 token 并按会话重拉附件
          clientToken.value = null
          void refreshAttachments()
        },
        onToken: appendStreamToken,
        onReasoning: appendStreamReasoning,
        onProgress: appendProcessStep,
        onDone: () => {
          const id = settleId()
          if (id == null) { sending.value = false; return }
          void settleStream(id, true)
        },
        onError: (msg) => {
          ElMessage.error(msg)
          const id = settleId()
          if (id != null) void settleStream(id, false)
        },
        onAbort: () => {
          const id = settleId()
          if (id != null) {
            void settleStream(id, false)
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
    streamAbort.value = null
    cleanupStreamState()
  }
}

/** 过程步骤增量（progress 帧）：一行一条，直接追加并保持展开（用户要看到「在做什么」）。 */
function appendProcessStep(text: string) {
  if (!text) return
  // 同一行重复推送（正在检索 → 已检索）不去重：它们语义不同，按序展示更符合「过程」直觉
  processSteps.value = [...processSteps.value, text]
  scrollToBottom()
}

/** 过程区是否仍在进行（思考流未结束且正文未开始）——决定转圈图标与是否强制展开。 */
const isProcessRunning = computed(() =>
  !streamText.value && (reasoningActive || processSteps.value.length > 0)
)

/** 过程区标题：进行中显示「思考中…（已 N 步）」，结束后显示「思考与检索过程」。 */
const processHeadline = computed(() => {
  const stepCount = processSteps.value.length
  if (isProcessRunning.value) {
    return stepCount > 0 ? `思考中…（已 ${stepCount} 步）` : '思考中…'
  }
  return stepCount > 0 ? `思考与检索过程（${stepCount} 步）` : '思考过程'
})

/** 流式状态复位（正文/思考/步骤/围栏标记/缓冲）。 */
function resetStreamState() {
  streamText.value = ''
  thinkingText.value = ''
  thinkingExpanded.value = true
  processSteps.value = []
  streamBuffer = ''
  reasoningBuffer = ''
  reasoningActive = false
  jsonFenceSeen = false
}

/** 流式状态清理（定时器/缓冲/发送态）。 */
function cleanupStreamState() {
  if (streamFlushTimer != null) {
    window.clearTimeout(streamFlushTimer)
    streamFlushTimer = null
  }
  flushReasoningText()
  streamBuffer = ''
  reasoningBuffer = ''
  pendingText.value = ''
  sending.value = false
}

/**
 * 停止生成（2026-10-19）：abort 当前流式连接。前端中断 SSE → 后端 dispose LLM 订阅，
 * 生成立即停止且未完成回复不落库；随后 settleStream 拉详情（user 消息已落库 → 出现重试条）。
 */
function stopGenerating() {
  if (!streamAbort.value) return
  streamAbort.value.abort()
  ElMessage.info('已停止生成')
}

// ── 编辑已发送消息（2026-10-19，对齐 ChatGPT/DeepSeek/Kimi）──

/** 进入编辑态：预填原文。发送中/终稿中/已放弃不可编辑。 */
function startEdit(msg: { id: string | number; content?: string }) {
  if (sending.value || finalizing.value) return
  editingMsgId.value = String(msg.id)
  editingDraft.value = msg.content ?? ''
}

/** 取消编辑：清空编辑态，原文不动。 */
function cancelEdit() {
  if (editingSaving.value) return
  editingMsgId.value = ''
  editingDraft.value = ''
}

/**
 * 保存并重新生成：调后端「编辑消息 + 截断重跑」。编辑期间禁用（防重复提交），
 * 完成后收起编辑态并用后端返回的完整详情刷新界面。失败保留编辑态让用户重试。
 */
async function submitEdit(msg: { id: string | number }) {
  const text = editingDraft.value.trim()
  if (!text || editingSaving.value) return
  if (activeId.value == null) { cancelEdit(); return }
  editingSaving.value = true
  try {
    const result = await clarifyApi.editMessage(activeId.value, String(msg.id), text)
    detail.value = result
    editingMsgId.value = ''
    editingDraft.value = ''
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
  if (sending.value) return
  input.value = ''
  // 气泡展示用户意图文案，不再回显 /planner 语法
  pendingText.value = extra || '请帮我整理一份技术方案'
  sending.value = true
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
// 2026-10-18：改走 SSE 流式（与普通消息同一通道）——原同步链路要等搜索 + 完整 LLM 输出，
// 用户体感是「卡住很久然后一次性刷出」，且看不到思考过程。
async function handleStructuredSubmit(payload: { text: string; selections: ClarifySelection[] }) {
  const id = activeId.value
  if (id == null || sending.value) return
  pendingText.value = payload.text
  sending.value = true
  scrollToBottom()
  try {
    await streamSendActiveMessage(id, payload.text, payload.selections)
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
  // 附件清单：仅在已有会话时拉（新会话为空，等用户上传后才出现胶囊）
  if (activeId.value != null) void refreshAttachments()
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

/* ── 2026-10-27 产物侧栏（平台生成的附件，对齐 Kimi 右侧可收缩面板） ── */
/* 顶部「产物」开关按钮（header-actions 内） */
.artifact-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface-elevated);
  color: var(--ha-ink-secondary);
  font-size: 12px;
  cursor: pointer;
  position: relative;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out);
}
.artifact-toggle-btn:hover {
  border-color: var(--ha-primary-light);
  color: var(--ha-primary);
}
.artifact-toggle-btn.is-active {
  border-color: var(--ha-primary);
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
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
  width: 300px;
  flex: 0 0 300px;
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
.artifact-sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ha-ink);
}
.artifact-sidebar-count {
  font-size: 12px;
  color: var(--ha-muted);
}
.artifact-sidebar-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
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
/* 产物生成提示条（2026-10-27）：产物已移入右侧侧栏，消息流内仅留一条轻提示，
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
 * 助手回复 = **文档流**，不是卡片（2026-10-18 对齐 DeepSeek / Kimi）
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

/* 编辑消息（2026-10-19，对齐 ChatGPT/DeepSeek）：
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

/* 思考过程区（2026-10-23 重做）：
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
/* 拖拽落区高亮：让用户知道可以松手 */
.composer-area.is-dragover {
  border-color: var(--ha-primary) !important;
  box-shadow: 0 0 0 2px var(--ha-primary-light);
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

/* 「＋」能力入口（对齐 WorkBuddy：一个 + 收纳可选能力） */
.plus-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--ha-border-light);
  border-radius: 50%;
  background: var(--ha-surface);
  color: var(--ha-muted);
  cursor: pointer;
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out),
              background var(--ha-duration-fast) var(--ha-ease-out),
              color var(--ha-duration-fast) var(--ha-ease-out);
}
.plus-btn:hover:not(:disabled) {
  border-color: var(--ha-primary);
  background: var(--ha-primary-light);
  color: var(--ha-primary);
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
  /* 窄屏：产物侧栏改为底部全宽抽屉（column 布局下不再挤占消息区横向空间） */
  .artifact-sidebar {
    width: 100%;
    flex: 0 0 auto;
    max-height: 40vh;
    border-left: none;
    border-top: 1px solid var(--ha-border-light);
  }
}

/* 下拉菜单中的危险项：与 TaskList/SubTaskList 同构 */
.dropdown-danger { color: var(--ha-danger); }
</style>
