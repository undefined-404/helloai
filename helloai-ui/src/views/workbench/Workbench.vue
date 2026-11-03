<template>
  <div class="wb-page">
    <!-- ══════════ 中间主区：顶部标题栏 + 舞台 + 输入区 ══════════ -->
    <section class="wb-main">
      <header class="wb-topbar">
        <div class="wb-topbar-id">
          <span class="wb-topbar-avatar">
            <el-icon><MagicStick /></el-icon>
          </span>
          <div class="wb-topbar-text">
            <div class="wb-topbar-name">
              <span class="wb-topbar-name-text">{{ profile.name }}</span>
              <span v-if="currentTitle" class="wb-topbar-conv-chip">{{ currentTitle }}</span>
            </div>
            <div class="wb-topbar-sub">{{ profile.identity }} · {{ agentLabel }}</div>
          </div>
        </div>
        <div class="wb-topbar-ops">
          <span class="wb-model-chip" :title="`当前助手：${agentLabel}`">
            <span class="wb-model-dot" />
            <span>{{ agentLabel }}</span>
          </span>
          <el-dropdown trigger="click" @command="onHistoryCommand">
            <button class="wb-icon-btn" type="button" title="历史对话">
              <el-icon><Clock /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="c in conversations"
                  :key="String(c.id)"
                  :command="String(c.id)"
                >
                  {{ displayTitle(c.title) || '未命名对话' }}
                </el-dropdown-item>
                <el-dropdown-item v-if="!conversations.length" disabled>
                  暂无历史对话
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="onTopCommand">
            <button class="wb-icon-btn" type="button" title="更多操作">
              <el-icon><MoreFilled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="new">
                  <el-icon><Plus /></el-icon>开启新话题
                </el-dropdown-item>
                <el-dropdown-item command="chat">
                  <el-icon><ChatDotRound /></el-icon>打开需求对话页
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 舞台：空闲态 / 对话态 -->
      <div ref="stageEl" class="wb-stage">
        <div v-if="isIdle" class="wb-idle">
          <div class="wb-idle-icon">
            <el-icon><MagicStick /></el-icon>
          </div>
          <h1 class="wb-idle-title">你好，今天想让 AI 帮你做什么？</h1>
          <p class="wb-idle-desc">
            描述你想做的事情，或直接向 AI 助手提问——它会解答疑问、帮你梳理思路。
          </p>
          <p class="wb-idle-desc">
            说「整理成方案」可把讨论转成可落地方案；信息足够时可生成任务终稿并自动拆解。
          </p>
          <div class="wb-suggestions">
            <button
              v-for="s in SUGGESTIONS"
              :key="s.title"
              type="button"
              class="wb-suggestion"
              @click="useSuggestion(s.prompt)"
            >
              <span class="wb-suggestion-icon">
                <el-icon><component :is="s.icon" /></el-icon>
              </span>
              <span class="wb-suggestion-title">{{ s.title }}</span>
              <span class="wb-suggestion-desc">{{ s.desc }}</span>
            </button>
          </div>
        </div>

        <div v-else class="wb-thread">
          <article
            v-for="row in renderMessages"
            :key="String(row.msg.id)"
            class="wb-msg"
            :class="row.msg.role === 'user' ? 'is-user' : 'is-assistant'"
          >
            <span v-if="row.msg.role === 'assistant'" class="wb-msg-avatar">
              <el-icon><MagicStick /></el-icon>
            </span>
            <div class="wb-msg-col">
              <!-- 联网搜索查验条（历史消息）：与需求对话同一组件，payload 有 trace 才渲染 -->
              <WebSearchBar v-if="row.webSearch" :trace="row.webSearch" />
              <!-- 历史过程区：思考 + 检索步骤随 payload 落库，默认折叠、可展开回看 -->
              <div
                v-if="row.process && (row.process.thinking || (row.process.steps && row.process.steps.length))"
                class="wb-process"
              >
                <div class="wb-process-head" @click="toggleHistoryProcess(row.msg.id)">
                  <span>{{ historyProcessHeadline(row.process) }}</span>
                  <span class="wb-process-toggle">
                    {{ expandedHistoryProcess === String(row.msg.id) ? '收起' : '展开' }}
                  </span>
                </div>
                <div v-if="expandedHistoryProcess === String(row.msg.id)" class="wb-process-body">
                  <div
                    v-for="(step, si) in row.process.steps || []"
                    :key="`hstep-${si}`"
                    class="wb-process-step"
                  >
                    {{ step }}
                  </div>
                  <div v-if="row.process.thinking" class="wb-process-thinking">
                    <MarkdownView compact :content="row.process.thinking" />
                  </div>
                </div>
              </div>
              <div class="wb-bubble" :class="{ 'is-user': row.msg.role === 'user' }">
                <MarkdownView
                  v-if="row.msg.role === 'assistant' && row.msg.content.trim()"
                  :content="row.msg.content"
                />
                <div v-else-if="row.msg.role === 'assistant'" class="wb-msg-empty">
                  助手未返回内容（可能是模型服务繁忙），请重试。
                </div>
                <template v-else>{{ row.msg.content }}</template>
              </div>
              <div v-if="row.msg.role === 'user'" class="wb-msg-meta">
                <span class="wb-msg-time">{{ messageTime(row.msg.createTime) }}</span>
                <span class="wb-msg-me"><el-icon><User /></el-icon></span>
              </div>
              <div v-if="row.msg.role === 'assistant'" class="wb-msg-actions">
                <button class="wb-act" type="button" @click="copyMessage(row.msg)">
                  <el-icon><CopyDocument /></el-icon>复制
                </button>
                <button class="wb-act" type="button" @click="toTask(row.msg)">
                  <el-icon><Tickets /></el-icon>转为任务
                </button>
              </div>
            </div>
          </article>
          <article v-if="sending" class="wb-msg is-assistant">
            <span class="wb-msg-avatar">
              <el-icon><MagicStick /></el-icon>
            </span>
            <div class="wb-msg-col">
              <!-- 过程区：检索步骤 + 推理思考混排；正文开始后自动折叠，可展开回看 -->
              <div v-if="processSteps.length || thinkingText" class="wb-process">
                <div class="wb-process-head" @click="thinkingExpanded = !thinkingExpanded">
                  <el-icon v-if="isProcessRunning" class="is-loading"><Loading /></el-icon>
                  <span>{{ processHeadline }}</span>
                  <span class="wb-process-toggle">
                    {{ thinkingExpanded || isProcessRunning ? '收起' : '展开' }}
                  </span>
                </div>
                <div v-if="thinkingExpanded || isProcessRunning" class="wb-process-body">
                  <div
                    v-for="(step, si) in processSteps"
                    :key="`step-${si}`"
                    class="wb-process-step"
                  >
                    {{ step }}
                  </div>
                  <div v-if="thinkingText" class="wb-process-thinking">
                    <MarkdownView compact :content="thinkingText" />
                  </div>
                </div>
              </div>
              <div v-if="streamText" class="wb-bubble">
                <MarkdownView :content="streamText" />
              </div>
              <div v-else-if="!thinkingText && !processSteps.length" class="wb-typing">正在思考…</div>
            </div>
          </article>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="wb-composer-wrap">
        <div class="wb-composer">
          <!-- 技能 / 附件 胶囊条（选技能只挂载不发送；附件上传后即时可见） -->
          <div v-if="chips.length" class="wb-chips">
            <span
              v-for="chip in chips"
              :key="chip.key"
              class="wb-chip"
              :class="chip.kind"
              :title="chip.title"
            >
              <el-icon><component :is="chip.icon" /></el-icon>
              <span class="wb-chip-name">{{ chip.name }}</span>
              <span v-if="chip.state" class="wb-chip-state">{{ chip.state }}</span>
              <button
                v-if="chip.remove"
                class="wb-chip-x"
                type="button"
                title="移除"
                @click="chip.remove()"
              >
                <el-icon><Close /></el-icon>
              </button>
            </span>
          </div>

          <el-input
            v-model="input"
            class="wb-input"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            resize="none"
            placeholder="把拆解出来的任务直接派发给空闲 Agent，并同步到审查中心"
            @keydown.enter.exact.prevent="onEnter"
          />
          <div class="wb-toolbar">
            <div class="wb-toolbar-left">
              <!-- 「＋」能力入口：一个 + 收纳「技能」与「文件」两组 -->
              <el-popover
                v-model:visible="plusMenuVisible"
                placement="top-start"
                :width="300"
                trigger="click"
                popper-class="wb-plus-popover"
              >
                <template #reference>
                  <button class="wb-plus-btn" type="button" title="技能与文件">
                    <el-icon><Plus /></el-icon>
                  </button>
                </template>
                <div class="wb-plus-menu">
                  <div class="wb-plus-group-title">技能</div>
                  <button
                    type="button"
                    class="wb-plus-item"
                    :class="{ 'is-active': activeSkill === 'planner' }"
                    @click="pickSkill('planner')"
                  >
                    <span class="wb-plus-icon"><el-icon><EditPen /></el-icon></span>
                    <span class="wb-plus-text">
                      <span class="wb-plus-name">方案整理</span>
                      <span class="wb-plus-desc">逐轮问清边界与取舍，再产出可执行方案</span>
                    </span>
                    <span v-if="activeSkill === 'planner'" class="wb-plus-check">
                      <el-icon><Check /></el-icon>
                    </span>
                  </button>
                  <button
                    type="button"
                    class="wb-plus-item"
                    :class="{ 'is-active': activeSkill === 'task' }"
                    @click="pickSkill('task')"
                  >
                    <span class="wb-plus-icon"><el-icon><MagicStick /></el-icon></span>
                    <span class="wb-plus-text">
                      <span class="wb-plus-name">直接拆解</span>
                      <span class="wb-plus-desc">跳过澄清，按当前描述直接产出方案并派任务</span>
                    </span>
                    <span v-if="activeSkill === 'task'" class="wb-plus-check">
                      <el-icon><Check /></el-icon>
                    </span>
                  </button>
                  <button
                    v-for="pkg in packageSkills"
                    :key="pkg.name"
                    type="button"
                    class="wb-plus-item"
                    :class="{ 'is-active': activeSkill === `pkg:${pkg.name}` }"
                    @click="pickSkill(`pkg:${pkg.name}`)"
                  >
                    <span class="wb-plus-icon"><el-icon><Grid /></el-icon></span>
                    <span class="wb-plus-text">
                      <span class="wb-plus-name">{{ pkg.name }}</span>
                      <span class="wb-plus-desc">{{ shortSkillDesc(pkg.description) }}</span>
                    </span>
                    <span v-if="activeSkill === `pkg:${pkg.name}`" class="wb-plus-check">
                      <el-icon><Check /></el-icon>
                    </span>
                  </button>
                  <div class="wb-plus-group-title">文件</div>
                  <button type="button" class="wb-plus-item" @click="pickFiles">
                    <span class="wb-plus-icon"><el-icon><Document /></el-icon></span>
                    <span class="wb-plus-text">
                      <span class="wb-plus-name">添加文件</span>
                      <span class="wb-plus-desc">PDF / Word / 文本 / 代码；单文件 20MB，单次 5 个</span>
                    </span>
                  </button>
                </div>
              </el-popover>
              <input
                ref="fileInputRef"
                type="file"
                multiple
                class="wb-file-input"
                accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.json,.log,.html,.htm"
                @change="onFileInputChange"
              >

              <!-- 会话级联网搜索开关（新会话与进行中会话都可改） -->
              <div class="wb-web-search">
                <el-tooltip
                  content="开启后每轮先联网检索资料，再把资料作为依据作答"
                  placement="top"
                  :show-after="300"
                >
                  <span class="wb-web-search-label">
                    <el-icon class="wb-web-search-icon"><Connection /></el-icon>
                    <span>联网搜索</span>
                  </span>
                </el-tooltip>
                <el-switch
                  v-model="webSearchEnabled"
                  size="small"
                  inline-prompt
                  active-text="开"
                  inactive-text="关"
                />
              </div>

              <!-- 规划者 / 调度策略 -->
              <el-dropdown trigger="click" @command="onPlannerCommand">
                <button class="wb-tool-btn" type="button">
                  <el-icon><User /></el-icon>
                  <span>{{ plannerLabel }}</span>
                  <el-icon class="wb-caret"><ArrowDown /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="auto">优先空闲 Agent（系统自动）</el-dropdown-item>
                    <el-dropdown-item
                      v-for="o in plannerOptions"
                      :key="String(o.id)"
                      :command="String(o.id)"
                      :disabled="!o.selectable"
                    >
                      {{ plannerOptionLabel(o) }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <div class="wb-toolbar-right">
              <!-- 上下文窗口：点击展开五项明细 + 手动压缩（复用 RequirementChat 的上下文面板与压缩逻辑） -->
              <el-popover
                placement="top-end"
                :width="300"
                trigger="click"
                popper-class="wb-ctx-popover"
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
                  <div v-if="contextUsage" class="ctx-bar">
                    <div class="ctx-bar-fill" :style="{ width: contextRatioPct + '%' }" />
                  </div>
                  <template v-if="contextUsage">
                    <div class="ctx-row"><span>系统提示词</span><span>{{ contextUsage.systemPromptTokens }}</span></div>
                    <div class="ctx-row"><span>系统工具</span><span>{{ contextUsage.toolTokens }}</span></div>
                    <div class="ctx-row"><span>Skill</span><span>{{ contextUsage.skillTokens }}</span></div>
                    <div class="ctx-row"><span>消息</span><span>{{ contextUsage.messageTokens }}</span></div>
                    <div class="ctx-row"><span>其他</span><span>{{ contextUsage.otherTokens }}</span></div>
                    <div class="ctx-row ctx-total"><span>合计 / 窗口</span><span>{{ contextUsage.totalTokens }} / {{ contextUsage.contextSize }}</span></div>
                    <div class="ctx-meta">已压缩到 seq {{ contextUsage.compactedUntilSeq }} · 摘要 v{{ contextUsage.summaryVersion }}</div>
                  </template>
                  <div v-else class="ctx-empty">打开会话后可查看占用</div>
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    class="ctx-compact-btn"
                    :loading="compacting"
                    :disabled="activeId == null"
                    @click="compactContext"
                  >压缩上下文</el-button>
                </div>
              </el-popover>

              <!-- 优化输出：把当前输入改写为更清晰的结构化表达（回填输入框，不自动发送） -->
              <button
                class="wb-tool-btn is-icon is-optimize"
                type="button"
                title="优化输出"
                :disabled="!input.trim() || sending || enhancing"
                @click="optimizeInput"
              >
                <el-icon :class="{ 'is-loading': enhancing }"><MagicStick /></el-icon>
              </button>

              <!-- 语音输入：暂未开放，先置灰 -->
              <button
                class="wb-mic-btn"
                :class="{ 'is-recording': recording }"
                type="button"
                :title="micTitle"
                disabled
                @click="toggleMic"
              >
                <el-icon><Microphone /></el-icon>
              </button>

              <button
                class="wb-send-btn"
                :class="{ 'is-stop': sending }"
                type="button"
                :title="sending ? '停止生成，当前回复将不会保存' : '发送'"
                :disabled="!sending && !input.trim()"
                @click="sending ? stopGenerating() : send()"
              >
                <el-icon><component :is="sending ? CircleClose : ArrowUp" /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════ 右侧信息栏 ══════════ -->
    <aside class="wb-panel">
      <header class="wb-panel-head">
        <div class="wb-panel-title">
          <el-icon class="wb-panel-icon"><Grid /></el-icon>
          <span>工作台</span>
          <span class="wb-scope-chip">个人</span>
        </div>
        <div class="wb-panel-ops">
          <button class="wb-icon-btn" type="button" title="刷新" @click="refreshAll">
            <el-icon><Refresh /></el-icon>
          </button>
          <el-dropdown trigger="click" @command="onPanelCommand">
            <button class="wb-icon-btn" type="button" title="更多">
              <el-icon><MoreFilled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="refresh">刷新数据</el-dropdown-item>
                <el-dropdown-item command="tasks">打开任务管理</el-dropdown-item>
                <el-dropdown-item command="chat">打开需求对话页</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <div class="wb-panel-body">
        <!-- 助理状态卡（空闲态与对话态常驻） -->
        <div class="wb-status-card">
          <div class="wb-status-head">
            <div class="wb-status-id">
              <span class="wb-status-avatar"><el-icon><MagicStick /></el-icon></span>
              <div class="wb-status-text">
                <div class="wb-status-name">{{ profile.name }}</div>
                <div class="wb-status-model">{{ agentLabel }}</div>
              </div>
            </div>
            <span class="wb-ready-pill">
              <span class="wb-ready-dot" />{{ assistantReadyText }}
            </span>
          </div>
          <div class="wb-status-metric">
            <div class="wb-metric-row">
              <span class="wb-metric-label">可用余额</span>
              <span class="wb-metric-value">{{ balanceText }}</span>
            </div>
            <div v-if="activeId != null" class="wb-metric-row">
              <span class="wb-metric-label">上下文</span>
              <span class="wb-metric-value">
                <template v-if="contextUsage">
                  {{ contextUsage.totalTokens }} / {{ contextUsage.contextSize }}
                  <span class="wb-metric-unit">tokens（{{ Math.round(contextUsage.ratio * 100) }}%）</span>
                </template>
                <template v-else>加载中…</template>
              </span>
            </div>
          </div>
        </div>

        <!-- 任务概况 -->
        <section class="wb-section wb-section--overview">
          <div class="wb-section-head">
            <h3 class="wb-section-title">任务概况</h3>
          </div>
          <div class="wb-stat-grid">
            <div v-for="s in statCards" :key="s.label" class="wb-stat">
              <div class="wb-stat-label">{{ s.label }}</div>
              <div class="wb-stat-value">{{ s.value }}</div>
              <div class="wb-stat-hint">
                <span class="wb-stat-dot" :style="{ background: s.color }" />{{ s.hint }}
              </div>
            </div>
          </div>
        </section>

        <!-- 活跃 Agent -->
        <section class="wb-section wb-section--agents">
          <div class="wb-section-head">
            <h3 class="wb-section-title">活跃 Agent</h3>
            <span class="wb-section-count">{{ activeAgents.length }}</span>
            <span class="wb-section-link" @click="router.push('/agents')">管理</span>
          </div>
          <div v-if="!activeAgents.length" class="wb-empty">暂无可用 Agent</div>
          <div v-else class="wb-agent-list">
            <div v-for="a in visibleAgents" :key="String(a.agentId)" class="wb-agent" :title="agentTitle(a)">
              <span class="wb-agent-avatar" :class="agentRoleClass(a)">
                <el-icon><component :is="agentIcon(a)" /></el-icon>
              </span>
              <span class="wb-agent-name">{{ a.name }}</span>
              <span class="wb-agent-status" :class="agentStatusClass(a)">
                <span class="wb-agent-dot" />{{ agentStatusLabel(a.status) }}
              </span>
            </div>
          </div>
        </section>

        <!-- 最近任务 -->
        <section class="wb-section wb-section--tasks">
          <div class="wb-section-head">
            <h3 class="wb-section-title">最近任务</h3>
            <span class="wb-section-count">{{ recentTasks.length }}</span>
            <span class="wb-section-link" @click="router.push('/tasks')">查看全部</span>
          </div>
          <div v-if="!recentTasks.length" class="wb-empty">暂无进行中的任务</div>
          <div v-for="t in visibleTasks" :key="String(t.id)" class="wb-task-item">
            <span class="wb-task-dot" :style="{ background: taskStatusColor(t.status) }" />
            <div class="wb-task-body">
              <div class="wb-task-title">{{ t.title }}</div>
              <div class="wb-task-meta">
                <span class="wb-task-tag" :style="taskTagStyle(t.status)">{{ taskStatusLabel(t.status) }}</span>
                <span class="wb-task-time">{{ fmtTime(t.createTime) }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 最近产物 -->
        <section class="wb-section wb-section--artifacts">
          <div class="wb-section-head">
            <h3 class="wb-section-title">最近产物</h3>
            <span class="wb-section-count">{{ artifacts.length }}</span>
            <span class="wb-section-link" @click="router.push('/attachments')">更多</span>
          </div>
          <div v-if="!artifacts.length" class="wb-empty">暂无产物</div>
          <div v-for="a in visibleArtifacts" :key="a.source + '-' + String(a.id)" class="wb-artifact">
            <span class="wb-artifact-icon"><el-icon><Document /></el-icon></span>
            <div class="wb-artifact-body">
              <div class="wb-artifact-name">{{ a.fileName }}</div>
              <div class="wb-artifact-sub">
                {{ sizeLabel(a.fileSize) }}<template v-if="a.contextTitle"> · {{ a.contextTitle }}</template>
              </div>
            </div>
            <button class="wb-icon-btn" type="button" title="下载" @click="downloadArtifact(a)">
              <el-icon><Download /></el-icon>
            </button>
          </div>
        </section>

      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
/**
 * 个人工作台（PA-4 / PA-11 / PA-12）。
 *
 * <p>独立页面（不再复用需求对话页）：中间是「助理对话」——空闲态给建议入口，
 * 有内容后是消息流；右侧是聚合面板（助理状态 / 任务概况 / 活跃 Agent / 最近任务 / 最近产物）。
 * 布局与配色按 Calicat 设计稿「HelloAI 个人工作台」，色值收敛在本页的 --wb-* 变量里，
 * 不污染全局设计令牌。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, ArrowUp, ChatDotRound, Check, CircleClose, Clock, Close, Connection, CopyDocument, Cpu,
  Document, Download, EditPen, Grid, Histogram, Loading, MagicStick, Microphone, MoreFilled, Plus,
  Refresh, Stamp, Tickets, Tools, User
} from '@element-plus/icons-vue'
import MarkdownView from '@/components/MarkdownView.vue'
import WebSearchBar from '@/views/requirement/WebSearchBar.vue'
import { clarifyApi } from '@/api/clarify'
import { streamAssistantCreate, streamAssistantSend, type ChatStreamHandlers } from '@/api/chatStream'
import { skillApi, type ChatSkillOption } from '@/api/skill'
import { quotaApi, type QuotaSnapshot } from '@/api/quota'
import { promptEnhanceApi } from '@/api/promptEnhance'
import { streamWorkbenchEvents } from '@/api/workbenchStream'
import { useAuthStore } from '@/stores/auth'
import { saveBlobResponse } from '@/utils/download'
import { fmtTime, splitDateTime } from '@/utils/tableConfig'
import type {
  ArtifactItem, ChatAttachmentItem, ClarifyAssistantPayload, ClarifyConversationDetail,
  ContextUsage, LongId, PlannerOption, RequirementConversation, RequirementMessage,
  RoundProcessTrace, WebSearchTrace, Workbench, WorkbenchActiveAgent
} from '@/types'

const router = useRouter()
const auth = useAuthStore()

// ── 静态文案与快捷入口 ──
const SUGGESTIONS = [
  { title: '整理成方案', desc: '把讨论沉淀为方案', icon: Document, prompt: '帮我把刚才讨论的内容整理成一份可落地的方案，包含目标、步骤与验收标准。' },
  { title: '拆解任务', desc: '自动拆解可执行子任务', icon: Tickets, prompt: '请把这件事拆解成可执行的子任务清单，每个子任务标明负责角色与产出物。' },
  { title: '生成文档', desc: '产出终稿与交付物', icon: Tickets, prompt: '请基于我们的讨论生成一份可交付的文档终稿。' },
  { title: '指派 Agent', desc: '交给空闲 Agent 执行', icon: Cpu, prompt: '请把这些子任务指派给当前空闲的 Agent 执行。' }
]

// ── 「＋」面板：技能与文件 ──
/** 「＋」面板展开态（点技能项后先收起，光标回到输入框）。 */
const plusMenuVisible = ref(false)
/** 已挂载的技能：planner / task / pkg:<技能包名>；发送时随本轮一起提交。 */
const activeSkill = ref<string | null>(null)
/** 可选的产出型技能包（来自 /api/skills/chat-options，失败静默降级为空）。 */
const packageSkills = ref<ChatSkillOption[]>([])
let packageSkillsLoading = false

async function loadPackageSkills(): Promise<void> {
  if (packageSkillsLoading) return
  packageSkillsLoading = true
  try {
    packageSkills.value = await skillApi.chatOptions()
  } catch {
    // 静默降级：菜单只剩两个流程技能，不打断对话
  } finally {
    packageSkillsLoading = false
  }
}

watch(plusMenuVisible, (visible) => {
  if (visible) void loadPackageSkills()
})

function shortSkillDesc(desc: string | null | undefined): string {
  const text = (desc ?? '').trim()
  return text.length > 46 ? `${text.slice(0, 46)}…` : text
}

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
    ? '直接拆解：跳过澄清，按当前描述直接产出方案'
    : activePackageName.value
      ? `${activePackageName.value}：按该技能当场生成文件`
      : ''))

