<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>子任务列表</span>
          <div class="header-actions">
            <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width:140px;margin-right:8px" @change="load">
              <el-option v-for="[k,v] in Object.entries(SUB_TASK_STATUS_MAP)" :key="k" :label="v.label" :value="k" />
            </el-select>
            <el-button size="small" type="primary" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
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
        :total="total" :page-size="20" @current-change="loadPage" style="margin-top:16px;text-align:center"
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
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import AgentSelect from '@/components/AgentSelect.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import { ACTION } from '@/utils/tableConfig'
import { fmtTime } from '@/utils/tableConfig'
import type { SubTask, SubTaskStatus } from '@/types'

const router = useRouter()
const list = ref<SubTask[]>([])
const total = ref(0)
const loading = ref(false)
const statusFilter = ref<SubTaskStatus | ''>('')

async function load(page = 1) {
  loading.value = true
  try {
    const params: any = { page, size: 20 }
    if (statusFilter.value) params.status = statusFilter.value
    list.value = await subTaskApi.list(params)
    total.value = list.value.length
  } finally { loading.value = false }
}
function loadPage(page: number) { load(page) }

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
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; align-items: center; }
</style>
