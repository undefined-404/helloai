<template>
  <el-dialog
    v-model="visible"
    title="拆解草案审阅"
    width="960px"
    top="6vh"
    append-to-body
    @open="loadDrafts"
    @close="$emit('close')"
  >
    <div v-loading="loadingDrafts">
      <p style="font-size:14px;color:var(--ha-ink);margin:0 0 12px">
        任务「{{ task?.title }}」的 AI 拆解草案，确认后草案转正为待分配子任务并按配置自动分发。
      </p>
      <el-table v-if="drafts.length" :data="drafts" border stripe size="small" style="width:100%">
        <el-table-column type="index" label="#" width="48" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
        <el-table-column prop="deliverable" label="交付物" min-width="140" show-overflow-tooltip />
        <el-table-column prop="acceptance" label="验收标准" min-width="140" show-overflow-tooltip />
        <el-table-column label="优先级" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.priority" :type="priorityTagType(row.priority)" size="small">{{ row.priority }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="依赖" width="110">
          <template #default="{ row }">{{ fmtDepends(row.dependsOn) }}</template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="!loadingDrafts"
        description="未找到待审阅草案（可能为异常残留），建议「拒绝重拆」使任务回到待规划状态"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" plain :loading="rejecting" @click="handleReject">拒绝重拆</el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="!drafts.length"
        @click="handleConfirm"
      >确认并分发</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import type { Task, SubTask, LongId } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const loadingDrafts = ref(false)
const confirming = ref(false)
const rejecting = ref(false)
const drafts = ref<SubTask[]>([])

async function loadDrafts() {
  if (!props.task) return
  loadingDrafts.value = true
  drafts.value = []
  try { drafts.value = await taskApi.planDrafts(String(props.task.id)) }
  catch { /* 拦截器已弹错 */ }
  finally { loadingDrafts.value = false }
}

function priorityTagType(priority: string): 'danger' | 'warning' | 'info' {
  const p = priority.toUpperCase()
  if (p === 'HIGH' || p === 'P0') return 'danger'
  if (p === 'MEDIUM' || p === 'P1') return 'warning'
  return 'info'
}

// dependsOn 存草案 id，映射为表内序号展示（如「依赖 #1,#2」）
function fmtDepends(dependsOn?: LongId[] | null): string {
  if (!dependsOn?.length) return '-'
  const seqs = dependsOn
    .map(id => drafts.value.findIndex(d => String(d.id) === String(id)) + 1)
    .filter(seq => seq > 0)
  return seqs.length ? `依赖 #${seqs.join(',')}` : '-'
}

async function handleConfirm() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      `将 ${drafts.value.length} 条草案转正为待分配子任务，任务进入「进行中」并按配置自动分配执行 Agent。是否继续？`,
      '确认拆解方案',
      { type: 'warning', confirmButtonText: '确认并分发', cancelButtonText: '取消' }
    )
  } catch { return }
  confirming.value = true
  try {
    await taskApi.confirmPlan(String(props.task.id))
    ElMessage.success(`已确认 ${drafts.value.length} 条草案并开始分发`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { confirming.value = false }
}

async function handleReject() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      '将作废全部草案，任务回到「待规划」状态，可重新触发 AI 拆解。是否继续？',
      '拒绝拆解方案',
      { type: 'warning', confirmButtonText: '拒绝重拆', cancelButtonText: '取消' }
    )
  } catch { return }
  rejecting.value = true
  try {
    const res = await taskApi.rejectPlan(String(props.task.id))
    ElMessage.success(`已作废 ${res.cancelledCount} 条草案，任务已回到待规划`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { rejecting.value = false }
}
</script>
