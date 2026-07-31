<template>
  <el-dialog
    v-model="visible"
    title="最终整合报告"
    width="860px"
    top="5vh"
    append-to-body
    :close-on-click-modal="false"
    @open="loadReport"
  >
    <div v-loading="loadingReport">
      <!-- 已有报告：元信息 + Markdown 正文 -->
      <template v-if="report?.content">
        <div class="report-meta">
          <span>由 Planner「{{ report.agentName || '未知 Agent' }}」整合生成</span>
          <span v-if="report.generatedAt">{{ fmtTime(report.generatedAt) }}</span>
        </div>
        <div class="report-body">
          <MarkdownView :content="report.content" />
        </div>
      </template>
      <!-- 无报告空态：任务收口会自动生成，也可在此手动补生成 -->
      <el-empty
        v-else-if="!loadingReport"
        :description="task?.status === 'DONE'
          ? '尚未生成整合报告，点击下方按钮由 Planner 整合全部子任务产出'
          : '任务完成（DONE）后才能生成整合报告'"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="report?.content" @click="handleCopy">复制</el-button>
      <el-button v-if="report?.content" @click="handleExport">导出 .md</el-button>
      <el-button
        type="primary"
        :loading="generating"
        :disabled="task?.status !== 'DONE'"
        @click="handleGenerate"
      >{{ report?.content ? '重新生成' : '生成报告' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import MarkdownView from '@/components/MarkdownView.vue'
import type { Task, TaskFinalReport } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const loadingReport = ref(false)
const generating = ref(false)
const report = ref<TaskFinalReport | null>(null)

async function loadReport() {
  if (!props.task) return
  loadingReport.value = true
  report.value = null
  try { report.value = await taskApi.getFinalReport(props.task.id) }
  catch { /* 拦截器已弹错 */ }
  finally { loadingReport.value = false }
}

async function handleGenerate() {
  if (!props.task) return
  if (report.value?.content) {
    try {
      await ElMessageBox.confirm(
        '将由 Planner 重新整合全部子任务产出并覆盖当前报告，约需几十秒。是否继续？',
        '重新生成',
        { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' }
      )
    } catch { return }
  }
  generating.value = true
  try {
    report.value = await taskApi.generateFinalReport(props.task.id)
    ElMessage.success('整合报告生成完成')
  } catch { /* 拦截器已弹错（非 DONE / 无产出 / LLM 失败由后端 BizException 统一提示） */ }
  finally { generating.value = false }
}

async function handleCopy() {
  if (!report.value?.content) return
  try {
    await navigator.clipboard.writeText(report.value.content)
    ElMessage.success('已复制到剪贴板')
  } catch { ElMessage.error('复制失败，请手动选择文本复制') }
}

function handleExport() {
  if (!report.value?.content) return
  const blob = new Blob([report.value.content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.task?.title || 'task'}-最终整合报告.md`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.report-meta {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--ha-muted);
}
.report-body {
  max-height: 62vh;
  overflow-y: auto;
  padding: 4px 2px;
}
</style>
