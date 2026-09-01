<template>
  <div
    v-loading="loading"
    class="page ha-entrance-up"
  >
    <!-- 版本三精简版：头部 = 状态徽标 + 标题 + 元信息 + 依赖标签（替代 el-descriptions 表格） -->
    <el-card
      v-if="item"
      class="head-card full-row"
    >
      <div class="head-top">
        <div class="head-badges">
          <span class="head-crumb">子任务详情</span>
          <el-tag
            :type="getSubTaskStatusMeta(item.status)?.type || 'info'"
            size="small"
          >
            {{ getSubTaskStatusMeta(item.status)?.label || item.status }}
          </el-tag>
          <el-tag
            v-if="item.scoreGrade"
            :type="SCORE_GRADE_MAP[item.scoreGrade]?.type || 'info'"
            size="small"
          >
            评分 {{ SCORE_GRADE_MAP[item.scoreGrade]?.label || item.scoreGrade }}
          </el-tag>
        </div>
        <el-button
          size="small"
          @click="goBackToList"
        >
          返回列表
        </el-button>
      </div>
      <h1 class="head-title">
        {{ item.title }}
      </h1>
      <div class="head-meta">
        <span>负责人：{{ item.assignedAgentName || resolveAgentName(item.assignedAgent) || '-' }}</span>
        <span>创建时间：{{ fmtTime(item.createTime) }}</span>
      </div>
      <!-- V27 依赖编排可视化：前置依赖（全部 DONE 才会分发本任务）与被依赖（本任务完成后解锁的下游） -->
      <div
        v-if="upstreamItems.length || downstreamItems.length"
        class="dep-row"
      >
        <template v-if="upstreamItems.length">
          <span class="dep-label">前置依赖</span>
          <el-tag
            v-for="dep in upstreamItems"
            :key="dep.id"
            size="small"
            class="dep-tag"
            :type="dep.tagType"
            @click="goSibling(dep.id)"
          >
            {{ dep.text }}
          </el-tag>
        </template>
        <template v-if="downstreamItems.length">
          <span class="dep-label">被依赖</span>
          <el-tag
            v-for="dep in downstreamItems"
            :key="dep.id"
            size="small"
            class="dep-tag"
            :type="dep.tagType"
            @click="goSibling(dep.id)"
          >
            {{ dep.text }}
          </el-tag>
        </template>
      </div>
      <!-- 任务摘要并入头部卡（用户要求：与子任务详情同框），虚线分隔保持层次感 -->
      <div
        v-if="item.content"
        class="head-summary"
      >
        <div class="head-summary-label">
          任务摘要
        </div>
        <div class="summary-text">
          {{ item.content }}
        </div>
      </div>
    </el-card>

    <!-- 版本三第四轮修正：概览统计卡已移除（数字与下方各卡头计数重复）；任务摘要已并入头部卡 -->

    <!-- §6.52 人工介入：返工达上限 / 降级能力不匹配时，用户自主选择 agent 驳回改派或直接通过 -->
    <el-card
      v-if="item && needsManualIntervention"
      class="manual-card full-row"
    >
      <template #header>
        <div class="card-header">
          <span style="color:var(--el-color-warning)">人工介入</span>
          <span style="font-size:12px;color:var(--ha-muted)">自动链路已停止，需人工处置（前端面板 + 后端 review API）</span>
        </div>
      </template>
      <div class="manual-body">
        <div class="manual-reason">
          <el-tag
            type="warning"
            size="small"
          >
            {{ manualReasonText }}
          </el-tag>
          <span class="manual-current">当前负责人：{{ item.assignedAgentName || resolveAgentName(item.assignedAgent) }}</span>
        </div>
        <div class="manual-actions">
          <div class="manual-row">
            <span class="manual-label">改派给</span>
            <el-select
              v-model="manualTargetAgentId"
              placeholder="选择执行 Agent（外部/内部均可）"
              filterable
              style="width:320px"
            >
              <el-option
                v-for="a in manualCandidates"
                :key="String(a.id)"
                :label="a.name + '（' + accessTypeLabel(a.accessType) + (a.onlineStatus ? ' · ' + a.onlineStatus : '') + '）'"
                :value="String(a.id)"
              />
              <!-- §6.57 原执行者重做：人工驳回=开启新一轮（reworkFresh 重置计数），
                   不换 Agent 亦合法；选择器里用特殊值标记，提交时映射为 reworkAgentId=null -->
              <el-option
                v-if="currentExecutorName"
                :label="'原执行者重做（' + currentExecutorName + '）'"
                :value="KEEP_CURRENT"
              />
            </el-select>
            <el-button
              type="warning"
              :loading="manualSubmitting"
              :disabled="!manualTargetAgentId"
              @click="submitManualRework"
            >
              驳回并改派
            </el-button>
          </div>
          <div class="manual-row">
            <span class="manual-label">或</span>
            <el-button
              type="success"
              :loading="manualSubmitting"
              @click="submitManualApprove"
            >
              直接通过（人工验收）
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- V28: 执行对话流（按轮次展示 Agent ↔ LLM 的完整请求/返回）。版本三第二轮：卡内页签含执行时序图，左主栏 -->
    <el-card
      v-if="item"
      id="sec-conv"
      class="main-col conv-card"
    >
      <template #header>
        <div class="card-header">
          <span style="font-size:12px;color:var(--ha-muted)">共 {{ conversation.length }} 条 · {{ convRounds.length }} 轮</span>
        </div>
      </template>
      <!-- 时序图页签从时间线卡迁入：右栏 360px 太窄，对话流卡（左主栏）给足画布 -->
      <el-tabs
        v-model="convView"
        class="conv-tabs"
      >
        <el-tab-pane
          label="执行对话流"
          name="conv"
        >
          <el-empty
            v-if="!conversation.length"
            description="暂无对话消息"
          />
          <div
            v-else
            class="conv-rounds"
          >
            <div
              v-for="(round, rIdx) in convRounds"
              :key="rIdx"
              class="conv-round"
            >
              <!-- 设计图轮次头：#N · type 徽标 + Agent 标签 + 状态标签 + 时间右对齐 -->
              <div class="round-header">
                <span class="round-badges">
                  <el-tag
                    size="small"
                    :type="round.type === 'execute' ? 'primary' : 'warning'"
                  >
                    #{{ round.roundNo }} · {{ round.type }}
                  </el-tag>
                  <el-tag
                    size="small"
                    type="info"
                  >
                    {{ roundAgentName(round) }}
                  </el-tag>
                  <el-tag
                    size="small"
                    :type="roundMeta(round).failed ? 'danger' : 'success'"
                  >
                    {{ roundMeta(round).statusLabel }}
                  </el-tag>
                </span>
                <span class="round-time">{{ roundMeta(round).time }}</span>
              </div>
              <div class="conv-list">
                <div
                  v-for="msg in round.messages"
                  :key="msg.id"
                  class="conv-item"
                  :class="convItemClass(msg)"
                >
                  <!-- 设计图：左侧角色头像（用户主色 / AI 绿 / 核验橙） -->
                  <span
                    class="conv-avatar"
                    :title="convTagLabel(msg.toolName)"
                  >{{ convAvatarText(msg) }}</span>
                  <div class="conv-body">
                    <div
                      class="conv-item-head"
                      :class="{ clickable: isCollapsible(msg) }"
                      @click="isCollapsible(msg) && toggleMsg(msg)"
                    >
                      <el-tag
                        size="small"
                        :type="convTagType(msg.toolName, msg.content)"
                      >
                        {{ convTagLabel(msg.toolName) }}
                      </el-tag>
                      <span class="conv-meta">
                        #{{ msg.seq }} · {{ msg.role }}/{{ msg.senderType }}<template v-if="msg.senderId"> · {{ resolveAgentName(msg.senderId) }}</template>
                        · {{ fmtTime(msg.createTime) }}
                      </span>
                      <!-- 仅“执行产出”保留复制/导出按钮 -->
                      <div
                        v-if="msg.toolName === 'sub_task_execute' && msg.content"
                        class="conv-actions"
                      >
                        <el-button
                          link
                          size="small"
                          @click.stop="copyMessage(msg)"
                        >
                          复制
                        </el-button>
                        <el-button
                          link
                          size="small"
                          type="primary"
                          @click.stop="exportMarkdown(msg)"
                        >
                          导出 .md
                        </el-button>
                      </div>
                      <el-icon
                        v-if="isCollapsible(msg)"
                        class="conv-caret"
                        :class="{ open: isMsgExpanded(msg) }"
                      >
                        <ArrowRight />
                      </el-icon>
                    </div>
                    <!-- 核验结论用结构化视图；可折叠消息默认全部收起（单行摘要预览），点头部展开 -->
                    <template v-if="!isCollapsible(msg) || isMsgExpanded(msg)">
                      <ReviewVerdictView
                        v-if="['subtask_review_verdict', 'subtask_dual_review_verdict', 'subtask_recheck_verdict'].includes(msg.toolName || '')"
                        :content="msg.content"
                      />
                      <MarkdownView
                        v-else
                        :content="msg.content"
                      />
                    </template>
                    <!-- 收起态预览 + 设计图展开全文按钮 -->
                    <template v-else>
                      <div class="conv-preview">
                        {{ msgPreview(msg) }}
                      </div>
                      <span
                        class="conv-toggle"
                        @click.stop="toggleMsg(msg)"
                      >
                        <span>展开全文（{{ msgContentLength(msg) }}字）</span>
                        <el-icon
                          class="toggle-caret"
                          :class="{ open: isMsgExpanded(msg) }"
                        >
                          <ArrowRight />
                        </el-icon>
                      </span>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane
          label="执行时序图"
          name="seq"
        >
          <SubTaskSequenceFlow
            :events="timeline"
            :resolve-agent-name="resolveAgentName"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- M4.5: 执行时间线（M5 联调可视化）。版本三第二轮：右栏首卡（与对话流卡顶对齐），纯列表无技术详情 -->
    <el-card
      v-if="item"
      id="sec-tl"
      class="side-card tl-card"
    >
      <template #header>
        <div class="card-header">
          <span>执行时间线</span>
          <span class="tl-head-right">
            <span style="font-size:12px;color:var(--ha-muted)">{{ timelinePolling ? '轮询中（5s）' : '已停止' }} · 共 {{ timeline.length }} 条</span>
            <el-button
              v-if="hiddenEventCount > 0"
              size="small"
              round
              plain
              :type="timelineFull ? 'info' : 'primary'"
              @click="timelineFull = !timelineFull"
            >
              {{ timelineFull ? '只看关键节点' : '展开全部 ' + timeline.length + ' 条' }}
            </el-button>
          </span>
        </div>
      </template>
      <!-- 只保留时间线列表（时序图已迁至执行对话流卡）；技术详情不再露出，例行事件由卡头「展开全部」回查 -->
      <el-empty
        v-if="!timeline.length"
        description="暂无时间线事件"
      />
      <el-timeline v-else>
        <el-timeline-item
          v-for="ev in viewTimeline"
          :key="ev.id"
          :type="eventTypeColor(ev.eventType)"
        >
          <!-- 设计图润色：事件卡片化（语义色淡底）+ 顶部分类徽标 + 标题/描述/时间分层 -->
          <div
            class="tl-item"
            :class="'tone-' + eventTypeColor(ev.eventType)"
          >
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
            <div
              class="tl-title"
              :title="eventLabel(ev.eventType)"
            >
              {{ eventLabel(ev.eventType) }}
            </div>
            <!-- 人话化：把事件类型/payload 翻译成非开发者能看懂的一句话 -->
            <div class="tl-desc">
              {{ eventDescription(ev) }}
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <!-- 方案 2：产出附件（执行产出物化后可单独下载；无附件时不展示卡片）。
         右栏沉底：时间线恒与对话流卡顶对齐，附件条件渲染不产生空行间隙 -->
    <el-card
      v-if="item && attachments.length"
      id="sec-att"
      class="side-card att-card"
    >
      <template #header>
        <div class="card-header">
          <span>产出附件</span>
          <span style="font-size:12px;color:var(--ha-muted)">共 {{ attachments.length }} 个</span>
          <el-switch
            v-if="historyAttachments.length"
            v-model="showHistoryAtt"
            size="small"
            inline-prompt
            active-text="历史"
            inactive-text="有效"
            style="margin-left:12px"
            @change="() => {}"
          />
        </div>
      </template>
      <div class="att-list">
        <div
          v-for="att in viewAttachments"
          :key="String(att.id)"
          class="att-item"
          :class="{ inactive: att.status !== 'ACTIVE' }"
        >
          <div class="att-thumb">
            {{ attExt(att.fileName) }}
          </div>
          <div class="att-info">
            <span
              class="att-name"
              :title="att.fileName"
            >{{ att.fileName }}</span>
            <span class="att-meta">{{ attExt(att.fileName) }} · {{ fmtSize(att.fileSize) }}</span>
          </div>
          <el-tag
            v-if="att.status === 'INACTIVE'"
            size="small"
            type="info"
          >
            旧版本
          </el-tag>
          <el-button
            link
            size="small"
            type="primary"
            :loading="downloadingAttId === att.id"
            @click="downloadAttachment(att)"
          >
            下载
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { subTaskApi } from '@/api/subTask'
import { agentApi } from '@/api/agent'
import { reviewApi } from '@/api/review'
import { attachmentApi } from '@/api/attachment'
import { saveBlobResponse } from '@/utils/download'
import MarkdownView from '@/components/MarkdownView.vue'
import ReviewVerdictView from '@/components/ReviewVerdictView.vue'
import SubTaskSequenceFlow from '@/components/SubTaskSequenceFlow.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { fmtTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import type { SubTask, TaskTimelineItem, ConversationMessageItem, Attachment, LongId, Agent } from '@/types'

