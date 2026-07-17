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
        以下是 HelloAI 平台为该 Agent 生成的接入内容。请按需使用底部按钮：
        <strong>复制全部</strong> 用于人工交接；
        <strong style="color:var(--el-color-success)">下载 hello_ai_skills.md</strong> 用于保存到 IDE 的 skills 目录；
        <strong style="color:var(--el-color-warning)">一键上班口令</strong> 用于在新会话第一句话里激活 AI Agent。
      </div>
      <el-input
        v-if="data"
        type="textarea"
        :rows="18"
        :model-value="data.content"
        readonly
        style="font-family: monospace; font-size:12px; line-height:1.7"
      />
    </div>

    <template #footer>
      <el-button type="primary" @click="copyContent">📋 复制全部</el-button>
      <el-button type="success" @click="downloadSkill">⬇️ 下载 hello_ai_skills.md</el-button>
      <el-button type="warning" @click="copyActivation">🚀 一键上班口令</el-button>
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

async function fetchData() {
  if (!props.agentId) return
  loading.value = true
  try {
    data.value = await agentApi.getOnboardingContent(String(props.agentId))
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

// 文件名净化：仅保留 ASCII 字母/数字/短横线/下划线，中文名降级为 _
// 避免 Windows/Linux 下文件名出现跨平台兼容问题
function sanitizeFilename(name: string): string {
  const safe = (name || 'agent').replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 64)
  return safe || 'agent'
}

// 下载 hello_ai_skills.md（方案 C：hello_ai_<agentName>.md）
function downloadSkill() {
  if (!data.value) return
  const blob = new Blob([data.value.skillContent], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `hello_ai_${sanitizeFilename(String(data.value.agentName || ''))}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已下载 hello_ai_skills.md，请保存到 IDE 的 skills 目录')
}

// 复制一键上班口令：粘到 IDE 对话框第一句话即可触发 AI Agent 自检接入
function copyActivation() {
  if (!data.value) return
  const cmd = `你是 HelloAI 平台的 ${data.value.agentName}（ID=${data.value.agentId}），请按平台 SKILL 接入并开始工作。`
  navigator.clipboard.writeText(cmd)
  ElMessage.success('已复制激活口令，粘到 IDE 对话框即可触发接入')
}

function close() {
  emit('update:modelValue', false)
}
</script>
