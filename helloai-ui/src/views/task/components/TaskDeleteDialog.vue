<template>
  <el-dialog
    v-model="visible"
    title="删除任务"
    width="480px"
    top="8vh"
    append-to-body
    @open="loadCounts"
    @close="$emit('close')"
  >
    <div v-loading="loadingCounts">
      <p style="font-size:14px;color:var(--ha-ink);margin:0 0 12px">
        此操作不可撤销。将级联物理清理以下关联数据：
      </p>
      <div class="delete-counts" v-if="counts">
        <div class="count-row"><span>子任务（含死信）</span><strong>{{ counts.subTaskCount }}</strong></div>
        <div class="count-row"><span>死信子任务</span><strong>{{ counts.deadLetterCount }}</strong></div>
        <div class="count-row"><span>模块</span><strong>{{ counts.moduleCount }}</strong></div>
        <div class="count-row"><span>审查记录</span><strong>{{ counts.reviewCount }}</strong></div>
        <div class="count-row"><span>执行记录</span><strong>{{ counts.executionCount }}</strong></div>
        <div class="count-row"><span>未读收件箱消息</span><strong>{{ counts.unreadInboxCount }}</strong></div>
        <div class="count-row"><span>时间线事件</span><strong>{{ counts.timelineCount }}</strong></div>
      </div>
      <p v-else-if="!loadingCounts" style="font-size:13px;color:var(--ha-ink-secondary);margin:0">
        关联数据统计加载失败，不影响删除操作
      </p>
      <el-alert
        v-if="counts && counts.activeSubTaskCount > 0"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top:12px"
        :title="`有 ${counts.activeSubTaskCount} 个子任务正在执行中，删除将丢弃其在途执行结果`"
      />
      <p style="font-size:13px;color:var(--ha-danger);margin:12px 0 0">
        请输入任务标题 <code>{{ task?.title }}</code> 以确认删除：
      </p>
      <div class="confirm-row">
        <el-input
          v-model="confirmInput"
          placeholder="输入标题确认"
          :class="{ 'is-error': confirmError }"
        />
        <el-button plain @click="fillConfirm">一键填入</el-button>
      </div>
      <p v-if="confirmError" style="color:var(--ha-danger);font-size:12px;margin:4px 0 0">
        标题不匹配
      </p>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="danger"
        :loading="deleting"
        :disabled="!canDelete"
        @click="handleDelete"
      >
        确认删除
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/task'
import type { Task, TaskRelatedCounts } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const loadingCounts = ref(false)
const deleting = ref(false)
const counts = ref<TaskRelatedCounts | null>(null)
const confirmInput = ref('')
const confirmError = ref(false)

const canDelete = computed(() => confirmInput.value === props.task?.title)

watch(confirmInput, () => { confirmError.value = false })
watch(visible, (v) => { if (!v) { confirmInput.value = ''; confirmError.value = false } })

function fillConfirm() { confirmInput.value = props.task?.title ?? '' }

async function loadCounts() {
  if (!props.task) return
  loadingCounts.value = true
  counts.value = null
  try { counts.value = await taskApi.relatedCounts(String(props.task.id)) }
  catch { /* 拦截器已弹错；统计加载失败不阻断删除确认 */ }
  finally { loadingCounts.value = false }
}

async function handleDelete() {
  if (!canDelete.value || !props.task) return
  if (confirmInput.value !== props.task.title) { confirmError.value = true; return }
  deleting.value = true
  try {
    await taskApi.deleteTask(String(props.task.id), confirmInput.value)
    ElMessage.success(`已删除任务「${props.task.title}」及全部关联数据`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { deleting.value = false }
}
</script>

<style scoped>
.delete-counts {
  background: var(--ha-surface);
  border-radius: var(--ha-radius-md);
  padding: 12px 16px;
}
.confirm-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.confirm-row .el-input {
  flex: 1;
}
.count-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
  color: var(--ha-ink-secondary);
}
.count-row strong {
  color: var(--ha-ink);
  font-weight: 600;
}
</style>
