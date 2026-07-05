<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    :title="data?.title || '生成接入内容'"
    width="880px"
    top="3vh"
    append-to-body
    :close-on-click-modal="false"
    @opened="handleOpened"
  >
    <div v-loading="loading">
      <!-- Agent 摘要 -->
      <el-descriptions v-if="data" :column="2" border size="small" style="margin-bottom:16px">
        <el-descriptions-item label="Agent">{{ data.agentName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ data.role }}</el-descriptions-item>
        <el-descriptions-item label="API Key">
          <code>{{ data.apiKey && data.apiKey.length > 12 ? data.apiKey.substring(0, 12) + '...' : data.apiKey }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="服务地址">{{ data.baseUrl }}</el-descriptions-item>
      </el-descriptions>

      <!-- 内容区 -->
      <div v-if="data" style="margin-bottom:8px;font-size:12px;color:var(--ha-muted)">
        以下内容可直接复制到 Trae / Qoder 聊天框中，外部 Agent 即可按此接入并开始工作。
      </div>
      <el-input
        v-if="data"
        type="textarea"
        :rows="18"
        :model-value="showSkillOnly ? data.skillContent : data.content"
        readonly
        style="font-family: monospace; font-size:12px; line-height:1.7"
      />
    </div>

    <template #footer>
      <el-button type="primary" @click="copyContent">📋 复制全部</el-button>
      <el-button @click="copySkill">📋 仅复制 SKILL</el-button>
      <el-button @click="toggleView">{{ showSkillOnly ? '查看完整内容' : '查看纯 Skill' }}</el-button>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import type { AgentOnboardingResponse } from '@/types'

const props = defineProps<{
  modelValue: boolean
  agentId: string | number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const loading = ref(false)
const data = ref<AgentOnboardingResponse | null>(null)
const showSkillOnly = ref(false)

async function fetchData() {
  if (!props.agentId) return
  loading.value = true
  try {
    data.value = await agentApi.getOnboardingContent(String(props.agentId))
    showSkillOnly.value = false
  } catch (e: any) {
    const msg = e?.response?.data?.msg || e?.message || '获取接入内容失败'
    ElMessage.error(msg)
    data.value = null
  } finally {
    loading.value = false
  }
}

function handleOpened() {
  fetchData()
}

// 当外部 agentId 变化且弹窗已打开时重新拉取
watch(() => props.agentId, (newVal) => {
  if (props.modelValue && newVal) {
    fetchData()
  }
})

function copyContent() {
  if (data.value) {
    navigator.clipboard.writeText(data.value.content)
    ElMessage.success('已复制全部接入内容到剪贴板')
  }
}

function copySkill() {
  if (data.value) {
    navigator.clipboard.writeText(data.value.skillContent)
    ElMessage.success('已复制 SKILL 内容到剪贴板')
  }
}

function toggleView() {
  showSkillOnly.value = !showSkillOnly.value
}

function close() {
  emit('update:modelValue', false)
}
</script>
