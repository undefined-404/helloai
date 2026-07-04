<template>
  <el-dialog
    v-model="visible"
    :title="agent?.status === 'ACTIVE' ? '禁用 Agent' : '启用 Agent'"
    width="420px"
    top="10vh"
    @close="$emit('close')"
  >
    <div style="text-align:center;padding:12px 0">
      <p style="font-size:15px;color:var(--ha-ink);margin:0">
        {{ agent?.status === 'ACTIVE' ? '确定要禁用此 Agent？' : '确定要启用此 Agent？' }}
      </p>
      <p style="font-size:13px;color:var(--ha-muted);margin:8px 0 0">
        <template v-if="agent?.status === 'ACTIVE'">禁用后该 Agent 将无法接收任务和调用 API。</template>
        <template v-else>启用后该 Agent 将恢复正常工作。</template>
      </p>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        :type="agent?.status === 'ACTIVE' ? 'warning' : 'success'"
        :loading="loading"
        @click="handleConfirm"
      >
        {{ agent?.status === 'ACTIVE' ? '确认禁用' : '确认启用' }}
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
    ElMessage.success(newStatus === 'ACTIVE' ? '已启用' : '已禁用')
    visible.value = false
    emit('done')
  } finally { loading.value = false }
}
</script>