const route = useRoute()
const router = useRouter()
const item = ref<SubTask | null>(null)
const loading = ref(false)
const timeline = ref<TaskTimelineItem[]>([])
const conversation = ref<ConversationMessageItem[]>([])
let pollTimer: number | null = null
const timelinePolling = ref(false)
// 对话流卡内页签：执行对话流 / 执行时序图（时序图从时间线卡迁入，左主栏画布更宽）
const convView = ref<'conv' | 'seq'>('conv')

// 版本三：时间线默认只呈现关键里程碑，例行内部事件静默过滤（技术详情不再露出）
const timelineFull = ref(false)

// 精简版隐藏的例行内部事件（分发/指令流转/上下文装配/思考过程等，全量视图仍可回查）
const COMPACT_HIDDEN_EVENTS = new Set([
  'sub_task_dispatch_prepare',
  'sub_task_auto_execute_dispatch_enter',
  'sub_task_auto_execute_dispatch',
  'sub_task_auto_execute_dispatch_ok',
  'sub_task_execution_command_created',
  'sub_task_execution_command_consume',
  'sub_task_execution_command_consume_skipped',
  'sub_task_execution_command_poll_recovery',
  'sub_task_execute_enter',
  'sub_task_execute_before_platform',
  'sub_task_deps_context_loaded',
  'sub_task_spec_context_loaded',
  'sub_task_execute_thinking',
  'sub_task_llm_call_start',
  'subtask_review_prompt',
  'subtask_review_thinking',
  'subtask_dual_review_prompt',
  'subtask_dual_review_thinking',
  'subtask_recheck_prompt',
  'subtask_recheck_thinking'
])

