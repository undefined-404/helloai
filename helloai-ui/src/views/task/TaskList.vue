<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>任务管理</span>
          <div class="header-actions">
            <el-button size="small" type="primary" @click="openCreate">新建</el-button>
            <el-button size="small" @click="router.push('/requirement-chat')">对话新建</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="TASK_STATUS_MAP[row.status as TaskStatus]?.type || 'info'" size="small">
              {{ TASK_STATUS_MAP[row.status as TaskStatus]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="390" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push('/sub-tasks?taskId='+row.id)">子任务</el-button>
            <el-button
              v-if="row.status === 'PENDING'"
              size="small"
              type="primary"
              plain
              :loading="planningId === row.id"
              @click="handlePlan(row)"
            >AI 拆解</el-button>
            <el-button
              v-if="row.status === 'PLANNING'"
              size="small"
              type="warning"
              @click="openPlanReview(row)"
            >审阅草案</el-button>
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              size="small"
              type="warning"
              plain
              :disabled="row.status === 'DONE'"
              @click="handleRepublish(row)"
            >重新发布</el-button>
            <el-button size="small" type="danger" plain @click="openDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无任务" />
    </el-card>

    <TaskFormDialog v-model="formVisible" :task="editingTask" @done="load" />
    <TaskDeleteDialog v-model="deleteVisible" :task="deletingTask" @done="load" />
    <PlanReviewDialog v-model="planReviewVisible" :task="reviewingTask" @done="load" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import TaskFormDialog from './components/TaskFormDialog.vue'
import TaskDeleteDialog from './components/TaskDeleteDialog.vue'
import PlanReviewDialog from './components/PlanReviewDialog.vue'
import { TASK_STATUS_MAP } from '@/types'
import type { Task, TaskStatus, LongId } from '@/types'

const route = useRoute()
const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await taskApi.list() } finally { loading.value = false } }
// V29 对话新建跳转带 ?review=taskId 时，加载后自动打开草案审阅（找不到静默忽略）
onMounted(async () => {
  await load()
  const review = route.query.review
  if (review) {
    const row = list.value.find(t => String(t.id) === String(review))
    if (row) openPlanReview(row)
  }
})

// ── 新建/编辑 ──
const formVisible = ref(false)
const editingTask = ref<Task | null>(null)
function openCreate() { editingTask.value = null; formVisible.value = true }
function openEdit(row: Task) { editingTask.value = row; formVisible.value = true }

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
    await taskApi.republish(row.id)
    ElMessage.success('已重新发布并通知 PLANNER')
    load()
  } catch { /* 拦截器已弹错 */ }
}

// ── 删除 ──
const deleteVisible = ref(false)
const deletingTask = ref<Task | null>(null)
function openDelete(row: Task) { deletingTask.value = row; deleteVisible.value = true }

// ── V26 AI 拆解 + 草案审阅 ──
const planningId = ref<LongId | null>(null)
const planReviewVisible = ref(false)
const reviewingTask = ref<Task | null>(null)
function openPlanReview(row: Task) { reviewingTask.value = row; planReviewVisible.value = true }

async function handlePlan(row: Task) {
  try {
    await ElMessageBox.confirm(
      `将调用 LLM 对任务「${row.title}」做 AI 拆解，约需几十秒，完成后生成草案供审阅。是否继续？`,
      'AI 拆解',
      { type: 'info', confirmButtonText: '开始拆解', cancelButtonText: '取消' }
    )
  } catch { return }
  planningId.value = row.id
  try {
    await taskApi.plan(row.id)
    ElMessage.success('拆解完成，请审阅草案')
    await load()
    // 拆解成功后直接进入草案审阅
    openPlanReview(row)
  } catch { /* 拦截器已弹错（已存在子任务/并发拆解中等由后端 BizException 统一提示） */ }
  finally { planningId.value = null }
}
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; gap: 8px; }
</style>