function pickSkill(kind: string) {
  plusMenuVisible.value = false
  activeSkill.value = kind
}

/** 移除已挂载的技能（胶囊上的 ×）。 */
function clearSkill() {
  activeSkill.value = null
}

// ── 会话附件（「＋→文件」）──
const attachments = ref<ChatAttachmentItem[]>([])
const uploading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
/** 新会话首句前的附件临时归属（前端 UUID），建会时由后端按 token 认领。 */
const clientToken = ref<string | null>(null)
let parsePollTimer: number | null = null

const MAX_FILES_PER_UPLOAD = 5
const MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024

/** 只展示用户上传的附件；平台产物进右侧「最近产物」。 */
const userAttachments = computed(() =>
  attachments.value.filter(a => a.source !== 'platform'))

function ensureClientToken(): string {
  if (clientToken.value) return clientToken.value
  const cryptoObj = window.crypto as Crypto | undefined
  clientToken.value = cryptoObj?.randomUUID
    ? cryptoObj.randomUUID()
    : `ct-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
  return clientToken.value
}

function pickFiles() {
  plusMenuVisible.value = false
  fileInputRef.value?.click()
}

function onFileInputChange(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  target.value = ''
  void uploadFiles(files)
}

/** 上传：前端先做「单文件 20MB / 单次 5 个」预校验（后端仍会再校验一遍）。 */
async function uploadFiles(files: File[]) {
  if (!files.length || uploading.value) return
  if (files.length > MAX_FILES_PER_UPLOAD) {
    ElMessage.warning(`单次最多 ${MAX_FILES_PER_UPLOAD} 个文件，本次已忽略多余的 ${files.length - MAX_FILES_PER_UPLOAD} 个`)
    files = files.slice(0, MAX_FILES_PER_UPLOAD)
  }
  const tooBig = files.find(f => f.size > MAX_FILE_SIZE_BYTES)
  if (tooBig) {
    ElMessage.error(`「${tooBig.name}」超过 20MB 上限`)
    return
  }
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  if (activeId.value != null) {
    form.append('conversationId', String(activeId.value))
  } else {
    form.append('clientToken', ensureClientToken())
  }
  uploading.value = true
  try {
    const saved = await clarifyApi.uploadAttachments(form)
    const known = new Set(userAttachments.value.map(a => String(a.id)))
    saved.forEach(item => {
      if (!known.has(String(item.id))) attachments.value.push(item)
    })
    scheduleParsePoll()
  } catch {
    // 拦截器已弹错（400 会带可读原因：超限 / 不支持的类型）
  } finally {
    uploading.value = false
  }
}

/** 移除附件（软删；后端幂等，前端先摘胶囊保证手感）。 */
async function removeAttachment(id: LongId) {
  const before = attachments.value
  attachments.value = attachments.value.filter(a => String(a.id) !== String(id))
  try {
    await clarifyApi.deleteAttachment(id)
  } catch {
    attachments.value = before
  }
}

/** 轮询解析状态：附件从 PENDING/PARSING 到 READY 通常 1~3s。 */
function scheduleParsePoll() {
  if (parsePollTimer != null) return
  let attempts = 0
  parsePollTimer = window.setInterval(async () => {
    attempts += 1
    await refreshAttachments()
    const pending = userAttachments.value.some(a => a.parseStatus === 'PENDING' || a.parseStatus === 'PARSING')
    if (!pending || attempts >= 15) {
      if (parsePollTimer != null) {
        window.clearInterval(parsePollTimer)
        parsePollTimer = null
      }
    }
  }, 2000)
}

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

/** 本轮参与的附件 id（空 = 无附件）。 */
function effectiveAttachmentIds(): string[] | null {
  const ids = userAttachments.value.map(a => String(a.id))
  return ids.length ? ids : null
}

/** 附件胶囊的状态文案（SKIPPED 必须显式「未识别」，不能画成加载中）。 */
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

/** 输入框上方的胶囊（技能 + 附件）。 */
const chips = computed(() => {
  const list: Array<{
    key: string
    kind: string
    icon: unknown
    name: string
    state: string
    title: string
    remove?: () => void
  }> = []
  if (activeSkill.value) {
    list.push({
      key: 'skill',
      kind: 'is-skill',
      icon: activeSkill.value === 'task' ? MagicStick : activePackageName.value ? Grid : EditPen,
      name: activeSkillLabel.value,
      state: '',
      title: activeSkillHint.value,
      remove: clearSkill
    })
  }
  for (const a of userAttachments.value) {
    list.push({
      key: `att-${String(a.id)}`,
      kind: 'is-file',
      icon: Document,
      name: a.fileName,
      state: attachmentStateText(a),
      title: `${a.fileName}${a.parseError ? ' · ' + a.parseError : ''}`,
      remove: () => { void removeAttachment(a.id) }
    })
  }
  if (uploading.value) {
    list.push({
      key: 'uploading',
      kind: 'is-file',
      icon: Document,
      name: '上传中…',
      state: '',
      title: '附件上传中'
    })
  }
  return list
})

// ── 数据状态 ──
const workbench = ref<Workbench | null>(null)
const detail = ref<ClarifyConversationDetail | null>(null)
const activeId = ref<LongId | null>(null)
const conversations = ref<RequirementConversation[]>([])
const plannerOptions = ref<PlannerOption[]>([])
const selectedPlannerId = ref<LongId | null>(null)
const contextUsage = ref<ContextUsage | null>(null)
const quota = ref<QuotaSnapshot[]>([])

const input = ref('')
const sending = ref(false)
const enhancing = ref(false)
const compacting = ref(false)
const stageEl = ref<HTMLElement | null>(null)
/** 会话级联网搜索开关（新会话与进行中会话都可改；助理侧默认关闭，与设计图一致）。 */
const webSearchEnabled = ref(false)
/** 语音输入进行中。 */
const recording = ref(false)

// ── SSE 流式状态（PA-11，2026-11-03）：与需求对话同一事件协议 ──
/** 已刷入界面的正文（60ms 节流后累加，避免高频帧打爆渲染）。 */
const streamText = ref('')
/** 思考过程全文（reasoning 帧，80ms 节流）。 */
const thinkingText = ref('')
const thinkingExpanded = ref(true)
/** 思考阶段标记：正文首帧到达即置 false，用于过程区的转圈与强制展开。 */
const reasoningActive = ref(false)
/** 平台动作步骤（progress 帧：检索发起 / 检索结果）。 */
const processSteps = ref<string[]>([])
let streamBuffer = ''
let reasoningBuffer = ''
let streamFlushTimer: number | null = null
let reasoningFlushTimer: number | null = null
/** 当前流式对话的 AbortController（「停止生成」/ 切换会话时中断）。 */
let chatStreamAbort: AbortController | null = null
/** 本轮流式回复所属会话（首帧 session 后确定）；用于收尾时只在仍停留该会话时回写详情。 */
let settlingConversationId: string | null = null

// ── 派生数据 ──
const profile = computed(() => workbench.value?.profile ?? {
  name: 'AI 助手',
  identity: '你的个人助理',
  model: '—',
  skillIds: [],
  greeting: ''
})

const messages = computed(() => detail.value?.messages ?? [])
/** 空闲态 = 没有会话或会话内没有任何消息（设计稿的空态区）。 */
const isIdle = computed(() => messages.value.length === 0)

/** assistant 消息 payload（webSearch / process 过程数据）；非 assistant 或解析失败返回 null。 */
function assistantPayloadOf(msg: RequirementMessage): ClarifyAssistantPayload | null {
  if (msg.role !== 'assistant' || !msg.payload) return null
  try {
    const parsed = JSON.parse(msg.payload)
    return parsed && typeof parsed === 'object' ? parsed as ClarifyAssistantPayload : null
  } catch { return null }
}

/** 消息流渲染行：历史消息的联网搜索 trace 与过程数据从 payload 重建。 */
const renderMessages = computed(() => messages.value.map((msg) => {
  const payload = assistantPayloadOf(msg)
  const webSearch: WebSearchTrace | null = payload?.webSearch ?? null
  const process: RoundProcessTrace | null = payload?.process ?? null
  return { msg, webSearch, process }
}))

/** 历史过程区展开态（同一时间只展开一条）。 */
const expandedHistoryProcess = ref('')
function historyProcessHeadline(process: RoundProcessTrace): string {
  const steps = process.steps?.length ?? 0
  const hasThinking = !!process.thinking
  if (steps > 0 && hasThinking) return `思考与检索过程（${steps} 步）`
  if (steps > 0) return `检索过程（${steps} 步）`
  return '思考过程'
}
function toggleHistoryProcess(id: LongId) {
  const key = String(id)
  expandedHistoryProcess.value = expandedHistoryProcess.value === key ? '' : key
}

/** 过程区是否仍在进行（正文未开始且仍有思考/步骤）——决定转圈图标与是否强制展开。 */
const isProcessRunning = computed(() =>
  !streamText.value && (reasoningActive.value || processSteps.value.length > 0))
/** 过程区标题：进行中显示已执行步数，结束后显示可回看的过程摘要。 */
const processHeadline = computed(() => {
  const stepCount = processSteps.value.length
  if (isProcessRunning.value) {
    return stepCount > 0 ? `思考中…（已 ${stepCount} 步）` : '思考中…'
  }
  return stepCount > 0 ? `思考与检索过程（${stepCount} 步）` : '思考过程'
})
const currentTitle = computed(() => {
  const t = detail.value?.conversation?.title
  return t ? displayTitle(t) : ''
})

const taskOverview = computed(() => workbench.value?.taskOverview ?? {
  inProgressTasks: 0,
  pendingDispatch: 0,
  pendingReview: 0,
  pendingManual: 0
})
const activeAgents = computed(() => workbench.value?.activeAgents ?? [])
const recentTasks = computed(() => workbench.value?.recentTasks ?? [])
const artifacts = computed(() => workbench.value?.artifacts ?? [])

// 右栏各模块固定高度，只展示设计稿约定的条数，其余走「管理 / 查看全部 / 更多」
const visibleAgents = computed(() => activeAgents.value.slice(0, 3))
const visibleTasks = computed(() => recentTasks.value.slice(0, 3))
const visibleArtifacts = computed(() => artifacts.value.slice(0, 2))

/** 任务概况 2×2 计数卡（设计稿：标题 + 数值 + 状态点说明）。 */
const statCards = computed(() => [
  { label: '总任务', value: taskOverview.value.inProgressTasks, hint: '执行中', color: '#7C5CFC' },
  { label: '待分配', value: taskOverview.value.pendingDispatch, hint: '等待分配', color: '#F59E0B' },
  { label: '待审查', value: taskOverview.value.pendingReview, hint: '等待复核', color: '#0EA5E9' },
  { label: '待人工', value: taskOverview.value.pendingManual, hint: '你的确认', color: '#EF4444' }
])

const canViewQuota = computed(() => auth.hasPermission('quota:view'))
/** 余额 = 金额型供应商求和（额度型百分比不掺入金额）。 */
const totalAmount = computed(() => {
  let sum = 0
  for (const p of quota.value) {
    if (p.available && p.quotaPercent == null && p.amount != null) sum += Number(p.amount)
  }
  return sum
})
const balanceText = computed(() => {
  if (!canViewQuota.value) return '—'
  if (!quota.value.length) return '—'
  if (totalAmount.value === 0) {
    return quota.value.some(p => p.available) ? '已连接' : '—'
  }
  return '¥ ' + totalAmount.value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
})
/** 助理状态徽标：空闲「已就绪」，一轮对话在跑时「进行中」。 */
const assistantReadyText = computed(() => sending.value ? '进行中' : '已就绪')

/** 空闲 Agent 数（未在跑、未在审）：设计稿的「N 台可用」。 */
const idleAgentCount = computed(() =>
  activeAgents.value.filter(a => a.status !== 'RUNNING' && a.status !== 'REVIEWING').length)

const plannerLabel = computed(() => {
  if (selectedPlannerId.value == null) return `优先空闲 Agent（${idleAgentCount.value} 台可用）`
  const opt = plannerOptions.value.find(o => String(o.id) === String(selectedPlannerId.value))
  return opt ? opt.name : '规划者'
})

/**
 * 头部/状态卡展示的 Agent：当前会话绑定的 Planner 优先（历史会话），
 * 新会话回落到输入区选中的 Planner；都没有时才用助理身份默认模型。
 */
const agentLabel = computed(() => {
  const plannerId = detail.value?.conversation?.plannerAgentId ?? selectedPlannerId.value
  if (plannerId == null) return profile.value.model
  const opt = plannerOptions.value.find(o => String(o.id) === String(plannerId))
  return opt ? opt.name : profile.value.model
})

/** 上下文占用百分比（0-100），用于输入区上下文按钮与其浮层。 */
const contextRatioPct = computed(() => {
  const ratio = contextUsage.value?.ratio ?? 0
  return Math.min(100, Math.max(0, Math.round(ratio * 100)))
})
// 输入框内圆形占用环：r=13 的周长，按占用比例做 stroke-dashoffset（与需求对话页同口径）
const ctxRingCircumference = 2 * Math.PI * 13
const ctxRingOffset = computed(() => ctxRingCircumference * (1 - contextRatioPct.value / 100))
const ctxRingTitle = computed(() => contextUsage.value
  ? `上下文窗口：已用 ${contextRatioPct.value}%（点击查看明细 / 手动压缩）`
  : '上下文窗口：打开会话后可查看')

// ── 加载 ──
async function loadWorkbench() {
  try {
    workbench.value = await clarifyApi.workbench()
  } catch { /* 拦截器已弹错 */ }
}

async function loadQuota() {
  if (!canViewQuota.value) return
  try {
    quota.value = await quotaApi.overview()
  } catch { /* 拦截器已弹错 */ }
}

async function loadPlannerOptions() {
  try {
    plannerOptions.value = await clarifyApi.plannerOptions()
  } catch { /* 拦截器已弹错 */ }
}

async function loadConversations() {
  try {
    conversations.value = await clarifyApi.list('ASSISTANT')
  } catch { /* 拦截器已弹错 */ }
  if (activeId.value != null || !conversations.value.length) return
  const latest = [...conversations.value].sort((a, b) => {
    const ta = a.createTime ? Date.parse(a.createTime) : 0
    const tb = b.createTime ? Date.parse(b.createTime) : 0
    return tb - ta
  })[0]
  if (latest) void selectConversation(latest.id)
}

async function selectConversation(id: LongId) {
  // 切走前中断在跑的流（后端未完成回复不落库；收尾读到新会话时不会覆盖详情）
  chatStreamAbort?.abort()
  activeId.value = id
  contextUsage.value = null
  try {
    detail.value = await clarifyApi.detail(id)
    // 会话级状态回填：联网搜索开关取会话原值；技能胶囊按会话重置
    webSearchEnabled.value = detail.value.conversation?.webSearchEnabled === true
    activeSkill.value = null
    clientToken.value = null
    await scrollToBottom()
    void loadContextUsage()
    void refreshAttachments()
  } catch { /* 拦截器已弹错 */ }
}

function startNew() {
  chatStreamAbort?.abort()
  activeId.value = null
  detail.value = null
  contextUsage.value = null
  input.value = ''
  // 联网搜索是会话级开关：新会话无历史值，回到默认关闭（与设计稿、与后端
  // assistantCreate 的默认值一致），避免沿用上个会话的开关造成语义漂移
  webSearchEnabled.value = false
  activeSkill.value = null
  attachments.value = []
  clientToken.value = null
}

async function loadContextUsage() {
  if (activeId.value == null) return
  try {
    contextUsage.value = await clarifyApi.contextWindow(activeId.value)
  } catch { /* 拦截器已弹错 */ }
}

async function refreshAll() {
  await Promise.all([loadWorkbench(), loadQuota()])
}

// ── 发送（SSE 流式，与需求对话同一事件协议）──
function onEnter() {
  void send()
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  const existing = activeId.value
  const webSearch = webSearchEnabled.value
  const skill = activeSkill.value
  const attachmentIds = effectiveAttachmentIds()
  sending.value = true
  input.value = ''
  resetStreamState()
  // 收尾落点：已有会话立即可知；新会话等首帧 session 再确定
  settlingConversationId = existing != null ? String(existing) : null
  const abort = new AbortController()
  chatStreamAbort = abort
  // done/error/abort 后的详情收尾是异步的：在 finally 里等它完成再撤占位，避免闪一下空白
  let settleTask: Promise<void> = Promise.resolve()
  const handlers: ChatStreamHandlers = {
    onSession: (id) => {
      // 新会话首帧：确定会话 id，后续停止/收尾都落到它；附件批次已被后端认领
      activeId.value = id
      settlingConversationId = id
      clientToken.value = null
    },
    onToken: (token) => appendStreamToken(token),
    onReasoning: (text) => appendStreamReasoning(text),
    onProgress: (text) => appendProcessStep(text),
    onDone: () => { settleTask = settleStream(true) },
    onError: (message) => {
      ElMessage.error(message)
      settleTask = settleStream(false)
    },
    onAbort: () => { settleTask = settleStream(false) }
  }
  try {
    if (existing != null) {
      await streamAssistantSend(existing, text, webSearch, skill,
        handlers, abort.signal, attachmentIds)
    } else {
      await streamAssistantCreate(text, selectedPlannerId.value, webSearch, skill,
        handlers, abort.signal, clientToken.value, attachmentIds)
    }
  } finally {
    await settleTask
    chatStreamAbort = null
    cleanupStreamTimers()
    sending.value = false
  }
}

/** 流收尾：冲残留缓冲 → 拉详情对齐落库全文（含过程 payload）→ 刷新侧栏统计。 */
async function settleStream(ok: boolean) {
  flushStreamText()
  flushReasoningText()
  const id = settlingConversationId
  settlingConversationId = null
  if (id != null) {
    try {
      const fresh = await clarifyApi.detail(id)
      // 切换会话后旧流的收尾不得覆盖新会话详情
      if (String(activeId.value ?? '') === id) {
        detail.value = fresh
        webSearchEnabled.value = fresh.conversation?.webSearchEnabled === true
      }
      void loadConversations()
      void loadContextUsage()
      void refreshAttachments()
    } catch {
      // 拦截器已弹错；成功语义下额外提示，避免用户以为回复丢失
      if (ok) ElMessage.warning('回复已生成，但详情刷新失败，请重新进入会话查看')
    }
  }
  resetStreamState()
  await scrollToBottom()
}

/** 正文增量：思考阶段结束即自动折叠过程区；60ms 节流后刷入界面。 */
function appendStreamToken(token: string) {
  if (reasoningActive.value) {
    reasoningActive.value = false
    thinkingExpanded.value = false
  }
  streamBuffer += token
  if (streamFlushTimer == null) {
    streamFlushTimer = window.setTimeout(() => {
      streamFlushTimer = null
      flushStreamText()
      void scrollToBottom()
    }, 60)
  }
}

function flushStreamText() {
  if (streamFlushTimer != null) {
    window.clearTimeout(streamFlushTimer)
    streamFlushTimer = null
  }
  if (streamBuffer) {
    streamText.value += streamBuffer
    streamBuffer = ''
  }
}

/** 思考增量：独立缓冲 + 更短节流（高频小帧，用户要看到「在动」）。 */
function appendStreamReasoning(text: string) {
  reasoningActive.value = true
  reasoningBuffer += text
  if (reasoningFlushTimer == null) {
    reasoningFlushTimer = window.setTimeout(() => {
      reasoningFlushTimer = null
      flushReasoningText()
      void scrollToBottom()
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

/** 过程步骤（progress 帧）：一行一条，按到达顺序追加。 */
function appendProcessStep(text: string) {
  if (!text) return
  processSteps.value = [...processSteps.value, text]
  void scrollToBottom()
}

/** 流式状态复位（正文/思考/步骤/缓冲/定时器）。 */
function resetStreamState() {
  streamText.value = ''
  thinkingText.value = ''
  thinkingExpanded.value = true
  reasoningActive.value = false
  processSteps.value = []
  streamBuffer = ''
  reasoningBuffer = ''
  cleanupStreamTimers()
}

function cleanupStreamTimers() {
  if (streamFlushTimer != null) {
    window.clearTimeout(streamFlushTimer)
    streamFlushTimer = null
  }
  if (reasoningFlushTimer != null) {
    window.clearTimeout(reasoningFlushTimer)
    reasoningFlushTimer = null
  }
}

/** 停止生成：中断 SSE → 后端 dispose LLM 订阅，未完成回复不落库；收尾拉详情保留已落库的 user 消息。 */
function stopGenerating() {
  if (!chatStreamAbort) return
  chatStreamAbort.abort()
  ElMessage.info('已停止生成')
}

async function scrollToBottom() {
  await nextTick()
  if (stageEl.value) stageEl.value.scrollTop = stageEl.value.scrollHeight
}

function useSuggestion(prompt: string) {
  input.value = prompt
}

// ── 消息操作 ──
async function copyMessage(m: RequirementMessage) {
  try {
    await navigator.clipboard.writeText(m.content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

async function toTask(m: RequirementMessage) {
  if (activeId.value == null) return
  try {
    const newId = await clarifyApi.assistantCreateTaskFromContext(activeId.value, m.id)
    router.push({ path: '/requirement-chat', query: { id: newId } })
  } catch {
    // 拦截器已统一弹错
  }
}

// ── 工具栏 ──
function onPlannerCommand(cmd: string) {
  if (cmd === 'auto') {
    selectedPlannerId.value = null
    return
  }
  selectedPlannerId.value = cmd
}

function plannerOptionLabel(opt: PlannerOption) {
  const model = opt.accessType === 'API_KEY_LLM' ? (opt.modelType || '平台内') : '外部 Agent'
  return opt.selectable
    ? `${opt.name}（${model}）`
    : `${opt.name}（${model}·${opt.disabledReason || '不可选'}）`
}

async function optimizeInput() {
  const text = input.value.trim()
  if (!text || enhancing.value) return
  enhancing.value = true
  try {
    const r = await promptEnhanceApi.enhance(text)
    input.value = r.optimizedPrompt || text
    ElMessage.success('已优化输入')
  } catch {
    // 拦截器已统一弹错
  } finally {
    enhancing.value = false
  }
}

async function compactContext() {
  if (activeId.value == null || compacting.value) return
  compacting.value = true
  try {
    // 与 RequirementChat 同一口径：区分「已压缩 / 无需压缩」，并回报压缩前后占用比
    const result = await clarifyApi.compact(activeId.value)
    if (result.compacted) {
      ElMessage.success(`已压缩 ${result.evictedMessages} 条（${Math.round(result.beforeRatio * 100)}% → ${Math.round(result.afterRatio * 100)}%）`)
    } else {
      ElMessage.info(`未压缩：${result.reason}`)
    }
    await loadContextUsage()
  } catch {
    // 拦截器已统一弹错
  } finally {
    compacting.value = false
  }
}

// ── 语音输入（设计稿右侧麦克风图标）──
/** 浏览器 Web Speech API 的最小结构（TS 标准库未收录 SpeechRecognition）。 */
interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  start(): void
  stop(): void
  onresult: ((e: { results?: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null
  onerror: ((e: { error?: string }) => void) | null
  onend: (() => void) | null
}
type SpeechRecognitionCtor = new () => SpeechRecognitionLike

function speechRecognitionCtor(): SpeechRecognitionCtor | null {
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor
    webkitSpeechRecognition?: SpeechRecognitionCtor
  }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

const micSupported = computed(() => speechRecognitionCtor() !== null)
const micTitle = computed(() => (micSupported.value
  ? '语音输入（暂未开放）'
  : '当前浏览器不支持语音输入'))

let recognition: SpeechRecognitionLike | null = null

function toggleMic() {
  if (recording.value) {
    recognition?.stop()
    return
  }
  const Ctor = speechRecognitionCtor()
  if (!Ctor) {
    ElMessage.warning('当前浏览器不支持语音输入')
    return
  }
  try {
    const r = new Ctor()
    r.lang = 'zh-CN'
    r.continuous = false
    r.interimResults = false
    r.onresult = (e) => {
      const parts: string[] = []
      const results = e.results ?? []
      for (let i = 0; i < results.length; i++) {
        const alt = results[i]?.[0]
        if (alt?.transcript) parts.push(alt.transcript)
      }
      const text = parts.join('').trim()
      if (text) input.value = text
    }
    r.onerror = (e) => {
      ElMessage.warning(`语音识别失败：${e?.error ?? '未知错误'}`)
    }
    r.onend = () => {
      recording.value = false
      recognition = null
    }
    r.start()
    recognition = r
    recording.value = true
  } catch {
    recording.value = false
    recognition = null
    ElMessage.warning('无法启动语音输入，请检查麦克风权限')
  }
}

// ── 顶栏 / 右栏菜单 ──
function onHistoryCommand(cmd: string) {
  void selectConversation(cmd)
}

function onTopCommand(cmd: string) {
  if (cmd === 'new') startNew()
  else if (cmd === 'chat') router.push('/requirement-chat')
}

function onPanelCommand(cmd: string) {
  if (cmd === 'refresh') void refreshAll()
  else if (cmd === 'tasks') router.push('/tasks')
  else if (cmd === 'chat') router.push('/requirement-chat')
}

// ── 产物 ──
function sizeLabel(bytes?: number | null): string {
  if (!bytes || bytes <= 0) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

async function downloadArtifact(item: ArtifactItem) {
  try {
    const resp = item.source === 'execution'
      ? await clarifyApi.executionArtifactDownloadBlob(item.id)
      : await clarifyApi.attachmentDownloadBlob(item.id)
    saveBlobResponse(resp, item.fileName || 'artifact')
  } catch {
    // 拦截器已统一弹错
  }
}

// ── 展示辅助 ──
/** 剥掉会话标题里的内部技能前缀（展示层剥离，库里不动）。 */
function displayTitle(title: string | null | undefined): string {
  const raw = (title ?? '').trim()
  const stripped = raw.replace(/^\[skill:[A-Za-z0-9][A-Za-z0-9._-]*]\s*/i, '').trim()
  return stripped || raw
}

/** 消息气泡下的时间只取时分（设计稿 "13:12"）。 */
function messageTime(createTime: string): string {
  return splitDateTime(createTime)?.time.slice(0, 5) ?? ''
}

const TASK_STATUS_COLOR: Record<string, string> = {
  PENDING: '#94A3B8',
  PLANNING: '#F59E0B',
  IN_PROGRESS: '#0EA5E9',
  DONE: '#10B981',
  CANCELLED: '#CBD5E1'
}
function taskStatusColor(status: string | null): string {
  return TASK_STATUS_COLOR[status ?? ''] ?? '#94A3B8'
}
function taskStatusLabel(status: string | null): string {
  const map: Record<string, string> = {
    PENDING: '待规划',
    PLANNING: '拆解中',
    IN_PROGRESS: '进行中',
    DONE: '已完成',
    CANCELLED: '已取消'
  }
  return map[status ?? ''] ?? '进行中'
}
function taskTagStyle(status: string | null) {
  const color = taskStatusColor(status)
  return { color, background: color + '1A' }
}
function agentStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'REVIEWING': return '审核中'
    default: return '待命中'
  }
}
/** 状态配色：运行中=主色紫、审核中=橙、待命中=灰（与任务概况卡片的语义色一致）。 */
function agentStatusClass(a: WorkbenchActiveAgent): string {
  switch (a.status) {
    case 'RUNNING': return 'is-running'
    case 'REVIEWING': return 'is-reviewing'
    default: return 'is-standby'
  }
}
function agentRoleClass(a: WorkbenchActiveAgent): string {
  return a.role === 'REVIEWER' ? 'is-reviewer' : 'is-executor'
}
function agentIcon(a: WorkbenchActiveAgent) {
  return a.role === 'REVIEWER' ? Stamp : Tools
}
/** 行悬浮说明：角色 + 接入类型 + 当前状态与负载。 */
function agentTitle(a: WorkbenchActiveAgent): string {
  const role = a.role === 'REVIEWER' ? '复核 Agent' : '执行 Agent'
  const access = a.internal ? '平台内 LLM' : '外部 Agent'
  const load = a.runningCount > 0 ? `，${a.runningCount} 个子任务在跑` : ''
  return `${role} · ${access} · ${agentStatusLabel(a.status)}${load}`
}

// ── SSE：任务状态变化即刷新右侧聚合 ──
let streamAbort: AbortController | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

function startStream() {
  streamAbort?.abort()
  const ctrl = new AbortController()
  streamAbort = ctrl
  void streamWorkbenchEvents({
    onTaskChange: () => { void loadWorkbench() },
    onClosed: () => {
      if (ctrl.signal.aborted) return
      reconnectTimer = setTimeout(startStream, 5000)
    }
  }, ctrl.signal)
}

onMounted(() => {
  void loadWorkbench()
  void loadQuota()
  void loadPlannerOptions()
  void loadConversations()
  startStream()
})

onBeforeUnmount(() => {
  streamAbort?.abort()
  chatStreamAbort?.abort()
  if (reconnectTimer) clearTimeout(reconnectTimer)
  if (parsePollTimer != null) window.clearInterval(parsePollTimer)
  recognition?.stop()
})
</script>

<style scoped>
/* ── 工作台配色令牌（Calicat 设计稿「HelloAI 个人工作台」）
   浅色取设计稿原值，暗色给等价暗色版本；仅本页作用域生效。 ── */
.wb-page {
  --wb-main-bg: #FFFFFF;
  --wb-panel-bg: #FAFAFD;
  --wb-card-border: #EEEDF4;
  --wb-divider: #ECEBF3;
  --wb-tint: #F5F2FF;
  --wb-tint-border: #EDE7FF;
  --wb-chip: #F0EFF6;
  --wb-chip-soft: #F7F6FB;
  --wb-ink: #2B2836;
  --wb-ink-strong: #1F1D2B;
  --wb-ink-sub: #8A8798;
  --wb-ink-faint: #B0ADBE;
  --wb-primary: #7C5CFC;
  --wb-primary-text: #6D4AFF;
  --wb-shadow-card: 0 2px 8px rgba(31, 29, 43, 0.03);
  --wb-input-border: #E9E8F2;
  --wb-shadow-input: 0 8px 24px rgba(31, 29, 43, 0.06);
  --wb-dot-purple: #7C5CFC;
  --wb-dot-amber: #F59E0B;
  --wb-dot-sky: #0EA5E9;
  --wb-dot-red: #EF4444;
  --wb-dot-gray: #94A3B8;

  display: flex;
  align-items: stretch;
  height: calc(100dvh - clamp(16px, 2vw, 32px) * 2);
  min-height: 0;
  overflow: hidden;
  background: var(--wb-main-bg);
  border: 1px solid var(--wb-card-border);
  border-radius: 16px;
}

html.dark .wb-page {
  --wb-main-bg: #121828;
  --wb-panel-bg: #0F1420;
  --wb-card-border: #242D47;
  --wb-divider: #1C2438;
  --wb-tint: rgba(124, 92, 252, 0.16);
  --wb-tint-border: rgba(124, 92, 252, 0.30);
  --wb-chip: rgba(255, 255, 255, 0.08);
  --wb-chip-soft: rgba(255, 255, 255, 0.06);
  --wb-ink: #EEF2F8;
  --wb-ink-strong: #F5F7FB;
  --wb-ink-sub: #A9B4C7;
  --wb-ink-faint: #7C8AA0;
  --wb-primary: #8B6BFF;
  --wb-primary-text: #A78BFA;
  --wb-shadow-card: 0 2px 8px rgba(0, 0, 0, 0.30);
  --wb-input-border: rgba(124, 92, 252, 0.35);
  --wb-shadow-input: 0 8px 24px rgba(0, 0, 0, 0.36);
}

/* ══════════ 中间主区 ══════════ */
.wb-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--wb-main-bg);
}

/* ── 顶部标题栏（68px） ── */
.wb-topbar {
  height: 68px;
  flex-shrink: 0;
  padding: 0 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.wb-topbar-id {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.wb-topbar-avatar {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: 12px;
  background: var(--wb-tint);
  border: 1px solid var(--wb-tint-border);
  color: var(--wb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
}
.wb-topbar-text {
  min-width: 0;
}
.wb-topbar-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-topbar-name-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wb-topbar-conv-chip {
  max-width: 220px;
  padding: 1px 8px;
  border-radius: 5px;
  background: var(--wb-tint);
  color: var(--wb-primary-text);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wb-topbar-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--wb-ink-sub);
}
.wb-topbar-ops {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.wb-model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  color: var(--wb-ink);
  font-size: 12px;
}
.wb-model-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10B981;
}
.wb-icon-btn {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: var(--wb-chip-soft);
  color: var(--wb-ink-sub);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  transition: background 0.15s, color 0.15s;
}
.wb-icon-btn:hover {
  background: var(--wb-chip);
  color: var(--wb-ink-strong);
}

/* ── 舞台 ── */
.wb-stage {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}

/* ── 空闲态 ── */
.wb-idle {
  min-height: 100%;
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.wb-idle-icon {
  width: 60px;
  height: 60px;
  border-radius: 18px;
  background: var(--wb-tint);
  border: 1px solid var(--wb-tint-border);
  box-shadow: 0 6px 18px rgba(124, 92, 252, 0.10);
  color: var(--wb-primary);
  font-size: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.wb-idle-title {
  margin: 20px 0 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-idle-desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--wb-ink-sub);
  text-align: center;
  max-width: 560px;
}
.wb-suggestions {
  margin-top: 32px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
  max-width: 760px;
}
.wb-suggestion {
  text-align: left;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  box-shadow: var(--wb-shadow-card);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition: border-color 0.15s, transform 0.15s;
}
.wb-suggestion:hover {
  border-color: var(--wb-primary);
  transform: translateY(-1px);
}
.wb-suggestion-icon {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--wb-tint);
  color: var(--wb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}
.wb-suggestion-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-suggestion-desc {
  font-size: 11px;
  color: var(--wb-ink-sub);
}

/* ── 对话态（设计稿：消息列表 744 宽 + 区域左右 36 内边距 + 条目间距 20） ── */
.wb-thread {
  width: 100%;
  max-width: 816px;
  margin: 0 auto;
  padding: 28px 36px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}
.wb-msg {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-width: 0;
}
.wb-msg.is-user {
  justify-content: flex-end;
}
.wb-msg-avatar {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 8px;
  background: var(--wb-tint);
  color: var(--wb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.wb-msg-col {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* 助手消息占满头像右侧（设计稿 704 = 744 - 28 头像 - 12 间距） */
.wb-msg.is-assistant .wb-msg-col {
  flex: 1 1 auto;
  gap: 12px;
}
.wb-msg.is-user .wb-msg-col {
  flex: 0 1 auto;
  align-items: flex-end;
}
.wb-bubble {
  max-width: 100%;
  color: var(--wb-ink);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.wb-bubble.is-user {
  background: var(--wb-tint);
  border-radius: 14px 4px 14px 14px;
  padding: 12px 16px;
  white-space: pre-wrap;
  max-width: 520px;
}
/* 用户消息元信息（设计稿：气泡下方右侧，时间 + 20px 圆形头像） */
.wb-msg-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}
.wb-msg-time {
  font-size: 10px;
  color: var(--wb-ink-faint);
}
.wb-msg-me {
  width: 20px;
  height: 20px;
  border-radius: 9999px;
  background: var(--wb-tint);
  color: var(--wb-primary);
  display: grid;
  place-items: center;
  font-size: 10px;
}
/* 助手空回复（模型侧失败返回空内容）：给出可读提示，避免渲染成空白气泡 */
.wb-msg-empty {
  padding: 10px 14px;
  border: 1px dashed var(--wb-card-border);
  border-radius: 10px;
  color: var(--wb-ink-sub);
  font-size: 13px;
}
.wb-msg-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.wb-act {
  height: 28px;
  border: none;
  background: transparent;
  color: var(--wb-ink-sub);
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  border-radius: 8px;
}
.wb-act:hover {
  color: var(--wb-ink-strong);
  background: var(--wb-chip-soft);
}
.wb-typing {
  color: var(--wb-ink-sub);
  font-size: 13px;
}

/* ── 过程区（思考 + 检索步骤）：流式进行中与历史消息共用折叠样式 ── */
.wb-process {
  border: 1px solid var(--wb-card-border);
  border-radius: 10px;
  background: var(--wb-panel-bg);
  overflow: hidden;
}
.wb-process-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
  color: var(--wb-ink-sub);
  transition: background 0.15s;
}
.wb-process-head:hover { background: var(--wb-chip-soft); }
.wb-process-toggle { margin-left: auto; color: var(--wb-ink-faint); }
.wb-process-body {
  border-top: 1px solid var(--wb-divider);
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wb-process-step { font-size: 12px; color: var(--wb-ink-sub); line-height: 1.6; }
.wb-process-thinking {
  font-size: 12px;
  color: var(--wb-ink-sub);
  line-height: 1.7;
  border-left: 2px solid var(--wb-tint-border);
  padding-left: 10px;
}

/* ── 输入区（设计稿：输入区宽 872、左右 36 内边距、底部 12） ── */
.wb-composer-wrap {
  flex-shrink: 0;
  width: 100%;
  max-width: 872px;
  margin: 0 auto;
  padding: 0 36px 12px;
}
.wb-composer {
  border: 1px solid var(--wb-input-border);
  border-radius: 16px;
  background: var(--wb-main-bg);
  box-shadow: var(--wb-shadow-input);
  padding: 14px 16px 12px;
}
.wb-input :deep(.el-textarea__inner) {
  box-shadow: none;
  border: none;
  padding: 0 4px 8px;
  background: transparent;
  color: var(--wb-ink);
  font-size: 14px;
  line-height: 1.6;
  font-family: inherit;
}
.wb-input :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}
.wb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
}
.wb-toolbar-left,
.wb-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
/* 右侧工具栏按设计稿收紧为 6px（左侧维持 8px） */
.wb-toolbar-right {
  gap: 6px;
}
.wb-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 9999px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-chip-soft);
  color: var(--wb-ink-sub);
  font-size: 12px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.wb-tool-btn:hover:not(:disabled) {
  color: var(--wb-ink-strong);
  border-color: var(--wb-primary);
}
.wb-tool-btn.is-icon {
  width: 28px;
  padding: 0;
  justify-content: center;
}
.wb-tool-btn.is-static {
  cursor: default;
  color: var(--wb-ink-faint);
}
.wb-tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 置顶态：紫色描边 + 紫色图标 */
.wb-tool-btn.is-on {
  color: var(--wb-primary);
  border-color: var(--wb-primary);
}
/* 右侧图标按钮（Calicat 设计稿：优化输出，无边框 28×28 圆角 8） */
.wb-tool-btn.is-optimize {
  width: 28px;
  height: 28px;
  padding: 0;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 15px;
  color: var(--wb-ink-sub);
}
.wb-tool-btn.is-optimize:hover:not(:disabled) {
  background: var(--wb-chip-soft);
  border: none;
  color: var(--wb-ink-strong);
}
/* 上下文占用环（与需求对话页 RequirementChat 同款；点击展开明细 / 压缩） */
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
  color: var(--wb-ink-sub);
  cursor: pointer;
}
.ctx-ring:hover { background: var(--wb-chip-soft); }
.ctx-ring-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ctx-ring-track {
  fill: none;
  stroke: var(--wb-input-border);
  stroke-width: 2.5;
}
.ctx-ring-fill {
  fill: none;
  stroke: var(--wb-primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.18s ease, stroke 0.18s ease;
}
.ctx-ring.is-warn .ctx-ring-fill { stroke: var(--wb-dot-amber); }
.ctx-ring.is-warn .ctx-ring-label { color: var(--wb-dot-amber); }
.ctx-ring-label {
  position: relative;
  font-size: 9px;
  font-weight: 600;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  color: var(--wb-ink-sub);
}
.ctx-ring-icon {
  position: relative;
  font-size: 13px;
  color: var(--wb-ink-sub);
}
.wb-caret {
  font-size: 11px;
}
.wb-send-btn {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 10px;
  background: var(--wb-primary);
  color: #fff;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  box-shadow: 0 4px 12px rgba(124, 92, 252, 0.28);
  transition: opacity 0.15s;
}
.wb-send-btn:disabled {
  opacity: 0.45;
  box-shadow: none;
  cursor: not-allowed;
}
.wb-send-btn.is-stop {
  background: var(--el-color-danger, #F56C6C);
  box-shadow: 0 4px 12px rgba(245, 108, 108, 0.28);
}

/* ── 「＋」按钮（圆形）── */
.wb-plus-btn {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 50%;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  color: var(--wb-ink-sub);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  transition: color 0.15s, border-color 0.15s;
}
.wb-plus-btn:hover {
  color: var(--wb-ink-strong);
  border-color: var(--wb-primary);
}
.wb-file-input {
  display: none;
}

/* ── 联网搜索开关 ── */
.wb-web-search {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px 0 10px;
  border-radius: 9999px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-chip-soft);
  color: var(--wb-ink-sub);
  font-size: 12px;
}
.wb-web-search-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: default;
}
.wb-web-search-icon {
  color: var(--wb-primary);
  font-size: 13px;
}

/* ── 语音输入（设计稿右侧 28×28；暂未开放，维持置灰）── */
.wb-mic-btn {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 8px;
  border: none;
  background: var(--wb-chip-soft);
  color: var(--wb-ink-sub);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  transition: color 0.15s, background 0.15s;
}
.wb-mic-btn:hover:not(:disabled) {
  color: var(--wb-ink-strong);
}
.wb-mic-btn.is-recording {
  background: #EF4444;
  color: #fff;
}
.wb-mic-btn:disabled {
  opacity: 1;
  color: var(--wb-ink-faint);
  cursor: not-allowed;
}

/* ── 技能 / 附件 胶囊条 ── */
.wb-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 8px;
}
.wb-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  height: 26px;
  padding: 0 4px 0 8px;
  border-radius: 8px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-chip-soft);
  color: var(--wb-ink-sub);
  font-size: 12px;
}
.wb-chip.is-skill {
  border-color: var(--wb-tint-border);
  background: var(--wb-tint);
  color: var(--wb-primary-text);
}
.wb-chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wb-chip-state {
  color: var(--wb-ink-faint);
  font-size: 11px;
  flex-shrink: 0;
}
.wb-chip-x {
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  font-size: 11px;
  flex-shrink: 0;
}
.wb-chip-x:hover {
  background: var(--wb-chip);
  color: var(--wb-ink-strong);
}

/* ── 上下文浮层（popover 挂在 body 上，须走 :global 并补一份本页令牌）── */
:global(.wb-ctx-popover) {
  --wb-ink: #2B2836;
  --wb-ink-sub: #8A8798;
  --wb-chip-soft: #F7F6FB;
  --wb-primary: #7C5CFC;
  --wb-card-border: #EEEDF4;
}
:global(html.dark .wb-ctx-popover) {
  --wb-ink: #EEF2F8;
  --wb-ink-sub: #A9B4C7;
  --wb-chip-soft: rgba(255, 255, 255, 0.06);
  --wb-primary: #8B6BFF;
  --wb-card-border: #242D47;
}
:global(.wb-ctx-popover .ctx-panel) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: var(--wb-ink);
}
:global(.wb-ctx-popover .ctx-head) {
  display: flex;
  justify-content: space-between;
  font-weight: 600;
}
:global(.wb-ctx-popover .ctx-ratio) { color: var(--wb-ink-sub); }
:global(.wb-ctx-popover .ctx-bar) {
  height: 6px;
  border-radius: 3px;
  background: var(--wb-chip-soft);
  overflow: hidden;
}
:global(.wb-ctx-popover .ctx-bar-fill) {
  height: 100%;
  background: var(--wb-primary);
  transition: width 0.15s ease;
}
:global(.wb-ctx-popover .ctx-row) {
  display: flex;
  justify-content: space-between;
  color: var(--wb-ink-sub);
}
:global(.wb-ctx-popover .ctx-total) {
  color: var(--wb-ink);
  border-top: 1px solid var(--wb-card-border);
  padding-top: 6px;
}
:global(.wb-ctx-popover .ctx-meta) {
  color: var(--wb-ink-sub);
  font-size: 11px;
}
:global(.wb-ctx-popover .ctx-empty) { color: var(--wb-ink-sub); }
:global(.wb-ctx-popover .ctx-compact-btn) { width: 100%; }

/* ── 「＋」面板（popover 挂在 body 上，样式须走 :global）──
   popover 不在 .wb-page 内，拿不到本页的 --wb-* 变量，故在面板自身补一份同值令牌。 */
:global(.wb-plus-popover) {
  padding: 6px !important;
  --wb-tint: #F5F2FF;
  --wb-ink: #2B2836;
  --wb-ink-sub: #8A8798;
  --wb-chip-soft: #F7F6FB;
  --wb-primary: #7C5CFC;
}
:global(html.dark .wb-plus-popover) {
  --wb-tint: rgba(124, 92, 252, 0.16);
  --wb-ink: #EEF2F8;
  --wb-ink-sub: #A9B4C7;
  --wb-chip-soft: rgba(255, 255, 255, 0.06);
  --wb-primary: #8B6BFF;
}
:global(.wb-plus-popover .wb-plus-menu) {
  max-height: min(60vh, 520px);
  overflow-y: auto;
}
:global(.wb-plus-popover .wb-plus-group-title) {
  padding: 4px 8px 6px;
  font-size: 12px;
  color: var(--wb-ink-sub);
  letter-spacing: 0.5px;
}
:global(.wb-plus-popover .wb-plus-item) {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
:global(.wb-plus-popover .wb-plus-item:hover) {
  background: var(--wb-chip-soft);
}
:global(.wb-plus-popover .wb-plus-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--wb-tint);
  color: var(--wb-primary);
  font-size: 14px;
}
:global(.wb-plus-popover .wb-plus-text) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
:global(.wb-plus-popover .wb-plus-name) {
  font-size: 13px;
  color: var(--wb-ink);
  line-height: 1.4;
  word-break: break-word;
}
:global(.wb-plus-popover .wb-plus-desc) {
  font-size: 12px;
  color: var(--wb-ink-sub);
  line-height: 1.4;
}
:global(.wb-plus-popover .wb-plus-item.is-active) {
  background: var(--wb-tint);
}
:global(.wb-plus-popover .wb-plus-item.is-active .wb-plus-name) {
  color: var(--wb-primary);
  font-weight: 600;
}
:global(.wb-plus-popover .wb-plus-check) {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--wb-primary);
  font-size: 13px;
}

/* ── 状态卡里的上下文行 ── */
.wb-metric-unit {
  color: var(--wb-ink-faint);
  font-weight: 400;
  font-size: 11px;
}

/* ══════════ 右侧信息栏（320px） ══════════ */
.wb-panel {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--wb-panel-bg);
  border-left: 1px solid var(--wb-divider);
  min-height: 0;
}
.wb-panel-head {
  height: 68px;
  flex-shrink: 0;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wb-panel-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-panel-icon {
  color: var(--wb-primary);
  font-size: 15px;
}
.wb-scope-chip {
  height: 18px;
  padding: 0 6px;
  border-radius: 5px;
  background: var(--wb-tint);
  color: var(--wb-primary-text);
  font-size: 11px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
}
.wb-panel-ops {
  display: flex;
  align-items: center;
  gap: 4px;
}
.wb-panel-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 0 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 助理状态卡 ── */
.wb-status-card {
  border: 1px solid var(--wb-tint-border);
  background: var(--wb-tint);
  border-radius: 16px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wb-status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.wb-status-id {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.wb-status-avatar {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 10px;
  background: var(--wb-main-bg);
  color: var(--wb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}
.wb-status-text {
  min-width: 0;
}
.wb-status-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-status-model {
  font-size: 11px;
  color: var(--wb-ink-sub);
}
.wb-ready-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 20px;
  padding: 0 8px;
  border-radius: 9999px;
  background: var(--wb-main-bg);
  color: #10B981;
  font-size: 11px;
  flex-shrink: 0;
}
.wb-ready-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
.wb-status-metric {
  background: var(--wb-main-bg);
  border-radius: 12px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wb-metric-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}
.wb-metric-label {
  color: var(--wb-ink-sub);
}
.wb-metric-value {
  color: var(--wb-ink-strong);
  font-weight: 600;
}

/* ── 分段（设计稿：每个模块固定高度，尺寸不随数据量抖动） ── */
.wb-section {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow: hidden;
}
.wb-section--overview { height: 212px; gap: 12px; }
.wb-section--agents { height: 216px; }
.wb-section--tasks { height: 282px; }
.wb-section--artifacts { height: 142px; }
.wb-section-head {
  flex: 0 0 auto;
  height: 18px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.wb-section-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--wb-ink-strong);
}
.wb-section-count {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 9999px;
  background: var(--wb-chip);
  color: var(--wb-ink-sub);
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.wb-section-link {
  margin-left: auto;
  font-size: 11px;
  color: var(--wb-primary-text);
  cursor: pointer;
}
.wb-section-link:hover {
  text-decoration: underline;
}
.wb-empty {
  font-size: 12px;
  color: var(--wb-ink-faint);
  padding: 4px 0;
}

/* ── 任务概况 2×2 ── */
.wb-stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.wb-stat {
  height: 85px;
  padding: 12px;
  border-radius: 12px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wb-stat-label {
  font-size: 11px;
  color: var(--wb-ink-sub);
}
.wb-stat-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--wb-ink-strong);
  line-height: 1.1;
}
.wb-stat-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: var(--wb-ink-faint);
}
.wb-stat-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
}

/* ── 活跃 Agent（设计稿：56 高浅紫卡，3 条） ── */
.wb-agent-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}
.wb-agent {
  flex: 0 0 56px;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-radius: 16px;
  border: 1px solid var(--wb-tint-border);
  background: var(--wb-tint);
}
.wb-agent-avatar {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 8px;
  background: var(--wb-main-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}
.wb-agent-avatar.is-executor {
  color: #6366f1;
}
.wb-agent-avatar.is-reviewer {
  color: #10b981;
}
.wb-agent-name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12px;
  color: var(--wb-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 状态徽标：白底胶囊 + 彩色圆点 + 文案（设计稿右侧形态） */
.wb-agent-status {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 6px;
  border-radius: 9999px;
  background: var(--wb-main-bg);
  font-size: 10px;
  color: var(--wb-ink-sub);
}
.wb-agent-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #94a3b8;
}
.wb-agent-status.is-running {
  color: #7c5cfc;
}
.wb-agent-status.is-running .wb-agent-dot {
  background: #7c5cfc;
}
.wb-agent-status.is-reviewing {
  color: #f59e0b;
}
.wb-agent-status.is-reviewing .wb-agent-dot {
  background: #f59e0b;
}

/* ── 最近任务（设计稿：78 高白卡，3 条） ── */
.wb-task-item {
  flex: 0 0 78px;
  height: 78px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  overflow: hidden;
}
.wb-task-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}
.wb-task-body {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wb-task-title {
  font-size: 12px;
  color: var(--wb-ink-strong);
  line-height: 17px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.wb-task-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}
.wb-task-tag {
  height: 18px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
}
.wb-task-time {
  font-size: 10px;
  color: var(--wb-ink-faint);
}

/* ── 最近产物（设计稿：52 高白卡，2 条） ── */
.wb-artifact {
  flex: 0 0 52px;
  height: 52px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--wb-card-border);
  background: var(--wb-main-bg);
  overflow: hidden;
}
.wb-artifact-icon {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 8px;
  background: var(--wb-tint);
  color: var(--wb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}
.wb-artifact-body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}
.wb-artifact-name {
  font-size: 12px;
  line-height: 17px;
  color: var(--wb-ink-strong);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wb-artifact-sub {
  font-size: 10px;
  line-height: 14px;
  color: var(--wb-ink-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── 高度不足时的右栏降级：先隐藏助理状态卡，再依次收起最近产物 / 活跃 Agent，
      保证 4 个模块固定尺寸时整体不出滚动条 ── */
@media (min-width: 1181px) and (max-height: 1239px) {
  .wb-status-card {
    display: none;
  }
}
@media (min-width: 1181px) and (max-height: 1069px) {
  .wb-panel-body {
    gap: 16px;
    padding-bottom: 16px;
  }
  /* 产物只留 1 条 */
  .wb-section--artifacts {
    height: 80px;
  }
  .wb-artifact:nth-child(n+3) {
    display: none;
  }
}
@media (min-width: 1181px) and (max-height: 949px) {
  /* Agent / 任务各留 2 条 */
  .wb-section--agents {
    height: 150px;
  }
  .wb-agent:nth-child(n+3) {
    display: none;
  }
  .wb-section--tasks {
    height: 194px;
  }
  .wb-task-item:nth-child(n+4) {
    display: none;
  }
}
@media (min-width: 1181px) and (max-height: 839px) {
  /* 再压低模块间距与任务条数，保证「最近产物」至少能用 1 条的形式留在首屏 */
  .wb-panel-body {
    gap: 12px;
    padding-bottom: 12px;
  }
  .wb-section--tasks {
    height: 106px;
  }
  .wb-task-item:nth-child(n+3) {
    display: none;
  }
}

/* ── 窄屏：右栏落到下方，避免挤压对话区 ── */
@media (max-width: 1180px) {
  .wb-page {
    flex-direction: column;
    height: auto;
    min-height: calc(100dvh - clamp(16px, 2vw, 32px) * 2);
  }
  .wb-main {
    min-height: 60vh;
  }
  .wb-panel {
    width: 100%;
    border-left: none;
    border-top: 1px solid var(--wb-divider);
  }
  /* 窄屏右栏落到下方：模块按可用宽度自动分列，页面整体滚动 */
  .wb-panel-body {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
    align-items: start;
    gap: 20px;
    overflow: visible;
  }
  .wb-status-card {
    grid-column: 1 / -1;
  }
  .wb-suggestions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
