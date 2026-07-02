<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span><el-icon><Document /></el-icon> 子任务列表</span>
        <div>
          <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width:140px;margin-right:8px" @change="load">
            <el-option v-for="[k,v] in Object.entries(SUB_TASK_STATUS_MAP)" :key="k" :label="v.label" :value="k" />
          </el-select>
          <el-button size="small" type="primary" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <el-table :data="list" border stripe v-loading="loading" style="width:100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
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
      <el-table-column prop="assignedAgent" label="负责人" width="100" />
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="router.push('/sub-tasks/' + row.id)">详情</el-button>
          <el-button v-if="row.status==='PENDING'" size="small" type="primary" @click="handleClaim(row)">
            认领
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!list.length && !loading" description="暂无子任务" />
    <el-pagination
      v-if="total > 0" background layout="prev, pager, next"
      :total="total" :page-size="20" @current-change="loadPage" style="margin-top:16px;text-align:center"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import type { SubTask, SubTaskStatus } from '@/types'

const router = useRouter()
const list = ref<SubTask[]>([])
const total = ref(0)
const loading = ref(false)
const statusFilter = ref<SubTaskStatus | ''>('')

async function load(page = 1) {
  loading.value = true
  try {
    const params: { page: number; size: number; status?: SubTaskStatus } = { page, size: 20 }
    if (statusFilter.value) params.status = statusFilter.value
    list.value = await subTaskApi.list(params)
    total.value = list.value.length
  } finally { loading.value = false }
}
function loadPage(page: number) { load(page) }

async function handleClaim(row: SubTask) {
  try {
    await ElMessageBox.prompt('输入 Agent ID', '认领子任务', { inputValue: '' })
    await subTaskApi.claim(row.id, 1)
    ElMessage.success('认领成功')
    load()
  } catch {}
}

function getSubTaskStatusMeta(status: SubTask['status']) {
  return SUB_TASK_STATUS_MAP[status]
}

onMounted(() => load())
</script>
