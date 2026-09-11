<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>任务管理</span>
          <div class="header-actions">
            <!-- A1: 表单直建入口（含 V47 执行策略） -->
            <el-button
              size="small"
              type="primary"
              @click="openCreate"
            >
              新建任务
            </el-button>
            <el-button
              size="small"
              type="primary"
              @click="router.push('/requirement-chat')"
            >
              对话新建
            </el-button>
            <el-button
              size="small"
              @click="load"
            >
              刷新
            </el-button>
          </div>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="pagedList"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="标题"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              class="link-cell"
              :title="row.title"
              @click="goSubTasks(row)"
            >{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="描述"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              class="link-cell"
              @click="openDesc(row)"
            >{{ stripMarkdown(row.description) || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="110"
        >
          <template #default="{ row }">
            <!-- V41: 报告生成中覆盖主状态显示（任务本体仍是 DONE） -->
            <el-tag
              v-if="row.finalReportStatus === 'GENERATING'"
              type="primary"
              size="small"
            >
              报告生成中
            </el-tag>
            <el-tag
              v-else
              :type="TASK_STATUS_MAP[row.status as TaskStatus]?.type || 'info'"
              size="small"
            >
              {{ TASK_STATUS_MAP[row.status as TaskStatus]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="184"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-cell">
              <!-- 主操作：按状态驱动，每行至多一个内联主按钮 -->
              <el-button
                v-if="row.status === 'PENDING'"
                size="small"
                type="primary"
                plain
                :loading="planningId === row.id"
                @click="handlePlan(row)"
              >
                AI 拆解
              </el-button>
              <el-button
                v-else-if="row.status === 'PLANNING'"
                size="small"
                type="warning"
                @click="openPlanReview(row)"
              >
                审阅草案
              </el-button>
              <el-button
                v-else-if="row.status === 'DONE'"
                size="small"
                type="primary"
                plain
                :loading="row.finalReportStatus === 'GENERATING'"
                :disabled="row.finalReportStatus === 'GENERATING'"
                @click="openReport(row)"
              >
                {{ row.finalReportStatus === 'GENERATING' ? '生成中' : '报告' }}
              </el-button>
              <!-- 次要操作：统一收进更多下拉 -->
              <el-dropdown
                trigger="click"
                @command="(cmd: string) => handleCommand(cmd, row)"
              >
                <el-button
                  size="small"
                  :aria-label="`任务「${row.title}」更多操作`"
                >
                  更多
                  <el-icon class="el-icon--right">
                    <ArrowDown />
                  </el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      command="edit"
                      :disabled="row.status === 'DONE'"
                    >
                      编辑
                    </el-dropdown-item>
                    <el-dropdown-item command="events">
                      事件流
                    </el-dropdown-item>
                    <el-dropdown-item command="republish">
                      重新发布
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="row.status !== 'DONE' && row.status !== 'CANCELLED'"
                      command="stop"
                    >
                      停止
                    </el-dropdown-item>
                    <el-dropdown-item
                      command="delete"
                      divided
                    >
                      <span class="dropdown-danger">删除</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="暂无任务"
      />
      <el-pagination
        v-if="list.length > 0"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="list.length"
        :page-sizes="[10, 20, 50, 100]"
        :page-size="pageSize"
        :current-page="currentPage"
        style="margin-top:16px;text-align:center"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <TaskDeleteDialog
      v-model="deleteVisible"
      :task="deletingTask"
      @done="load"
    />
    <PlanReviewDialog
      v-model="planReviewVisible"
      :task="reviewingTask"
      @done="load"
    />
    <FinalReportDialog
      v-model="reportVisible"
      :task="reportTask"
      @status-change="onReportStatusChange"
    />
    <!-- A1: 新建/编辑任务（含 V47 执行策略与 SLA） -->
    <TaskFormDialog
      v-model="formVisible"
      :task="editingTask"
      @done="load"
    />

    <!-- 任务详情弹窗：任务描述 + 需求包（任务级验收标准含在内）；无需求包时整块隐藏（存量任务零变化） -->
    <el-dialog
      v-model="descVisible"
      :title="descTitle"
      width="600px"
      top="5vh"
      append-to-body
      :show-close="true"
      :close-on-click-modal="false"
    >
      <div class="detail-block">
        <div class="detail-caption">
          任务描述
        </div>
        <MarkdownView
          v-if="descContent"
          :content="descContent"
          class="desc-md"
        />
        <p
          v-else
          class="detail-empty"
        >
          —
        </p>
      </div>
      <div
        v-if="descPackage"
        class="detail-block"
      >
        <div class="detail-caption">
          需求包
        </div>
        <div
          v-if="descPackage.goal"
          class="detail-row"
        >
          <span class="detail-label">目标</span>
          <span class="detail-value">{{ descPackage.goal }}</span>
        </div>
        <div
          v-for="group in descPackageGroups"
          :key="group.label"
          class="detail-row"
        >
          <span class="detail-label">{{ group.label }}</span>
          <ul class="detail-list">
            <li
              v-for="(item, idx) in group.items"
              :key="idx"
            >
              {{ item }}
            </li>
          </ul>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import { stripMarkdown } from '@/utils/markdown'
import { queryString } from '@/utils/queryParam'
import MarkdownView from '@/components/MarkdownView.vue'
import TaskDeleteDialog from './components/TaskDeleteDialog.vue'
import PlanReviewDialog from './components/PlanReviewDialog.vue'
import FinalReportDialog from './components/FinalReportDialog.vue'
import TaskFormDialog from './components/TaskFormDialog.vue'
import { TASK_STATUS_MAP } from '@/types'
import type { Task, TaskStatus, LongId } from '@/types'

const route = useRoute()
const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
// 分页：前端按 pageSize 切片（任务量小，列表全量加载后再分页，避免每次翻页都重新拉接口）
const pageSize = ref(20)
const currentPage = ref(1)

// taskId query 参数支持：筛选展示对应主任务（来源：子任务页"所属任务"、对话页"查看任务"等）
const taskIdQuery = ref(queryString(route.query, 'taskId') || '')
watch(
  () => route.query.taskId,
  () => { taskIdQuery.value = queryString(route.query, 'taskId') || ''; currentPage.value = 1; load() }
)

// 客户端分页切片：filter 后总数较小，前端切片减少接口往返
const pagedList = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return list.value.slice(start, start + pageSize.value)
})

// 切页：仅切当前页码，重新从 list 切片
function onPageChange(p: number) {
  currentPage.value = p
}
// 切每页条数：回到第一页避免越界
function onSizeChange(s: number) {
  pageSize.value = s
  currentPage.value = 1
}

async function load() {
  loading.value = true
  try {
    // 不传 page：后端按旧契约返回全量数组，前端做切片分页（与 ReviewList 同构）
    // 这样维持对话新建跳转全量匹配 review query 的旧契约，同时给用户分页体验
    const all = await taskApi.list()
    if (taskIdQuery.value) {
      list.value = (all as any[]).filter((t: any) => String(t.id) === String(taskIdQuery.value))
    } else {
      list.value = all as any[]
    }
    // 防御边界：当前页越界（taskId 筛选后总数缩小）时回退到末页
    const totalPages = Math.max(1, Math.ceil(list.value.length / pageSize.value))
    if (currentPage.value > totalPages) currentPage.value = totalPages
  } catch {
    // 网络/后端异常：拦截器已弹通用错误，这里清空列表避免展示脏数据
    list.value = []
    ElMessage.error('任务列表加载失败，请稍后重试')
  } finally { loading.value = false }
}

// 标题点击 → 跳转子任务列表
function goSubTasks(row: any) { router.push('/sub-tasks?taskId=' + String(row.id)) }

// 事件流工作台：按任务维度回放 / 审计（深链自动带入 taskId 并查询）
function goEvents(row: any) { router.push('/event-stream?taskId=' + String(row.id)) }

// 描述点击 → 「任务详情」弹窗（任务描述 + 需求包；上下文字段缺失/为空时需求包整块隐藏）
const descVisible = ref(false)
const descTitle = ref('')
const descContent = ref('')
const descPackage = ref<DescRequirementPackage | null>(null)

interface DescRequirementPackage {
  goal: string
  scope: string[]
  outOfScope: string[]
  acceptanceCriteria: string[]
  assumptions: string[]
  openQuestions: string[]
}

/** 数组字段防御式归一：非数组 / 非字符串元素 / 空白项一律丢弃（与后端解析同口径）。 */
function stringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((v): v is string => typeof v === 'string' && v.trim() !== '')
    .map(v => v.trim())
}

/** context.requirementPackage 归一：非法形态或六字段全空回落 null（整块隐藏，存量任务零变化）。 */
function normalizeRequirementPackage(raw: unknown): DescRequirementPackage | null {
  if (!raw || typeof raw !== 'object') return null
  const src = raw as Record<string, unknown>
  const pkg: DescRequirementPackage = {
    goal: typeof src.goal === 'string' ? src.goal.trim() : '',
    scope: stringList(src.scope),
    outOfScope: stringList(src.outOfScope),
    acceptanceCriteria: stringList(src.acceptanceCriteria),
    assumptions: stringList(src.assumptions),
    openQuestions: stringList(src.openQuestions),
  }
  const empty = !pkg.goal
    && !pkg.scope.length && !pkg.outOfScope.length && !pkg.acceptanceCriteria.length
    && !pkg.assumptions.length && !pkg.openQuestions.length
  return empty ? null : pkg
}

// 需求包分组：只渲染有条目的组（字段顺序与后端 RequirementPackageParser.render 一致）
const descPackageGroups = computed((): { label: string; items: string[] }[] => {
  const pkg = descPackage.value
  if (!pkg) return []
  return [
    { label: '范围', items: pkg.scope },
    { label: '明确不做（outOfScope）', items: pkg.outOfScope },
    { label: '任务级验收标准（acceptanceCriteria）', items: pkg.acceptanceCriteria },
    { label: '关键假设（推断项）', items: pkg.assumptions },
    { label: '待确认事项（openQuestions）', items: pkg.openQuestions },
  ].filter(group => group.items.length > 0)
})

function openDesc(row: any) {
  descTitle.value = row.title ? `${row.title} · 任务详情` : '任务详情'
  descContent.value = row.description || ''
  descPackage.value = normalizeRequirementPackage(row.context?.requirementPackage)
  descVisible.value = true
}
// V29 对话新建跳转带 ?review=taskId 时，按状态分流：
// - PLANNING → 打开草案审阅（待用户确认/拒绝）
// - 其他状态 → 直接跳到主任务页（筛选展示该任务）
onMounted(async () => {
  await load()
  const review = route.query.review
  if (review) {
    const row = list.value.find(t => String(t.id) === String(review))
    if (row) {
      if (row.status === 'PLANNING') {
        openPlanReview(row)
      } else {
        router.push('/tasks?taskId=' + String(row.id))
      }
    }
  }
})

// ── 重新发布 ──
async function handleRepublish(row: Task) {
  try {
    await ElMessageBox.confirm(
      `将任务「${row.title}」重置为 PENDING 并重新通知全部 PLANNER，已有子任务不受影响。是否继续？`,
      '重新发布',
      { type: 'warning', confirmButtonText: '重新发布', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await taskApi.republish(String(row.id))
    ElMessage.success('已重新发布并通知 PLANNER')
    load()
  } catch { /* 拦截器已弹错 */ }
}

// ── V48 停止任务（软终止）：任务置 CANCELLED + 级联取消全部未完成子任务，数据保留可回溯 ──
const stoppingId = ref<string | null>(null)
async function handleStop(row: Task) {
  try {
    await ElMessageBox.confirm(
      `停止后任务「${row.title}」及全部未完成子任务将置为已取消（数据保留，可回溯）。是否继续？`,
      '停止任务',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' }
    )
  } catch { return }
  stoppingId.value = String(row.id)
  try {
    await taskApi.stopTask(String(row.id))
    ElMessage.success(`已停止任务「${row.title}」`)
    load()
  } catch { /* 拦截器已弹错 */ }
  finally { stoppingId.value = null }
}

// ── A1 新建/编辑任务（task=null 新建，否则编辑；编辑态回显 SLA/执行策略/技能）──
const formVisible = ref(false)
const editingTask = ref<Task | null>(null)
function openCreate() { editingTask.value = null; formVisible.value = true }
function openEdit(row: Task) { editingTask.value = row; formVisible.value = true }

// ── 更多下拉：次要操作统一分派（编辑/事件流/重新发布/停止/删除） ──
function handleCommand(command: string, row: Task) {
  if (command === 'edit') openEdit(row)
  else if (command === 'events') goEvents(row)
  else if (command === 'republish') handleRepublish(row)
  else if (command === 'stop') handleStop(row)
  else if (command === 'delete') openDelete(row)
}

// ── 删除 ──
const deleteVisible = ref(false)
const deletingTask = ref<Task | null>(null)
function openDelete(row: Task) { deletingTask.value = row; deleteVisible.value = true }

// ── V32 最终整合报告（仅 DONE 任务展示入口，生成/重生成/交付物下载在弹窗内）──
const reportVisible = ref(false)
const reportTask = ref<Task | null>(null)
function openReport(row: Task) { reportTask.value = row; reportVisible.value = true }
// FinalReportDialog 把报告生成状态广播出来，我们只 patch list 里对应行的 finalReportStatus，
// 不重拉整个列表（避免打断用户当前操作 / 滚动位置）。
function onReportStatusChange(status: string) {
  if (!reportTask.value) return
  const target = list.value.find(t => String(t.id) === String(reportTask.value!.id))
  if (target) (target as any).finalReportStatus = status
}

// ── V26 AI 拆解 + 草案审阅 ──
const planningId = ref<LongId | null>(null)
const planReviewVisible = ref(false)
const reviewingTask = ref<Task | null>(null)
function openPlanReview(row: Task) { reviewingTask.value = row; planReviewVisible.value = true }

async function handlePlan(row: Task) {
  try {
    await ElMessageBox.confirm(
      `将对任务「${row.title}」发起 AI 拆解，提交后在后台生成草案（通常需要一段时间：几十秒到几分钟不等，由任务复杂程度而定），完成后即可审阅。`,
      'AI 拆解',
      { type: 'info', confirmButtonText: '开始拆解', cancelButtonText: '取消' }
    )
  } catch { return }
  planningId.value = row.id
  try {
    // 拆解异步化：plan 提交即返回（任务转 PLANNING），草案由后台生成，
    // 直接进入审阅弹窗轮询等待（不再原地等 LLM 结果，避免前端超时错乱）
    await taskApi.plan(row.id)
    ElMessage.success('拆解已提交，草案生成中')
    load()
    openPlanReview(row)
  } catch { /* 拦截器已弹错（已存在子任务/并发拆解中/排队已满等由后端 BizException 统一提示） */ }
  finally { planningId.value = null }
}
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; gap: 8px; }
.link-cell { color: var(--el-color-primary); cursor: pointer; }
/* 操作列：主按钮 + 更多下拉横排，不换行不压缩（避免单元格裁切「更多」） */
.action-cell { display: flex; align-items: center; gap: 6px; flex-wrap: nowrap; }
.action-cell .el-button { flex: none; }
/* 删除项：危险语义色（dropdown-item 无 danger 变体，用子元素着色） */
.dropdown-danger { color: var(--ha-danger); }
/* 描述弹窗：标题仅保上方留白，去掉下方多余空行 */
.desc-md :deep(h2),
.desc-md :deep(h3),
.desc-md :deep(h4) { margin-bottom: 0; }
/* 任务详情弹窗：描述 / 需求包分区，标签定宽 + 条目列表紧凑行高 */
.detail-block + .detail-block { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.detail-caption { margin-bottom: 8px; font-weight: 600; color: var(--el-text-color-primary); }
.detail-row { display: flex; gap: 8px; margin-bottom: 6px; }
.detail-label { flex: none; min-width: 72px; color: var(--el-text-color-secondary); }
.detail-value { flex: 1; word-break: break-word; }
.detail-list { flex: 1; margin: 0; padding-left: 16px; }
.detail-list li { line-height: 1.6; }
.detail-empty { margin: 0; color: var(--el-text-color-placeholder); }
</style>
