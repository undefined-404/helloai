<template>
  <el-select
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    filterable
    clearable
    :placeholder="placeholder"
    :disabled="disabled"
    :loading="loading"
    style="width:100%"
  >
    <el-option
      v-for="a in agents"
      :key="a.id"
      :label="`${a.name} (${roleLabel(a.role)})`"
      :value="a.id"
    />
  </el-select>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { agentApi } from '@/api/agent'
import type { Agent } from '@/types'

const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  placeholder?: string
  disabled?: boolean
  role?: string
}>(), {
  modelValue: null,
  placeholder: '选择 Agent',
  disabled: false,
  role: undefined,
})

defineEmits<{
  'update:modelValue': [value: string | number | null]
}>()

const agents = ref<Agent[]>([])
const loading = ref(false)

const ROLE_LABELS: Record<string, string> = {
  PLANNER: '规划者',
  EXECUTOR: '执行者',
  REVIEWER: '审查者',
  PATROL: '巡检者',
}
function roleLabel(role: string) {
  return ROLE_LABELS[role] || role
}

async function load() {
  loading.value = true
  try {
    const res = await agentApi.list(props.role ? { role: props.role } : {})
    agents.value = Array.isArray(res) ? res : []
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>
