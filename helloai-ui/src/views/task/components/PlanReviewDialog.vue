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
      <el-alert
        v-if="generating"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="AI 正在后台拆解，草案生成中…"
        description="通常需要一段时间（几十秒到几分钟不等，视任务复杂程度而定），本页自动刷新等待；期间可关闭弹窗，稍后从任务列表「审阅草案」再进入。"
      />
      <el-table
        v-if="drafts.length"
        :data="drafts"
        border
        stripe
        size="small"
        style="width:100%"
      >
        <el-table-column
          type="index"
          label="#"
          width="48"
        />
        <el-table-column
          prop="title"
          label="标题"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          prop="content"
          label="内容"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column
          prop="deliverable"
          label="交付物"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          prop="acceptance"
          label="验收标准"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          label="优先级"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.priority"
              :type="priorityTagType(row.priority)"
              size="small"
            >
              {{ row.priority }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="依赖"
          width="110"
        >
          <template #default="{ row }">
            {{ fmtDepends(row.dependsOn) }}
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="!loadingDrafts && !generating"
        :description="emptyText"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="danger"
        plain
        :loading="rejecting"
        @click="handleReject"
      >
        拒绝重拆
      </el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="!drafts.length"
        @click="handleConfirm"
      >
        确认并分发
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import type { Task, SubTask, LongId } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => {
  emit('update:modelValue', v)
  // 弹窗关闭即停轮询，避免后台定时器持续打接口
  if (!v) stopPolling()
})

const loadingDrafts = ref(false)
const confirming = ref(false)
const rejecting = ref(false)
const drafts = ref<SubTask[]>([])
// 拆解异步化：草案由后台生成，弹窗打开即进入轮询等待态
const generating = ref(false)
const emptyText = ref('未找到待审阅草案（可能为异常残留），建议「拒绝重拆」使任务回到待规划状态')

const POLL_INTERVAL_MS = 3000
const MAX_WAIT_MS = 5 * 60 * 1000
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollDeadline = 0

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  generating.value = false
}

async function loadDrafts() {
  if (!props.task) return
  stopPolling()
  loadingDrafts.value = true
  drafts.value = []
  pollDeadline = Date.now() + MAX_WAIT_MS
  await pollOnce()
}

// 轮询单步：草案为空且任务仍 PLANNING 时继续等待；
// 任务已回退 PENDING（拆解失败/超时回收）则停止并提示重试
async function pollOnce() {
  const task = props.task
  if (!task || !visible.value) { stopPolling(); return }
  try {
    const [list, latest] = await Promise.all([
      taskApi.planDrafts(String(task.id)),
      taskApi.getById(String(task.id))
    ])
    if (!visible.value) return
    if (list.length) {
      drafts.value = list
      generating.value = false
      loadingDrafts.value = false
      return
    }
    if (latest?.status === 'PENDING') {
      generating.value = false
      loadingDrafts.value = false
      emptyText.value = '拆解失败，任务已回到待规划状态，请回任务列表重试'
      ElMessage.warning('拆解失败，请回任务列表重试')
      return
    }
    if (latest?.status !== 'PLANNING' || Date.now() >= pollDeadline) {
      generating.value = false
      loadingDrafts.value = false
      return
    }
    // 仍在拆解中：进入等待态，3s 后继续轮询
    generating.value = true
    pollTimer = setTimeout(pollOnce, POLL_INTERVAL_MS)
  } catch {
    // 拦截器已弹错；不继续轮询避免报错刷屏
    generating.value = false
    loadingDrafts.value = false
  }
}

onBeforeUnmount(stopPolling)

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