const viewTimeline = computed(() => {
  if (timelineFull.value) return timeline.value
  return timeline.value.filter(ev => !COMPACT_HIDDEN_EVENTS.has(ev.eventType))
})

// 可被精简过滤的例行事件数（基于全量稳定计数，不随展开态变化，否则展开后按钮消失）
// >0 时卡头露出「展开全部 / 只看关键节点」切换入口
const hiddenEventCount = computed(() => timeline.value.filter(ev => COMPACT_HIDDEN_EVENTS.has(ev.eventType)).length)

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
  // 异常优先判定（避免 auto_review_rejected 含 review 被误判为 success）
  if (eventType.includes('failed') || eventType.includes('rejected') || eventType.includes('blocked') || eventType.includes('dead_letter')) return 'danger'
  if (eventType.includes('paused') || eventType.includes('warning') || eventType.includes('unparseable') || eventType.includes('degraded') || eventType.includes('disagreement') || eventType.includes('discrepancy') || eventType.includes('incomplete') || eventType.includes('timeout') || eventType.includes('offline_reassign')) return 'warning'
  if (eventType.includes('completed') || eventType.includes('submitted') || eventType.includes('passed') || eventType.includes('ok') || eventType.includes('materialized') || eventType.includes('consistent') || eventType.includes('consented')) return 'success'
  if (eventType.includes('assigned') || eventType.includes('created') || eventType.includes('dispatch') || eventType.includes('command')) return 'primary'
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
  // 超时改派（2026-09-01：关键调度节点可观测——超时未领取 / 执行超时）
  sub_task_unclaimed_timeout_reassign: { label: '超时未领取改派', desc: '分配后 Agent 超时未领取，系统自动改派其他 Agent' },
  sub_task_execution_timeout_reassign: { label: '执行超时改派', desc: '执行超过时限，系统判定超时并转入失败回退链重新分发' },
  sub_task_offline_reassign: { label: '离线改派', desc: '分配后 Agent 心跳丢失（离线），系统自动改派其他 Agent' },
  // 执行
  sub_task_execute_enter: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_start: { label: '开始执行', desc: '执行 Agent 开始处理子任务' },
  sub_task_execute_before_platform: { label: '执行前准备', desc: '执行前的平台准备工作' },
  sub_task_deps_context_loaded: { label: '参考上游产出', desc: '执行 Agent 已读取前置子任务的交付结果，作为本次执行的参考' },
  sub_task_spec_context_loaded: { label: '装配任务上下文', desc: '执行 Agent 已装配任务全局上下文与直接前置产出，作为本次执行的参考' },
  sub_task_llm_call_start: { label: '调用大模型', desc: '执行 Agent 开始请求大模型生成内容' },
  sub_task_llm_call_end: { label: '大模型返回', desc: '大模型已返回生成结果' },
  sub_task_llm_call_failed: { label: '大模型失败', desc: '调用大模型失败（超时或网络异常）' },
  sub_task_execute_thinking: { label: '思考过程', desc: '执行 Agent 的思考 / 推理过程' },
  sub_task_execute: { label: '执行产出', desc: '执行 Agent 产出了内容' },
  sub_task_execute_submit: { label: '提交产出', desc: '执行 Agent 提交了本次产出' },
  sub_task_artifact_materialized: { label: '产出物化', desc: '执行产出已物化为附件，可在产出附件中下载' },
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
  // V58: 双审 / 抽检（链路来源区分，timeline 事件 + 对话流消息类型共用字典）
  sub_task_dual_review_consented: { label: '双审共识', desc: '两位评审结论一致，按共识策略落地' },
  sub_task_dual_review_incomplete: { label: '双审缺失', desc: '双审核验不完整，子任务停留 REVIEW 等人工' },
  sub_task_dual_review_degraded: { label: '双审降级', desc: '双审候选不足，降级为单审' },
  sub_task_reviewer_disagreement: { label: '双审分歧', desc: '两位评审结论不一致，转人工介入' },
  sub_task_recheck_consistent: { label: '抽检一致', desc: '抽检复审与原判定一致' },
  sub_task_recheck_discrepancy: { label: '抽检分歧', desc: '抽检复审与原判定不一致，仅度量不改状态' },
  subtask_dual_review_prompt: { label: '双审请求', desc: '双审发起对产出的核验' },
  subtask_dual_review_verdict: { label: '双审结论', desc: '双审给出的核验结论' },
  subtask_dual_review_thinking: { label: '双审思考', desc: '双审的分析过程' },
  subtask_dual_review_result: { label: '双审共识', desc: '双审按共识策略落定的结论' },
  subtask_recheck_prompt: { label: '抽检请求', desc: '抽检复审发起的核验' },
  subtask_recheck_verdict: { label: '抽检审查', desc: '抽检复审给出的核验结论' },
  subtask_recheck_thinking: { label: '抽检思考', desc: '抽检复审的分析过程' },
  subtask_recheck_result: { label: '抽检结论', desc: '抽检复审结论（只度量不改状态）' },
  // 死信 / 重派
  sub_task_dead_letter: { label: '进入死信', desc: '多次失败，子任务进入死信池' },
  sub_task_dead_letter_manual_assign: { label: '死信重派', desc: '人工把死信子任务重新指派给 Agent' },
  // 核验熔断 / 人工介入（2026-08-19：与调度死信对称，OPS/DLQ 泳道可回溯）
  sub_task_review_dead_letter: { label: '核验熔断', desc: '核验返工超过上限，子任务进入死信池，等待人工决定通过/改派' },
  sub_task_manual_intervention_required: { label: '人工介入', desc: '系统判定该子任务需要人工处置（改派/人工通过/人工驳回）' },
  sub_task_manual_rework_reset: { label: '人工改派', desc: '人工驳回并改派执行者，同时重置返工计数' },
  // 任务级
  task_plan_generated: { label: '生成拆解', desc: '已生成任务拆解草案' },
  task_plan_confirmed: { label: '确认拆解', desc: '拆解草案已确认' },
  task_plan_rejected: { label: '驳回拆解', desc: '拆解草案被驳回' },
  task_plan_failed: { label: '拆解失败', desc: '任务拆解失败' },
  task_plan_llm_call_start: { label: '拆解调模型', desc: '开始请求大模型进行任务拆解' },
  task_auto_completed: { label: '任务完成', desc: '所有子任务完成，主任务自动收尾' }
}

