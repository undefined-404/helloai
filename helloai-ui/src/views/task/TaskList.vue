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
        :data="list"
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
          width="300"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              plain
              :disabled="row.status === 'DONE'"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
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
              v-if="row.status === 'PLANNING'"
              size="small"
              type="warning"
              @click="openPlanReview(row)"
            >
              审阅草案
            </el-button>
            <el-button
              v-if="row.status === 'DONE'"
              size="small"
              type="primary"
              plain
              :loading="row.finalReportStatus === 'GENERATING'"
              :disabled="row.finalReportStatus === 'GENERATING'"
              @click="openReport(row)"
            >
              {{ row.finalReportStatus === 'GENERATING' ? '生成中' : '报告' }}
            </el-button>
            <el-button
              size="small"
              type="warning"
              plain
              :disabled="row.status === 'DONE'"
              @click="handleRepublish(row)"
            >
              重新发布
            </el-button>
            <el-button
              v-if="row.status !== 'DONE' && row.status !== 'CANCELLED'"
              size="small"
              type="danger"
              :loading="stoppingId === row.id"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              @click="openDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="暂无任务"
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

    <!-- 描述详情弹窗 -->
    <el-dialog
      v-model="descVisible"
      :title="descTitle"
      width="600px"
      top="5vh"
      append-to-body
      :show-close="true"
      :close-on-click-modal="false"
    >
      <MarkdownView
        :content="descContent"
        class="desc-md"
      />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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

// taskId query 参数支持：筛选展示对应主任务（来源：子任务页"所属任务"、对话页"查看任务"等）
const taskIdQuery = ref(queryString(route.query, 'taskId') || '')
watch(
  () => route.query.taskId,
  () => { taskIdQuery.value = queryString(route.query, 'taskId') || ''; load() }
)

async function load() {
  loading.value = true
  try {
    const all = await taskApi.list()
    if (taskIdQuery.value) {
      list.value = all.filter((t: any) => String(t.id) === String(taskIdQuery.value))
    } else {
      list.value = all
    }
  } finally { loading.value = false }
}

// 标题点击 → 跳转子任务列表
function goSubTasks(row: any) { router.push('/sub-tasks?taskId=' + String(row.id)) }

// 描述点击 → 弹窗展示
const descVisible = ref(false)
const descTitle = ref('')
const descContent = ref('')
function openDesc(row: any) {
  descTitle.value = row.title || '任务描述'
  descContent.value = row.description || ''
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
/* 描述弹窗：标题仅保上方留白，去掉下方多余空行 */
.desc-md :deep(h2),
.desc-md :deep(h3),
.desc-md :deep(h4) { margin-bottom: 0; }
</style>
