<template>
  <el-dialog
    v-model="visible"
    :title="agent?.status === 'ACTIVE' ? '注销 Agent' : '恢复注册'"
    width="420px"
    top="10vh"
    append-to-body
    @close="$emit('close')"
  >
    <div style="text-align:center;padding:12px 0">
      <p style="font-size:15px;color:var(--ha-ink);margin:0">
        {{ agent?.status === 'ACTIVE' ? '确定要注销此 Agent？' : '确定要恢复此 Agent 的注册？' }}
      </p>
      <p style="font-size:13px;color:var(--ha-muted);margin:8px 0 0">
        <template v-if="agent?.status === 'ACTIVE'">注销后该 Agent 将无法接收任务和调用 API。</template>
        <template v-else>恢复注册后该 Agent 将恢复正常工作。</template>
      </p>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        :type="agent?.status === 'ACTIVE' ? 'warning' : 'success'"
        :loading="loading"
        @click="handleConfirm"
      >
        {{ agent?.status === 'ACTIVE' ? '确认注销' : '确认恢复注册' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import type { AgentListItem } from '@/types'

const props = defineProps<{ modelValue: boolean; agent: AgentListItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const loading = ref(false)

async function handleConfirm() {
  if (!props.agent) return
  loading.value = true
  try {
    const newStatus = props.agent.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    await agentApi.updateStatus(props.agent.id, newStatus)
    ElMessage.success(newStatus === 'ACTIVE' ? '已恢复注册' : '已注销')
    visible.value = false
    emit('done')
  } finally { loading.value = false }
}
</script>