const TRIGGER_LABEL: Record<string, string> = {
  auto_assign: '自动分配', manual: '手动触发', blocked_reassign: '阻塞后重新调度',
  dead_letter_redispatch: '死信重投', poll_recovery: '巡检恢复',
  agent_offline: 'Agent 离线（心跳丢失）', assigned_timeout: '分配后超时未领取',
  external_fallback: '外部失败回退'
}

function eventLabel(eventType: string): string {
  return EVENT_META[eventType]?.label || eventType
}

// payload 内英文枚举值人话化（避免用户看到裸英文，如 dependency_not_ready）
const REASON_LABEL: Record<string, string> = {
  dependency_not_ready: '依赖任务未完成'
}
function reasonText(reason: unknown): string {
  const r = String(reason || '')
  return REASON_LABEL[r] || r
}

// 语义分类（设计图：事件卡顶部类型徽标），供 el-tag 展示简短分类词
type EventCategory = '分发' | '执行' | '核验' | '人工介入' | '任务' | '流程'
function eventCategory(eventType: string): EventCategory {
  if (/review|recheck/.test(eventType)) return '核验'
  if (/dead_letter|manual|blocked|intervention|rework/.test(eventType)) return '人工介入'
  if (/dispatch|command|assigned|timeout_reassign|offline_reassign/.test(eventType)) return '分发'
  if (/^task_|task_auto/.test(eventType)) return '任务'
  if (/execute|llm|artifact|context_loaded|thinking|report/.test(eventType)) return '执行'
  return '流程'
}

