<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>子任务列表</span>
          <div class="header-actions">
            <!-- 按主任务过滤时提供列表/依赖图双视图切换 -->
            <el-radio-group
              v-if="taskId"
              v-model="viewMode"
              size="small"
              style="margin-right:8px"
            >
              <el-radio-button value="list">
                列表
              </el-radio-button>
              <el-radio-button value="dag">
                依赖图
              </el-radio-button>
              <el-radio-button value="iter">
                执行迭代
              </el-radio-button>
            </el-radio-group>
            <el-select
              v-model="statusFilter"
              placeholder="状态筛选"
              clearable
              style="width:140px;margin-right:8px"
              @change="load(1)"
            >
              <el-option
                v-for="[k,v] in Object.entries(SUB_TASK_STATUS_MAP)"
                :key="k"
                :label="v.label"
                :value="k"
              />
            </el-select>
            <el-button
              size="small"
              type="primary"
              style="margin-right:8px"
              @click="dispatchVisible = true"
            >
              快速派发
            </el-button>
            <el-button
              size="small"
              type="primary"
              @click="load(currentPage)"
            >
              刷新
            </el-button>
          </div>
        </div>
      </template>
      <!-- 主任务信息条：从任务管理页携带 taskId 跳转时展示归属主任务，并可清除筛选 -->
      <el-alert
        v-if="taskId"
        type="info"
        :closable="false"
        class="parent-task-bar"
      >
        <template #title>
          <span class="parent-task-title">
            当前主任务：<span
              class="link-cell"
              :title="parentTask?.title || ('跳转到主任务 ' + taskId)"
              @click="goParentTaskById(taskId)"
            >{{ parentTask?.title || taskId }}</span>
            <el-tag
              v-if="parentTask"
              :type="parentTask.status === 'DONE' ? 'success' : 'warning'"
              size="small"
              style="margin-left:8px"
            >
              {{ parentTask.status }}
            </el-tag>
          </span>
          <el-button
            link
            type="primary"
            @click="clearTaskFilter"
          >
            查看全部子任务
          </el-button>
        </template>
      </el-alert>
      <!-- 依赖图视图：拓扑分层流水线（同批可并行），点击节点跳详情 -->
      <SubTaskDagView
        v-if="taskId && viewMode === 'dag'"
        v-loading="fullListLoading"
        :sub-tasks="fullList"
        @node-click="goDetail"
      />
      <TaskIterationView
        v-if="taskId && viewMode === 'iter'"
        :items="iterations"
        :loading="iterLoading"
        :backfilling="backfilling"
        @backfill="handleBackfillIterations"
      />
      <template v-else>
        <el-table
          v-loading="loading"
          :data="displayList"
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
              <!-- 拓扑序号小徽标：与依赖列/依赖图 #序号 同口径，仅按主任务过滤时展示 -->
              <span
                v-if="taskId && seqMap.get(String(row.id))"
                class="seq-badge"
              >#{{ seqMap.get(String(row.id)) }}</span>
              <span
                class="link-cell"
                :title="row.title"
                @click="router.push('/sub-tasks/' + row.id)"
              >{{ row.title }}</span>
            </template>
          </el-table-column>
          <!-- 未按主任务过滤时展示归属任务，避免与顶部信息条重复 -->
          <el-table-column
            v-if="!taskId"
            label="所属任务"
            min-width="160"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span
                class="link-cell"
                @click="goParentTask(row)"
              >{{ row.taskTitle || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
          >
            <template #default="{ row }">
              <el-tag
                :type="getSubTaskStatusMeta(row.status)?.type || 'info'"
                size="small"
              >
                {{ getSubTaskStatusMeta(row.status)?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- 按主任务过滤时展示前置依赖（可点击跳依赖项详情），序号与依赖图/草案审阅一致 -->
          <el-table-column
            v-if="taskId"
            label="依赖"
            min-width="120"
          >
            <template #default="{ row }">
              <template v-if="depItems(row).length">
                <el-tag
                  v-for="dep in depItems(row)"
                  :key="dep.id"
                  size="small"
                  class="dep-tag"
                  :title="dep.title"
                  @click="goDetail(dep.id)"
                >
                  #{{ dep.seq }}
                </el-tag>
              </template>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column
            label="评分"
            width="80"
          >
            <template #default="{ row }">
              <el-tag
                v-if="row.scoreGrade"
                :type="SCORE_GRADE_MAP[row.scoreGrade]?.type || 'info'"
                size="small"
              >
                {{ SCORE_GRADE_MAP[row.scoreGrade]?.label || row.scoreGrade }}
              </el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column
            label="负责人"
            min-width="160"
          >
            <template #default="{ row }">
              <span
                class="agent-cell"
                :title="row.assignedAgentName || ''"
              >{{ row.assignedAgentName || '-' }}</span>
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
            :width="ACTION.THREE"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <el-button
                  size="small"
                  @click="router.push('/sub-tasks/' + row.id)"
                >
                  详情
                </el-button>
                <el-button
                  v-if="row.status==='PENDING'"
                  size="small"
                  type="primary"
                  @click="handleClaim(row)"
                >
                  认领
                </el-button>
                <el-button
                  v-if="row.status==='IN_PROGRESS'"
                  size="small"
                  type="warning"
                  @click="handlePause(row)"
                >
                  暂停
                </el-button>
                <el-button
                  v-if="row.status==='PAUSED'"
                  size="small"
                  type="success"
                  @click="handleResume(row)"
                >
                  恢复
                </el-button>
                <!-- V25 死信人工兜底：重新指派给指定 Agent（DEAD_LETTER → ASSIGNED） -->
                <el-button
                  v-if="row.status==='DEAD_LETTER'"
                  size="small"
                  type="danger"
                  @click="handleRedispatch(row)"
                >
                  重新指派
                </el-button>
                <!-- BLOCKED 阻塞子任务：重新调度（reset → PENDING 后交调度链） -->
                <el-button
                  v-if="row.status==='BLOCKED'"
                  size="small"
                  type="warning"
                  @click="handleReassign(row)"
                >
                  重新调度
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!list.length && !loading"
          description="暂无子任务"
        />
        <el-pagination
          v-if="total > 0"
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          style="margin-top:16px;text-align:center"
          @current-change="loadPage"
        />
      </template>

      <!-- 认领弹窗 -->
      <el-dialog
        v-model="claimDialog.visible"
        title="认领子任务"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ claimDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="认领 Agent">
            <AgentSelect
              v-model="claimDialog.agentId"
              placeholder="选择认领的 Agent"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="claimDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="claimDialog.loading"
            :disabled="!claimDialog.agentId"
            @click="doClaim"
          >
            确认认领
          </el-button>
        </template>
      </el-dialog>

      <!-- 死信重新指派弹窗：复用 AgentSelect 选目标 Agent -->
      <el-dialog
        v-model="redispatchDialog.visible"
        title="死信重新指派"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ redispatchDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="目标 Agent">
            <AgentSelect
              v-model="redispatchDialog.agentId"
              placeholder="选择重新指派的 Agent"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="redispatchDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="redispatchDialog.loading"
            :disabled="!redispatchDialog.agentId"
            @click="doRedispatch"
          >
            确认指派
          </el-button>
        </template>
      </el-dialog>

      <!-- BLOCKED 重新调度弹窗：选目标 Agent，后端 reset→PENDING 后重新进入调度链 -->
      <el-dialog
        v-model="reassignDialog.visible"
        title="重新调度阻塞子任务"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ reassignDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="目标 Agent">
            <AgentSelect
              v-model="reassignDialog.agentId"
              placeholder="选择重新调度的 Agent"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reassignDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="reassignDialog.loading"
            :disabled="!reassignDialog.agentId"
            @click="doReassign"
          >
            确认重新调度
          </el-button>
        </template>
      </el-dialog>

      <!-- M4.5: 快速派发对话框 -->
      <QuickDispatchDialog
        v-model="dispatchVisible"
        @done="load(1)"
      />
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
import SubTaskDagView from '@/components/SubTaskDagView.vue'
import TaskIterationView from '@/components/TaskIterationView.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { ACTION, fmtTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import { queryString } from '@/utils/queryParam'
import { useAutoRefresh } from '@/composables/useAutoRefresh'
import type { Task, SubTask, SubTaskStatus, TaskIteration } from '@/types'

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
const taskId = computed(() => queryString(route.query, 'taskId') || '')
// 路由 query 中的状态筛选（死信池菜单跳 /sub-tasks?status=DEAD_LETTER 复用本页）
const statusQuery = computed(() => queryString(route.query, 'status') || '')
const parentTask = ref<Task | null>(null)
// 列表/依赖图/执行迭代三视图（仅按主任务过滤时可切换）
const viewMode = ref<'list' | 'dag' | 'iter'>('list')
// 全量子任务（依赖图渲染 + 依赖列序号映射用；分页列表里依赖项可能不在当前页）
const fullList = ref<SubTask[]>([])
const fullListLoading = ref(false)
// V42 迭代记录
const iterations = ref<TaskIteration[]>([])
const iterLoading = ref(false)
const backfilling = ref(false)

async function loadFullList() {
  if (!taskId.value) { fullList.value = []; return }
  fullListLoading.value = true
  try {
    fullList.value = await subTaskApi.listAllByTask(taskId.value)
  } catch {
    // 全量拉取失败不阻断分页列表，依赖列/依赖图降级为空
    fullList.value = []
  } finally { fullListLoading.value = false }
}

async function loadIterations() {
  if (!taskId.value) { iterations.value = []; return }
  iterLoading.value = true
  try {
    iterations.value = await taskApi.findTaskIterationsByTaskId(taskId.value)
  } catch {
    iterations.value = []
  } finally { iterLoading.value = false }
}

async function handleBackfillIterations() {
  backfilling.value = true
  try {
    const res = await taskApi.backfillTaskIterations()
    ElMessage.success('回填完成，共 ' + res.backfilledCount + ' 个任务')
    await loadIterations()
  } catch {
    ElMessage.error('回填失败')
  } finally { backfilling.value = false }
}

// 拓扑正序全局序号映射（与依赖图、草案审阅弹窗的 #序号 口径一致）
const seqMap = computed(() => {
  const map = new Map<string, number>()
  orderByDependency(fullList.value).forEach((s, i) => map.set(String(s.id), i + 1))
  return map
})

// 展示列表：按主任务过滤时按拓扑序号 #1→#n 正序排列（seqMap 未就绪时保持原序），全局列表维持后端顺序
const displayList = computed(() => {
  if (!taskId.value) return list.value
  const sm = seqMap.value
  return [...list.value].sort(
    (a, b) => (sm.get(String(a.id)) ?? Infinity) - (sm.get(String(b.id)) ?? Infinity)
  )
})

// 行的前置依赖展示项：#序号 + 标题（悬浮提示），点击跳依赖项详情
function depItems(row: SubTask): { id: string; seq: number; title: string }[] {
  return (row.dependsOn || []).map(String)
    .filter(d => seqMap.value.has(d))
    .map(d => ({
      id: d,
      seq: seqMap.value.get(d)!,
      title: fullList.value.find(s => String(s.id) === d)?.title || ''
    }))
    .sort((a, b) => a.seq - b.seq)
}

function goDetail(id: string) { router.push('/sub-tasks/' + id) }

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
    // 按主任务过滤时同步刷新全量列表（依赖列序号/依赖图状态色保持最新）
    if (taskId.value) { loadFullList(); loadIterations() }
  } finally { loading.value = false }
}
function loadPage(page: number) { load(page) }

function clearTaskFilter() { router.replace('/sub-tasks') }

// 顶部信息条主任务名称点击 → 跳转任务管理页并筛选该主任务
function goParentTaskById(id: string) {
  router.push('/tasks?taskId=' + id)
}

// 所属任务列点击 → 跳转任务管理页并筛选该主任务
function goParentTask(row: SubTask) {
  if (row.taskId) router.push('/tasks?taskId=' + row.taskId)
}

// 同页面内 taskId 变化（如清除筛选 / 从不同主任务进入）时联动刷新
watch(taskId, () => {
  if (!taskId.value) { viewMode.value = 'list'; fullList.value = []; iterations.value = [] }
  loadParentTask()
  load()
})
// 同页面内 status query 变化（子任务菜单 ↔ 死信池菜单切换）时同步筛选并刷新
watch(statusQuery, (v) => {
  // 防御：仅在合法 SubTaskStatus 范围内赋值，避免后端不识别枚举值
  const validStatuses: SubTaskStatus[] = [
    'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW',
    'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER',
    'PENDING_PLAN_REVIEW'
  ]
  statusFilter.value = (validStatuses as string[]).includes(v) ? (v as SubTaskStatus) : ''
  load(1)
})

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

// V25 死信人工兜底：重新指派（后端清熔断计数并转 ASSIGNED）
const redispatchDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

function handleRedispatch(row: SubTask) {
  redispatchDialog.row = row
  redispatchDialog.agentId = null
  redispatchDialog.visible = true
}

async function doRedispatch() {
  if (!redispatchDialog.row || !redispatchDialog.agentId) return
  redispatchDialog.loading = true
  try {
    await subTaskApi.redispatchDeadLetter(redispatchDialog.row.id, redispatchDialog.agentId)
    ElMessage.success('重新指派成功')
    redispatchDialog.visible = false
    load()
  } finally { redispatchDialog.loading = false }
}

// BLOCKED 阻塞子任务重新调度（后端 reset→PENDING 后交弹性调度链，受熔断计数管控）
const reassignDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

function handleReassign(row: SubTask) {
  reassignDialog.row = row
  reassignDialog.agentId = null
  reassignDialog.visible = true
}

async function doReassign() {
  if (!reassignDialog.row || !reassignDialog.agentId) return
  reassignDialog.loading = true
  try {
    await subTaskApi.reassign(reassignDialog.row.id, reassignDialog.agentId)
    ElMessage.success('已重新调度，子任务重新进入分发链')
    reassignDialog.visible = false
    load()
  } finally { reassignDialog.loading = false }
}

function getSubTaskStatusMeta(status: SubTask['status']) { return SUB_TASK_STATUS_MAP[status] }

// step9c：按主任务过滤时 10s 自动轮询刷新依赖图（限定条件由 useAutoRefresh 内部判断）。
// 退出页面 / 主任务切换时由 composable 自动停 timer，无需手写 onBeforeUnmount。
const isDagMode = computed(() => viewMode.value === 'dag')
const allDone = computed(
  () => fullList.value.length > 0 && fullList.value.every((s) => s.status === 'DONE')
)
useAutoRefresh(() => { load(currentPage.value) }, {
  intervalMs: 10_000,
  shouldRun: computed(() => isDagMode.value && !!taskId.value && !allDone.value),
  key: computed(() => taskId.value || ''),
  autoStart: true
})

onMounted(() => {
  // 带筛选跳转（死信池菜单）：先用 query.status 初始化筛选再加载
  const initStatus = statusQuery.value
  const validStatuses: SubTaskStatus[] = [
    'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW',
    'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER',
    'PENDING_PLAN_REVIEW'
  ]
  statusFilter.value = (validStatuses as string[]).includes(initStatus) ? (initStatus as SubTaskStatus) : ''
  loadParentTask()
  load()
})
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; align-items: center; }
.parent-task-bar { margin-bottom: 12px; }
.parent-task-bar :deep(.el-alert__title) { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.parent-task-title { display: flex; align-items: center; }
.link-cell { color: var(--el-color-primary); cursor: pointer; }
.dep-tag { cursor: pointer; margin-right: 4px; }
/* 标题前拓扑序号小徽标：参考电商 new 角标样式（小号胶囊、实底白字） */
.seq-badge {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 8px;
  vertical-align: 1px;
}
/* 负责人 Agent 名：长名在窄列下不能换行，单行截断 + 原生 title tooltip 提示全名 */
.agent-cell {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ha-ink);
}
/* 表格 cell 默认 vertical-align: middle，显式居中作为升级防御，
   保证单行 Agent 名与单行时间戳视觉中线对齐 */
:deep(.el-table td.el-table__cell > .cell) {
  display: flex;
  align-items: center;
}
</style>
