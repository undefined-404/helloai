<template>
  <el-select
    :model-value="modelValue"
    filterable
    clearable
    :placeholder="placeholder"
    :disabled="disabled"
    :loading="loading"
    style="width:100%"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-option
      v-for="a in agents"
      :key="a.id"
      :label="optionLabel(a)"
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
  // 执行者候选模式：仅 EXECUTOR + ACTIVE（内部 LLM 恒可用、外部需在线），
  // 用于认领/换人/死信重派等“选执行者”场景，避免出现 PLANNER/REVIEWER 与离线 Agent
  executorOnly?: boolean
}>(), {
  modelValue: null,
  placeholder: '选择 Agent',
  disabled: false,
  role: undefined,
  executorOnly: false,
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
}
const ACCESS_TYPE_LABELS: Record<string, string> = {
  API_KEY_LLM: '内部 LLM',
  CLI_CLIENT: '外部 CLI',
  WEB_BROWSER: '外部网页',
}
function optionLabel(a: Agent) {
  if (props.executorOnly) {
    return `${a.name}（${ACCESS_TYPE_LABELS[a.accessType!] || a.accessType || '未知'}）`
  }
  return `${a.name} (${roleLabel(a.role)})`
}
function roleLabel(role: string) {
  return ROLE_LABELS[role] || role
}

async function load() {
  loading.value = true
  try {
    const res = props.executorOnly
      ? await agentApi.listAssignableExecutors()
      : await agentApi.list(props.role ? { role: props.role } : {})
    agents.value = Array.isArray(res) ? res : []
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>