// Agent ID → 注册名字映射（一次性拉取全量 Agent），展示注册名而非原始 ID
const agentNameMap = ref<Record<string, string>>({})
// §6.52 人工介入面板：全量 Agent 候选（改派目标选择器数据源）
const agents = ref<Agent[]>([])
async function loadAgents() {
  try {
    const list = await agentApi.list()
    agents.value = list
    const map: Record<string, string> = {}
    list.forEach((a) => { map[String(a.id)] = a.name })
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

// 双审单侧结论人话化：通过/驳回（评分 N/5）；字段缺失返回空串由调用方降级
type DualVerdictPayload = { pass1?: boolean; pass2?: boolean; score1?: number; score2?: number }
function dualVerdictText(pass: boolean | undefined, score: number | undefined): string {
  if (pass === undefined || pass === null) return ''
  const text = pass ? '通过' : '驳回'
  return score !== undefined && score !== null ? text + '（评分 ' + score + '/5）' : text
}

function eventDescription(ev: TaskTimelineItem): string {
  const base = EVENT_META[ev.eventType]?.desc || ev.eventType
  const p = ev.payload || {}
  // 双审并行校验结果：后端事件 payload 已含双方结论，此处翻译成人话展示（2026-09-01）
  if (ev.eventType === 'sub_task_dual_review_consented' || ev.eventType === 'sub_task_reviewer_disagreement') {
    const dp = p as DualVerdictPayload
    const v1 = dualVerdictText(dp.pass1, dp.score1)
    const v2 = dualVerdictText(dp.pass2, dp.score2)
    if (v1 && v2) return base + '（评审1：' + v1 + '；评审2：' + v2 + '）'
  }
  // 双审降级：候选数人话化（避免裸露英文 reason 键值）
  if (ev.eventType === 'sub_task_dual_review_degraded' && p.available !== undefined) {
    return base + '（可用核验候选 ' + p.available + ' 个）'
  }
  const extras: string[] = []
  if (p.trigger) extras.push('触发方式：' + (TRIGGER_LABEL[p.trigger] || p.trigger))
  if (p.reason) extras.push('原因：' + reasonText(p.reason))
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

// ── §6.52 人工介入：返工达上限 / 降级能力不匹配时，用户自主选择 agent 改派或直接通过 ──
const manualTargetAgentId = ref<string>('')
const manualSubmitting = ref(false)

// 「原执行者重做」特殊值：提交时映射为 reworkAgentId=null（后端 = 原执行者重做）
const KEEP_CURRENT = '__KEEP_CURRENT__'
// 当前负责人展示名（Agent 列表缺失时降级为短 ID）
const currentExecutorName = computed(() => {
  if (!item.value?.assignedAgent) return ''
  return resolveAgentName(item.value.assignedAgent)
})

// REVIEW 卡死判定：context 有人工介入标记，或返工次数已达后端默认上限
// （autoReviewMaxRework=3，兼容标记落库前的存量卡死任务）
const needsManualIntervention = computed(() => {
  if (!item.value || item.value.status !== 'REVIEW') return false
  if (item.value.context?.manualIntervention) return true
  return item.value.reworkCount >= 3
})

const manualReasonText = computed(() => {
  const mark = item.value?.context?.manualIntervention as Record<string, any> | undefined
  if (mark?.reason === 'fallback_skip_execution_dense') {
    return '降级能力不匹配：执行密集任务未自动回退给无本机能力的 LLM Agent'
  }
  return '返工已达上限（' + (item.value?.reworkCount ?? 0) + ' 次），自动核验已停止'
})

// 改派候选：EXECUTOR 角色 + ACTIVE，排除当前负责人（原执行者重做走下方独立选项），
// 在线优先（外部/内部 Agent 均可选）；当前负责人若为唯一内部 Agent 时仍可通过
// 「原执行者重做」选项（reworkAgentId=null）回到原执行者，避免候选列表出现外部 Agent
// 一枝独秀、内部 Agent 完全不可选的情况（§6.55/§6.57 决策）
const manualCandidates = computed(() => {
  if (!item.value) return []
  const current = item.value.assignedAgent != null ? String(item.value.assignedAgent) : ''
  return agents.value
    .filter(a => a.role === 'EXECUTOR' && a.status === 'ACTIVE' && String(a.id) !== current)
    .sort((a, b) => {
      const rank = (x: Agent) => (x.onlineStatus === 'IDLE' || x.onlineStatus === 'ONLINE' ? 0 : 1)
      return rank(a) - rank(b)
    })
})

function accessTypeLabel(t: string | undefined): string {
  if (t === 'CLI_CLIENT') return '外部 CLI（暂未上线）'
  if (t === 'WEB_BROWSER') return '外部 GUI'
  if (t === 'API_KEY_LLM') return '内部 LLM'
  return t || '未知'
}

// 驳回并改派：REVIEW → REWORK + 换 assignedAgent（后端 createReview REJECTED + reworkAgentId）
// 选中「原执行者重做」（KEEP_CURRENT）时 reworkAgentId=null，后端 reworkFresh 重置计数后原执行者重做
async function submitManualRework() {
  if (!item.value || !manualTargetAgentId.value) return
  manualSubmitting.value = true
  try {
    const reworkAgentId = manualTargetAgentId.value === KEEP_CURRENT ? null : manualTargetAgentId.value
    await reviewApi.create({
      subTaskId: item.value.id,
      result: 'REJECTED',
      score: 1,
      issues: '人工介入：自动链路已停止（返工达上限/能力不匹配），' + (reworkAgentId ? '改派 Agent ' + reworkAgentId : '原执行者重做') + '，重新执行',
      comment: '人工驳回改派（平台管理员）',
      reworkAgentId
    })
    ElMessage.success(reworkAgentId ? '已驳回并改派，等待新 Agent 认领执行' : '已驳回，等待原执行者重做提交')
    manualTargetAgentId.value = ''
    await pollOnce()
  } catch {
    // 拦截器已弹错误提示
  } finally {
    manualSubmitting.value = false
  }
}

// 直接通过：人工验收（APPROVED 不受返工上限限制，必然能收尾）
async function submitManualApprove() {
  if (!item.value) return
  manualSubmitting.value = true
  try {
    await reviewApi.create({
      subTaskId: item.value.id,
      result: 'APPROVED',
      score: 4,
      issues: '',
      comment: '人工验收通过（平台管理员）',
      reworkAgentId: null
    })
    ElMessage.success('已人工验收通过，子任务完成')
    await pollOnce()
  } catch {
    // 拦截器已弹错误提示
  } finally {
    manualSubmitting.value = false
  }
}

// V28: 对话流消息来源标签（toolName → 展示文案/颜色）
const CONV_TAG_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'info' | 'warning' }> = {
  sub_task_execute_user_prompt: { label: '执行请求', type: 'info' },
  sub_task_execute_thinking: { label: '思考过程', type: 'info' },
  sub_task_execute: { label: '执行产出', type: 'success' },
  sub_task_execute_failed: { label: '执行失败', type: 'danger' },
  subtask_review_prompt: { label: '核验请求', type: 'info' },
  subtask_review_thinking: { label: '核验思考', type: 'info' },
  subtask_review_verdict: { label: '核验分析', type: 'warning' },
  subtask_review_result: { label: '核验结论', type: 'success' },
  // V58: 双审 / 抽检链路来源区分（与单审独立标签，执行对话流可分辨）
  subtask_dual_review_prompt: { label: '双审请求', type: 'info' },
  subtask_dual_review_thinking: { label: '双审思考', type: 'info' },
  subtask_dual_review_verdict: { label: '双审分析', type: 'warning' },
  subtask_dual_review_result: { label: '双审结论', type: 'success' },
  subtask_recheck_prompt: { label: '抽检请求', type: 'info' },
  subtask_recheck_thinking: { label: '抽检思考', type: 'info' },
  subtask_recheck_verdict: { label: '抽检分析', type: 'warning' },
  subtask_recheck_result: { label: '抽检结论', type: 'danger' }
}

interface ConvRound {
  type: 'execute' | 'review'
  roundNo: number
  messages: ConversationMessageItem[]
}

// 把扁平消息按「执行轮次 / 核验轮次」分组，方便看清 Agent ↔ LLM 完整请求/返回
const convRounds = computed<ConvRound[]>(() => {
  const rounds: ConvRound[] = []
  let executeNo = 0
  let reviewNo = 0
  const current: { value: ConvRound | null } = { value: null }

  const flush = () => {
    if (current.value) {
      rounds.push(current.value)
      current.value = null
    }
  }
  const startRound = (type: 'execute' | 'review') => {
    flush()
    current.value = {
      type,
      roundNo: type === 'execute' ? ++executeNo : ++reviewNo,
      messages: []
    }
  }

  for (const msg of conversation.value) {
    const tool = msg.toolName || ''
    const isExecute = tool === 'sub_task_execute_user_prompt' || tool === 'sub_task_execute' || tool === 'sub_task_execute_failed'
    // V58: 核验轮次识别含单审 / 双审 / 抽检三链路前缀
    const isReview = tool.startsWith('subtask_review') || tool.startsWith('subtask_dual_review') || tool.startsWith('subtask_recheck')
    if (isExecute && (!current.value || current.value.type !== 'execute')) {
      startRound('execute')
    } else if (isReview && (!current.value || current.value.type !== 'review')) {
      startRound('review')
    }
    if (!current.value) {
      startRound('execute')
    }
    current.value!.messages.push(msg)
  }
  flush()
  return rounds
})

function convTagLabel(toolName: string | null) {
  return (toolName && CONV_TAG_MAP[toolName]?.label) || toolName || '消息'
}

function convTagType(toolName: string | null, content: string | null | undefined = null) {
  const base = (toolName && CONV_TAG_MAP[toolName]?.type) || 'info'
  // 核验结论类（_result）跟随 verdict.pass 切色：默认绿（success），驳回走红（danger）。
  // 说明：仅 _result 动态切；_verdict（核验分析）保持原 warning 黄色作类别标识，
  // 因为其内容已走 ReviewVerdictView 单独展示 通过/未通过 tag，避免重复表达。
  if (toolName && /subtask_(review|dual_review|recheck)_result$/.test(toolName)) {
    return parseVerdictPass(content) ? 'success' : 'danger'
  }
  return base
}

// 设计图润色：消息块语义分类 → is-user / is-execute / is-analysis / is-result
// 互不冲突的修饰类，驱动头像、背景、边框的颜色分支
// 说明：is-result 现在包含 _verdict 与 _result 两类（核验分析与核验结论），
// 外层框体都需要按 pass/fail 切色；_verdict 内容走 JSON 解析，_result 多为遗留 Markdown 走关键字兜底
function convItemClass(msg: ConversationMessageItem): Record<string, boolean> {
  const tool = msg.toolName || ''
  const isUser = tool === 'sub_task_execute_user_prompt'
  const isExecute = tool === 'sub_task_execute' || tool === 'sub_task_execute_failed'
  const isAnalysis = isAnalysisMsg(msg)
  // 核验类（subtask_(review|dual_review|recheck)_(verdict|result)）
  const isResult = /subtask_(review|dual_review|recheck)_(verdict|result)$/.test(tool)
  const verdictFail = isResult && !isUser && !isExecute && !parseVerdictPass(msg.content)
  return {
    'is-user': isUser,
    'is-execute': isExecute && !isUser,
    'is-analysis': isAnalysis && !isUser && !isExecute,
    'is-result': isResult && !isUser && !isExecute,
    'is-result-fail': verdictFail
  }
}

// 从核验结论内容中判断通过/驳回：先尝试解析 JSON verdict.pass，
// 失败则按文案关键字兑底（兼容 `- 结果: 驳回` 这类遗留 Markdown 结论）。
// 解析不出时默认 true（走原绿色），避免误将未知内容刷红。
// 冒号同时匹配 ASCII 半角与中文全角（\uFF1A），避免遗漏 `结果：驳回` 这类中文写法。
// 说明：is-result 现在包含 _verdict 与 _result 两类（核验分析与核验结论），
// 外层框体都需要按 pass/fail 切色；_verdict 内容走 JSON 解析，_result 多为遗留 Markdown 走关键字兜底
function parseVerdictPass(content: string | null | undefined): boolean {
  const raw = (content || '').trim()
  if (!raw) return true
  try {
    const t = raw.replace(/^```(?:json)?\s*([\s\S]*?)\s*```$/, '$1').trim()
    if (t.startsWith('{')) {
      const obj = JSON.parse(t)
      if (obj && typeof obj === 'object' && 'pass' in obj) {
        return !!obj.pass
      }
    }
  } catch { /* 不是 JSON，回退关键字判定 */ }
  if (/结果\s*[:：]\s*(驳回|未通过|拒绝|失败|不达标)|核验未通过|核验驳回|未达标|\b驳回\b|\b未通过\b/.test(raw)) return false
  return true
}

// 设计图润色：头像缩写（USER/AI/审核）三角色
function convAvatarText(msg: ConversationMessageItem): string {
  const tool = msg.toolName || ''
  if (tool === 'sub_task_execute_user_prompt') return 'U'
  if (tool === 'sub_task_execute' || tool === 'sub_task_execute_failed') return 'AI'
  // 核验类：审核（双审、抽检、单审均走核验视觉）
  if (tool.startsWith('subtask_review') || tool.startsWith('subtask_dual_review') || tool.startsWith('subtask_recheck')) return '审'
  // 思考类同走核验视觉（橙）
  if (tool === 'sub_task_execute_thinking') return '思'
  return '·'
}

// 设计图润色：可折叠消息的真实字数（用于展开全文括号提示）
function msgContentLength(msg: ConversationMessageItem): number {
  return (msg.content || '').length
}

// ── 对话流消息块展开/收缩：核验分析/思考过程默认收起（一行摘要预览），长文按需展开 ──
// 分析/思考类消息（单审/双审/抽检 verdict + thinking + 执行思考）信息密度低、篇幅长，默认折叠
const ANALYSIS_TOOLS = new Set([
  'subtask_review_verdict', 'subtask_dual_review_verdict', 'subtask_recheck_verdict',
  'subtask_review_thinking', 'subtask_dual_review_thinking', 'subtask_recheck_thinking',
  'sub_task_execute_thinking'
])

function isAnalysisMsg(msg: ConversationMessageItem): boolean {
  return ANALYSIS_TOOLS.has(msg.toolName || '')
}

function isCollapsible(msg: ConversationMessageItem): boolean {
  return isAnalysisMsg(msg) || (msg.content?.length || 0) > 300
}

// 逐条用户开关（默认值：分析类收起、其余展开），轮询刷新不重置已打开的块
const msgToggleOverride = ref<Record<string, boolean>>({})

function isMsgExpanded(msg: ConversationMessageItem): boolean {
  const key = String(msg.id)
  if (key in msgToggleOverride.value) return msgToggleOverride.value[key]
  return false
}

function toggleMsg(msg: ConversationMessageItem) {
  const key = String(msg.id)
  msgToggleOverride.value[key] = !(key in msgToggleOverride.value ? msgToggleOverride.value[key] : false)
}

// 收起态预览：首个非空行，剖掉 Markdown 符号，截断 120 字
function msgPreview(msg: ConversationMessageItem): string {
  const line = (msg.content || '').split('\n').map(l => l.trim()).find(l => l) || ''
  const plain = line.replace(/[#*`>_|-]/g, ' ').replace(/\s+/g, ' ').trim()
  return plain.length > 120 ? plain.slice(0, 120) + '…' : (plain || '（无内容）')
}

// ── 轮次头部元信息（设计图：#N · type 徽标 + Agent 标签 + 状态标签 + 时间右对齐）──
function roundAgentName(round: ConvRound): string {
  const first = round.messages.find(m => m.senderId)
  if (first) return resolveAgentName(first.senderId)
  return item.value?.assignedAgentName || resolveAgentName(item.value?.assignedAgent ?? null) || '-'
}

function roundMeta(round: ConvRound): { statusLabel: string; failed: boolean; time: string } {
  const failed = round.messages.some(m => m.toolName === 'sub_task_execute_failed')
  const last = round.messages[round.messages.length - 1]
  return {
    statusLabel: failed ? '失败' : '已完成',
    failed,
    time: last ? fmtTime(last.createTime) : ''
  }
}

// 版本三第四轮修正：概览统计卡已移除（计数与各卡头「共 N 条」重复），statCards/scrollToSection 一并下线

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
// 附件版本化（2026-08-19）：同名重传后旧版 INACTIVE；默认只看有效版，"历史"开关回查旧版
const showHistoryAtt = ref(false)
const historyAttachments = computed(() => attachments.value.filter(a => a.status !== 'ACTIVE'))
const viewAttachments = computed(() => showHistoryAtt.value ? attachments.value : attachments.value.filter(a => a.status === 'ACTIVE'))

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

// 附件类型缩略块：取扩展名大写（无扩展名降级 FILE），对齐设计图缩略图位置
function attExt(fileName: string | null | undefined): string {
  const ext = (fileName || '').split('.').pop()?.toUpperCase() || ''
  return ext && ext.length <= 5 ? ext : 'FILE'
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
/* 版本三：页面级 CSS Grid 双栏——头部/统计/摘要/人工介入通栏，
   对话流占左主栏（跨 2 行），产出附件 + 时间线自然堆叠在右栏 */
.page {
  max-width: var(--ha-content-width);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 16px;
  align-items: start;
}
.page .full-row { grid-column: 1 / -1; }
.page .main-col { grid-column: 1; grid-row: span 2; }
/* 右栏自动堆叠（DOM 顺序 = 时间线先、附件后）：时间线恒与对话流卡顶对齐，
   附件条件渲染缺失时不产生空行间隙 */
.page .side-card { grid-column: 2; }
#sec-conv, #sec-att, #sec-tl { scroll-margin-top: 16px; }

/* 版本三：头部卡片（状态徽标 + 标题 + 元信息 + 依赖标签） */
.head-card {
  border: 1px solid var(--ha-border);
  box-shadow: var(--ha-shadow-sm);
  transition: box-shadow var(--ha-duration-normal) var(--ha-ease-out);
}
.head-card:hover { box-shadow: var(--ha-shadow-md); }
.head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}
.head-badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
/* 设计图：左侧"子任务详情"小标签，作为页内面包屑替代 */
.head-crumb {
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-muted);
  letter-spacing: 0.01em;
}
/* 设计图：标题字重加大、字距收紧，与下方元信息形成强对比 */
.head-title {
  margin: 10px 0 8px;
  font-size: 22px;
  line-height: 1.35;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--ha-ink);
  text-wrap: balance;
}
/* 设计图：元信息行字号统一 12px，字色 muted，主标签/值之间用细分隔点 */
.head-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  color: var(--ha-muted);
  font-size: 12px;
  line-height: 1.6;
  gap: 0;
}
.head-meta > span { display: inline-flex; align-items: center; gap: 6px; }
.head-meta > span + span::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 3px;
  margin: 0 10px;
  border-radius: 50%;
  background: var(--ha-border);
}
.head-meta .meta-key { color: var(--ha-muted); }
/* 设计图：依赖行小标签（前置依赖 / 被依赖），与下方面包屑色一致 */
.dep-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 6px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--ha-border-light);
}
.dep-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-muted);
  margin-left: 12px;
}
.dep-row > .dep-label:first-child { margin-left: 0; }
/* 设计图：依赖标签胶囊化（圆角更小）+ hover 提亮（参考图标签形态：圆角胶囊） */
.dep-tag {
  cursor: pointer;
  margin: 0;
  border-radius: 999px !important;
  font-variant-numeric: tabular-nums;
}
.dep-tag:hover { filter: brightness(1.08); }

