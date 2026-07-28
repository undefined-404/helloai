<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>任务管理</span>
          <div class="header-actions">
            <el-button size="small" type="primary" @click="openCreate">新建</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status==='DONE'?'success':'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push('/sub-tasks?taskId='+row.id)">子任务</el-button>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import TaskFormDialog from './components/TaskFormDialog.vue'
import TaskDeleteDialog from './components/TaskDeleteDialog.vue'
import type { Task } from '@/types'

const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await taskApi.list() } finally { loading.value = false } }
onMounted(() => load())

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
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.header-actions { display: flex; gap: 8px; }
</style>
