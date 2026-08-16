<template>
  <el-dialog
    v-model="visible"
    title="删除 Agent"
    width="480px"
    top="8vh"
    append-to-body
    @open="loadCounts"
    @close="$emit('close')"
  >
    <div v-loading="loadingCounts">
      <p style="font-size:14px;color:var(--ha-ink);margin:0 0 12px">
        此操作不可撤销。将级联清理以下关联数据：
      </p>
      <div
        v-if="counts"
        class="delete-counts"
      >
        <div class="count-row">
          <span>子任务</span><strong>{{ counts.subTaskCount }}</strong>
        </div>
        <div class="count-row">
          <span>审查记录</span><strong>{{ counts.reviewCount }}</strong>
        </div>
        <div class="count-row">
          <span>积分流水</span><strong>{{ counts.rewardCount }}</strong>
        </div>
        <div class="count-row">
          <span>活动日志</span><strong>{{ counts.activityCount }}</strong>
        </div>
      </div>
      <p
        v-else-if="!loadingCounts"
        style="font-size:13px;color:var(--ha-ink-secondary);margin:0"
      >
        关联数据统计加载失败，不影响删除操作
      </p>
      <p style="font-size:13px;color:var(--ha-danger);margin:12px 0 0">
        请输入 Agent 名称 <code>{{ agent?.name }}</code> 以确认删除：
      </p>
      <div class="confirm-row">
        <el-input
          v-model="confirmInput"
          placeholder="输入名称确认"
          :class="{ 'is-error': confirmError }"
        />
        <el-button
          plain
          @click="fillConfirm"
        >
          一键填入
        </el-button>
      </div>
      <p
        v-if="confirmError"
        style="color:var(--ha-danger);font-size:12px;margin:4px 0 0"
      >
        名称不匹配
      </p>
    </div>
    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
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
import { agentApi } from '@/api/agent'
import type { AgentListItem, AgentRelatedCounts } from '@/types'

const props = defineProps<{ modelValue: boolean; agent: AgentListItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const loadingCounts = ref(false)
const deleting = ref(false)
const counts = ref<AgentRelatedCounts | null>(null)
const confirmInput = ref('')
const confirmError = ref(false)

const canDelete = computed(() => confirmInput.value === props.agent?.name)

watch(confirmInput, () => { confirmError.value = false })
watch(visible, (v) => { if (!v) { confirmInput.value = ''; confirmError.value = false } })

function fillConfirm() { confirmInput.value = props.agent?.name ?? '' }

async function loadCounts() {
  if (!props.agent) return
  loadingCounts.value = true
  counts.value = null
  try { counts.value = await agentApi.relatedCounts(props.agent.id) }
  catch { /* 拦截器已弹错；统计加载失败不阻断删除确认 */ }
  finally { loadingCounts.value = false }
}

async function handleDelete() {
  if (!canDelete.value || !props.agent) return
  if (confirmInput.value !== props.agent.name) { confirmError.value = true; return }
  deleting.value = true
  try {
    await agentApi.deleteAgent(props.agent.id, confirmInput.value)
    ElMessage.success(`已删除 ${props.agent.name}`)
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