/* 版本三：任务摘要（已并入头部卡，虚线分隔） */
.head-summary {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface);
}
.head-summary-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ha-muted);
  margin-bottom: 6px;
  letter-spacing: 0.01em;
}
.summary-text {
  font-size: 13px;
  line-height: 1.75;
  color: var(--ha-ink-secondary, inherit);
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 72ch;
}

/* §6.52 人工介入面板 */
.manual-body { display: flex; flex-direction: column; gap: 12px; }
.manual-reason { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.manual-current { color: var(--ha-muted); font-size: 12px; }
.manual-actions { display: flex; flex-direction: column; gap: 10px; }
.manual-row { display: flex; align-items: center; gap: 10px; }
.manual-label { width: 44px; color: var(--ha-muted); font-size: 12px; flex-shrink: 0; }
/* M4.5: 执行时间线——设计图润色：事件卡片化（语义色淑底） + 顶部分类徽标 + 标题/描述/时间分层 */
.tl-card,
.att-card {
  border: 1px solid var(--ha-border);
  box-shadow: var(--ha-shadow-sm);
}
.tl-head-right { display: flex; align-items: center; gap: 8px; }
/* 设计图润色：事件卡片化——语义色淑底 + 细边框 + 微阴影，hover 边框提亮 */
.tl-item {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface, transparent);
  transition: border-color var(--ha-duration-fast, 150ms) var(--ha-ease-out, ease-out),
              background-color var(--ha-duration-fast, 150ms) var(--ha-ease-out, ease-out);
}
.tl-item:hover { border-color: var(--ha-primary); }
/* 设计图润色：事件卡顶行【分类徽标 · 右对齐时间】，统一间距 */
.tl-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.tl-top :deep(.el-tag) { border-radius: 999px; font-size: 11px; }
/* 事件名单行裁断 */
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

