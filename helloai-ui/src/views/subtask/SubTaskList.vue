<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>子任务列表</span>
          <div class="header-actions">
            <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width:140px;margin-right:8px" @change="load(1)">
              <el-option v-for="[k,v] in Object.entries(SUB_TASK_STATUS_MAP)" :key="k" :label="v.label" :value="k" />
            </el-select>
            <el-button size="small" type="primary" style="margin-right:8px" @click="dispatchVisible = true">快速派发</el-button>
            <el-button size="small" type="primary" @click="load(currentPage)">刷新</el-button>
          </div>
        </div>
      </template>
      <!-- 主任务信息条：从任务管理页携带 taskId 跳转时展示归属主任务，并可清除筛选 -->
      <el-alert v-if="taskId" type="info" :closable="false" class="parent-task-bar">
        <template #title>
          <span class="parent-task-title">
            当前主任务：{{ parentTask?.title || taskId }}
            <el-tag v-if="parentTask" :type="parentTask.status === 'DONE' ? 'success' : 'warning'" size="small" style="margin-left:8px">
              {{ parentTask.status }}
            </el-tag>
          </span>
          <el-button link type="primary" @click="clearTaskFilter">查看全部子任务</el-button>
        </template>
      </el-alert>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <!-- 未按主任务过滤时展示归属任务，避免与顶部信息条重复 -->
        <el-table-column v-if="!taskId" label="所属任务" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.taskTitle || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getSubTaskStatusMeta(row.status)?.type || 'info'" size="small">
              {{ getSubTaskStatusMeta(row.status)?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.scoreGrade" :type="SCORE_GRADE_MAP[row.scoreGrade]?.type || 'info'" size="small">
              {{ SCORE_GRADE_MAP[row.scoreGrade]?.label || row.scoreGrade }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="负责人" min-width="100">
          <template #default="{ row }">{{ row.assignedAgentName || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="ACTION.THREE" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" @click="router.push('/sub-tasks/' + row.id)">详情</el-button>
              <el-button v-if="row.status==='PENDING'" size="small" type="primary" @click="handleClaim(row)">认领</el-button>
              <el-button v-if="row.status==='IN_PROGRESS'" size="small" type="warning" @click="handlePause(row)">暂停</el-button>
              <el-button v-if="row.status==='PAUSED'" size="small" type="success" @click="handleResume(row)">恢复</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无子任务" />
      <el-pagination
        v-if="total > 0" background layout="prev, pager, next"
        :total="total" :page-size="pageSize" :current-page="currentPage"
        @current-change="loadPage" style="margin-top:16px;text-align:center"
      />

      <!-- 认领弹窗 -->
      <el-dialog v-model="claimDialog.visible" title="认领子任务" width="420px" top="5vh" append-to-body>
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ claimDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="认领 Agent">
            <AgentSelect v-model="claimDialog.agentId" placeholder="选择认领的 Agent" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="claimDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="claimDialog.loading" :disabled="!claimDialog.agentId" @click="doClaim">确认认领</el-button>
        </template>
      </el-dialog>

      <!-- M4.5: 快速派发对话框 -->
      <QuickDispatchDialog v-model="dispatchVisible" @done="load(1)" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import { taskApi } from '@/api/task'
import AgentSelect from '@/components/AgentSelect.vue'
import QuickDispatchDialog from '@/components/QuickDispatchDialog.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { ACTION } from '@/utils/tableConfig'
import { fmtTime } from '@/utils/tableConfig'
import type { Task, SubTask, SubTaskStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const list = ref<SubTask[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const loading = ref(false)
const statusFilter = ref<SubTaskStatus | ''>('')
const dispatchVisible = ref(false)
// 路由 query 中的主任务 ID（LongId 保持 string，不转 Number 防精度丢）
const taskId = computed(() => (route.query.taskId ? String(route.query.taskId) : ''))
const parentTask = ref<Task | null>(null)

async function loadParentTask() {
  if (!taskId.value) { parentTask.value = null; return }
  try {
    parentTask.value = await taskApi.getById(taskId.value)
  } catch {
    // 主任务查询失败不阻断子任务列表，信息条降级显示 taskId
    parentTask.value = null
  }
}

async function load(page = 1) {
  loading.value = true
  try {
    const params: { taskId?: string; status?: string; page: number; pageSize: number } = { page, pageSize }
    if (taskId.value) params.taskId = taskId.value
    if (statusFilter.value) params.status = statusFilter.value
    // 后端真分页：传 page 返回 PageResult（list/total）
    const res = await subTaskApi.list(params)
    list.value = res.list
    total.value = res.total
    currentPage.value = page
  } finally { loading.value = false }
}
function loadPage(page: number) { load(page) }

function clearTaskFilter() { router.replace('/sub-tasks') }

// 同页面内 taskId 变化（如清除筛选 / 从不同主任务进入）时联动刷新
watch(taskId, () => { loadParentTask(); load() })

const claimDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

async function handleClaim(row: SubTask) {
  claimDialog.row = row
  claimDialog.agentId = null
  claimDialog.visible = true
}

async function doClaim() {
  if (!claimDialog.row || !claimDialog.agentId) return
  claimDialog.loading = true
  try {
    await subTaskApi.claim(claimDialog.row.id, claimDialog.agentId)
    ElMessage.success('认领成功')
    claimDialog.visible = false
    load()
  } finally { claimDialog.loading = false }
}

async function handlePause(row: SubTask) {
  try {
    await ElMessageBox.confirm(`确定暂停子任务「${row.title}」？`, '确认暂停', { type: 'warning' })
    await subTaskApi.pause(row.id)
    ElMessage.success('已暂停')
    load()
  } catch {}
}

async function handleResume(row: SubTask) {
  try {
    await subTaskApi.resume(row.id)
    ElMessage.success('已恢复')
    load()
  } catch {}
}

function getSubTaskStatusMeta(status: SubTask['status']) { return SUB_TASK_STATUS_MAP[status] }
onMounted(() => { loadParentTask(); load() })
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; align-items: center; }
.parent-task-bar { margin-bottom: 12px; }
.parent-task-bar :deep(.el-alert__title) { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.parent-task-title { display: flex; align-items: center; }
</style>
