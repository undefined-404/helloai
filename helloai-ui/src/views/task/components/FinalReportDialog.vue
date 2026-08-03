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
        :description="emptyDesc"
      />
    </div>
    <!-- footer 不设关闭按钮（右上角 X 已承担关闭），保持四个动作按钮 -->
    <template #footer>
      <el-button v-if="report?.content" @click="handleCopy">复制</el-button>
      <el-button v-if="report?.content" @click="handleExport">导出 .md</el-button>
      <el-button
        type="success"
        plain
        :loading="downloading"
        @click="handleDownload"
      >交付物</el-button>
      <el-button
        type="primary"
        :loading="generating"
        :disabled="task?.status !== 'DONE' || reportGenerating"
        @click="handleGenerate"
      >{{ reportGenerating ? '生成中…' : (report?.content ? '重新生成' : '生成报告') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { fmtTime } from '@/utils/tableConfig'
import { saveBlobResponse } from '@/utils/download'
import MarkdownView from '@/components/MarkdownView.vue'
import type { Task, TaskFinalReport } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => {
  emit('update:modelValue', v)
  if (!v) stopPolling()
})

const loadingReport = ref(false)
const generating = ref(false)
const report = ref<TaskFinalReport | null>(null)

// V41: 生成中 = 本地请求在途 或 后端状态为 GENERATING（自动路径/其他窗口触发）
const reportGenerating = computed(() => generating.value || report.value?.status === 'GENERATING')

// 空态文案：按后端生成状态区分（GENERATING/FAILED/NONE）
const emptyDesc = computed(() => {
  if (props.task?.status !== 'DONE') return '任务完成（DONE）后才能生成整合报告'
  if (report.value?.status === 'GENERATING') return '报告正在生成中，由 Planner 整合全部子任务产出，请稍候…'
  if (report.value?.status === 'FAILED') return '上次生成失败，点击下方按钮重新生成'
  return '尚未生成整合报告，点击下方按钮由 Planner 整合全部子任务产出'
})

// 生成中轮询：弹窗打开且状态为 GENERATING 时每 5s 拉取一次，直到非生成中
let pollTimer: ReturnType<typeof setInterval> | null = null
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!props.task) return
    try {
      const r = await taskApi.getFinalReport(String(props.task.id))
      report.value = r
      if (r.status !== 'GENERATING') stopPolling()
    } catch { /* 网络异常不中断轮询 */ }
  }, 5000)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
onBeforeUnmount(stopPolling)

async function loadReport() {
  if (!props.task) return
  loadingReport.value = true
  report.value = null
  try {
    const r = await taskApi.getFinalReport(String(props.task.id))
    report.value = r
    if (r.status === 'GENERATING') startPolling()
    else stopPolling()
  } catch { /* 拦截器已弹错 */ }
  finally { loadingReport.value = false }
}

async function handleGenerate() {
  if (!props.task || reportGenerating.value) return
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
  // 同步列表行状态：按钮转"生成中"禁用态（即使关闭弹窗重开也能看到）
  if (props.task) props.task.finalReportStatus = 'GENERATING'
  try {
    report.value = await taskApi.generateFinalReport(String(props.task.id))
    if (props.task) props.task.finalReportStatus = 'DONE'
    ElMessage.success('整合报告生成完成')
  } catch {
    // 拦截器已弹错（非 DONE / 无产出 / LLM 失败由后端 BizException 统一提示）；
    // 后端失败会置 FAILED，重拉刷新状态与文案
    await loadReport()
  } finally { generating.value = false }
}

// ── 交付物 zip 下载（实时聚合；报告已生成时包内含 01-最终整合报告.md）──
const downloading = ref(false)
async function handleDownload() {
  if (!props.task) return
  downloading.value = true
  try {
    const resp = await taskApi.downloadDeliverables(String(props.task.id))
    saveBlobResponse(resp, `${props.task.title || 'task'}-交付物.zip`)
  } catch { /* 拦截器已弹错 */ }
  finally { downloading.value = false }
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