/* 设计图润色：Timeline 本身的圆点改为语义色点 + 缩小默认尺寸 */
.tl-card :deep(.el-timeline-item__node) {
  background-color: var(--ha-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ha-primary) 18%, transparent);
}
.tl-card :deep(.el-timeline-item__node--success) { background-color: var(--ha-success); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ha-success) 18%, transparent); }
.tl-card :deep(.el-timeline-item__node--warning) { background-color: var(--ha-warning); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ha-warning) 18%, transparent); }
.tl-card :deep(.el-timeline-item__node--danger)  { background-color: var(--ha-danger);  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ha-danger) 18%, transparent); }
.tl-card :deep(.el-timeline-item__node--info)    { background-color: var(--ha-info);    box-shadow: 0 0 0 3px color-mix(in srgb, var(--ha-info) 18%, transparent); }
.tl-card :deep(.el-timeline) { padding-left: 4px; }
.tl-card :deep(.el-timeline-item__wrapper) { padding-left: 14px; top: -2px; }
.tl-card :deep(.el-timeline-item__timestamp) { display: none; }

/* 产出附件：设计图润色——左侧类型缩略块 + 右侧文件名/类型·大小双行 */
.att-list { display: flex; flex-direction: column; gap: 8px; }
.att-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface);
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out);
}
.att-item:hover { border-color: var(--ha-border); }
/* 历史版本（INACTIVE）灰显：仍可下载回查，但视觉上弱化 */
.att-item.inactive { opacity: 0.55; }
.att-item.inactive .att-name { text-decoration: line-through; }
.att-thumb {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ha-radius-md);
  background: color-mix(in srgb, var(--ha-primary) 12%, var(--ha-surface));
  border: 1px solid color-mix(in srgb, var(--ha-primary) 28%, var(--ha-border-light));
  color: var(--ha-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.att-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.att-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 500; color: var(--ha-ink); }
.att-meta { color: var(--ha-muted); font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }

/* V28: 执行对话流（设计图润色：轮次头左圆角胶囊 + 消息块三段语义底色） */
.conv-card {
  border: 1px solid var(--ha-border);
  box-shadow: var(--ha-shadow-sm);
}
.conv-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.conv-tabs :deep(.el-tabs__nav-wrap::after) { background-color: var(--ha-border-light); }
.conv-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  font-weight: 500;
  color: var(--ha-muted);
}
.conv-tabs :deep(.el-tabs__item.is-active) { color: var(--ha-primary); font-weight: 600; }
.conv-tabs :deep(.el-tabs__active-bar) { background-color: var(--ha-primary); }

/* 轮次列表：间距与左对齐 */
.conv-rounds { display: flex; flex-direction: column; gap: 14px; }

/* 轮次卡：细边框 + 与表面同色背景（不要额外 surface，避免与卡片背景色冲突） */
.conv-round {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  padding: 12px 14px;
  background: transparent;
}
/* 轮次头：左徽标堆叠 + 右时间，底部细线分隔 */
.round-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ha-border-light);
}
.round-badges { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.round-badges :deep(.el-tag) { border-radius: 999px; }
.round-time {
  color: var(--ha-muted);
  font-size: 12px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* 消息列表 */
.conv-list { display: flex; flex-direction: column; gap: 10px; }

/* 消息块：默认表面背景（用户/AI），分析类另加语义底色 */
.conv-item {
  display: flex;
  align-items: stretch;
  gap: 10px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  padding: 10px 12px;
  background: var(--ha-surface);
  transition: border-color var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-item:hover { border-color: var(--ha-border); }
/* 设计图：AI 产出（sub_task_execute）背景走 success 语义底 */
.conv-item.is-execute {
  background: color-mix(in srgb, var(--ha-success) 6%, var(--ha-surface));
  border-color: color-mix(in srgb, var(--ha-success) 22%, var(--ha-border-light));
}
/* 用户请求走 info 语义底（与 AI 反差） */
.conv-item.is-user {
  background: color-mix(in srgb, var(--ha-info) 6%, var(--ha-surface));
  border-color: color-mix(in srgb, var(--ha-info) 20%, var(--ha-border-light));
}
/* 核验分析 / 思考过程：警告底色 + 语义边框（设计图核验分析黄块） */
.conv-item.is-analysis {
  background: color-mix(in srgb, var(--ha-warning) 7%, var(--ha-surface));
  border-color: color-mix(in srgb, var(--ha-warning) 28%, var(--ha-border-light));
}
/* 核验结论：默认 success 绿色语义（与核验分析黄块区分） */
.conv-item.is-result:not(.is-result-fail) {
  background: color-mix(in srgb, var(--ha-success) 7%, var(--ha-surface));
  border-color: color-mix(in srgb, var(--ha-success) 28%, var(--ha-border-light));
}
/* 核验结论：驳回则走 danger 红色语义（绿色代表通过，红色代表驳回） */
.conv-item.is-result.is-result-fail {
  background: color-mix(in srgb, var(--ha-danger) 7%, var(--ha-surface));
  border-color: color-mix(in srgb, var(--ha-danger) 30%, var(--ha-border-light));
}

/* 设计图：左侧角色小图标（圆形背景） */
.conv-avatar {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: color-mix(in srgb, var(--ha-info) 14%, transparent);
  color: var(--ha-info-text);
}
.conv-item.is-execute .conv-avatar {
  background: color-mix(in srgb, var(--ha-success) 18%, transparent);
  color: var(--ha-success-text);
}
.conv-item.is-analysis .conv-avatar {
  background: color-mix(in srgb, var(--ha-warning) 22%, transparent);
  color: var(--ha-warning-text);
}
.conv-item.is-result:not(.is-result-fail) .conv-avatar {
  background: color-mix(in srgb, var(--ha-success) 22%, transparent);
  color: var(--ha-success-text);
}
.conv-item.is-result.is-result-fail .conv-avatar {
  background: color-mix(in srgb, var(--ha-danger) 22%, transparent);
  color: var(--ha-danger-text);
}
/* 用户消息头像走主色 */
.conv-item.is-user .conv-avatar {
  background: color-mix(in srgb, var(--ha-primary) 18%, transparent);
  color: var(--ha-primary);
}

.conv-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.conv-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.conv-item-head.clickable { cursor: pointer; }
.conv-item-head :deep(.el-tag) { border-radius: 999px; }
.conv-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 2px;
}
.conv-meta {
  color: var(--ha-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.conv-caret {
  margin-left: 4px;
  color: var(--ha-muted);
  transition: transform var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-caret.open { transform: rotate(90deg); }

/* 设计图：可折叠消息的折叠预览（单行摘要 + 角标提示） */
.conv-preview {
  margin-top: 2px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ha-ink-secondary);
  background: transparent;
  border: 0px dashed var(--ha-border);
  border-radius: var(--ha-radius-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 设计图：展开/收起交互按钮（“展开全文（810字）”） */
.conv-toggle {
  align-self: flex-start;
  margin-top: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-primary);
  cursor: pointer;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
  transition: color var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-toggle:hover { color: var(--ha-primary-hover); }
.conv-toggle .toggle-caret {
  display: inline-block;
  transition: transform var(--ha-duration-fast) var(--ha-ease-out);
}
.conv-toggle .toggle-caret.open { transform: rotate(90deg); }

/* 版本三响应式：窄屏收起为单列（沿用项目 1024/768 断点口径） */
@media (max-width: 1024px) {
  .page { grid-template-columns: 1fr; }
  .page .full-row { grid-column: auto; }
  .page .main-col { grid-column: auto; grid-row: auto; }
  .page .side-card { grid-column: auto; }
}
</style>