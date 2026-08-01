<template>
  <el-empty v-if="!events.length" description="暂无时间线条目" />
  <div v-else class="seq-wrapper">
    <!-- 顶部说明条：事件数 / 持续时长 / 失败次数摘要 -->
    <div class="seq-summary">
      <el-tag size="small" type="info">共 {{ events.length }} 条事件</el-tag>
      <el-tag size="small">跨度 {{ summarySpan }}</el-tag>
      <el-tag v-if="failureCount > 0" size="small" type="danger">{{ failureCount }} 次失败</el-tag>
      <el-tag v-if="retryCount > 0" size="small" type="warning">重派 {{ retryCount }} 次</el-tag>
      <el-tag v-if="hasManual" size="small" type="primary">有人工介入</el-tag>
      <span class="seq-hint">红色虚线=响应/失败 · 蓝色实线=主动行为 · 黄色 Note=耗时与原因</span>
    </div>
    <!-- mermaid 渲染容器：每次 events 变化重新 init 防止 HMR 残留 -->
    <div v-if="mermaidSyntax" ref="chartRef" class="seq-chart" :class="{ 'seq-loading': rendering }" />
    <el-alert
      v-if="renderError"
      :title="renderError"
      type="warning"
      :closable="false"
      show-icon
    />
    <!-- 语法查看：调试用，默认折叠 -->
    <el-collapse class="seq-syntax-collapse">
      <el-collapse-item title="查看 Mermaid 源代码（开发者调试）" name="syntax">
        <pre class="seq-syntax">{{ mermaidSyntax }}</pre>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import mermaid from 'mermaid'
import DOMPurify from 'dompurify'
import type { TaskTimelineItem } from '@/types'
import { buildMermaidSequence } from '@/utils/sequenceFlow'

// mermaid 全局初始化（只跑一次）
let mermaidInited = false
function initMermaid() {
  if (mermaidInited) return
  mermaid.initialize({
    startOnLoad: false,
    // 深色模式：与项目 Element Plus 浅色背景形成“监控面板”嵌块效果（黑底 + 浅文本）
    theme: 'dark',
    securityLevel: 'loose',  // 允许中文 label（label 里有中文/特殊字符时不报错）
    sequence: {
      useMaxWidth: true,
      wrap: true,
      showSequenceNumbers: true,  // 显示事件序号，便于按编号定位
      diagramMarginX: 8,
      diagramMarginY: 8,
      boxMargin: 8,
      noteMargin: 12,
      messageMargin: 40
    },
    flowchart: { useMaxWidth: true },
    // dark 主题默认色偏冷交仁偏低饱和，下面重在保证 “文本可读 / 表达区分 / 与外层 .seq-chart 背景同调”
    themeVariables: {
      background: '#1e1e1e',           // 画布背景
      primaryColor: '#2b2b2b',          // 主题色（dark 默认走 primaryTextColor）
      primaryTextColor: '#e6e6e6',      // 主要文本
      primaryBorderColor: '#5a5a5a',
      lineColor: '#a3a3a3',             // lifeline、连线条
      actorBkg: '#262626',              // 参与者柱底色
      actorBorder: '#5a5a5a',
      actorTextColor: '#e6e6e6',
      actorLineColor: '#5a5a5a',        // 垂直 lifeline
      signalColor: '#5aa9ff',           // 实线箭头信号色（主动行为）
      signalTextColor: '#e6e6e6',
      labelBackgroundColor: '#1e1e1e',  // 信号上 label 背景
      labelBoxBkgColor: '#1e1e1e',
      labelBoxBorderColor: '#5a5a5a',
      labelTextColor: '#e6e6e6',
      noteBkgColor: '#3a2f1a',          // Note 背景（暖色，交仁偏黄，逆背景里一眼可辨）
      noteBorderColor: '#8b6e3a',
      noteTextColor: '#f3d79b',
      activationBorderColor: '#5a5a5a',
      activationBkgColor: '#2f2f2f',
      sequenceNumberColor: '#a3a3a3'    // autonumber 序号色
    }
  })
  mermaidInited = true
}

const props = defineProps<{
  events: TaskTimelineItem[]
  resolveAgentName: (agentId: string) => string
}>()

const chartRef = ref<HTMLDivElement>()
const mermaidSyntax = ref<string>('')
const rendering = ref(false)
const renderError = ref<string>('')

// 摘要：事件总数 / 跨度 / 失败 / 重派 / 人工
const summarySpan = computed(() => {
  if (!props.events.length) return '-'
  const sorted = [...props.events].sort((a, b) => new Date(a.createTime).getTime() - new Date(b.createTime).getTime())
  const first = new Date(sorted[0].createTime).getTime()
  const last = new Date(sorted[sorted.length - 1].createTime).getTime()
  const ms = last - first
  if (ms < 1000) return ms + ' ms'
  if (ms < 60_000) return (ms / 1000).toFixed(1) + ' s'
  if (ms < 3_600_000) return (ms / 60_000).toFixed(1) + ' min'
  if (ms < 86_400_000) return (ms / 3_600_000).toFixed(1) + ' h'
  return (ms / 86_400_000).toFixed(1) + ' d'
})

const failureCount = computed(() => props.events.filter(e =>
  e.eventType.includes('failed') || e.eventType.includes('rejected')
  || e.eventType.includes('blocked') || e.eventType.includes('unparseable')
).length)

const retryCount = computed(() => props.events.filter(e =>
  e.eventType === 'sub_task_auto_execute_dispatch_ok'
  || e.eventType === 'sub_task_execution_command_poll_recovery'
  || (e.eventType === 'sub_task_execution_command_created' && e.payload?.trigger === 'blocked_reassign')
).length)

const hasManual = computed(() => props.events.some(e =>
  e.eventType === 'sub_task_dead_letter_manual_assign'
  || e.payload?.trigger === 'manual'
  || e.payload?.trigger === 'dead_letter_redispatch'
))

async function render() {
  renderError.value = ''
  if (!props.events.length) {
    mermaidSyntax.value = ''
    return
  }
  initMermaid()
  rendering.value = true
  try {
    const syntax = buildMermaidSequence(props.events, {
      resolveAgentName: opts => props.resolveAgentName(opts)
    })
    mermaidSyntax.value = syntax
    await nextTick()
    if (!chartRef.value) return
    // mermaid 11 推荐 render() 异步 API（render 返回 svg 字符串）
    // 输出 SVG 含中文文本与 mermaid 自绘样式，先 DOMPurify 清理掉 onload/onclick 等可执行属性，
    // 避免 mermaid 库或恶意语法触发脚本执行
    const id = 'mermaid-' + Date.now() + '-' + Math.floor(Math.random() * 10000)
    const { svg } = await mermaid.render(id, syntax)
    const safeSvg = DOMPurify.sanitize(svg, {
      USE_PROFILES: { svg: true, svgFilters: true },
      FORBID_TAGS: ['script'],
      FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onblur']
    })
    chartRef.value.innerHTML = safeSvg
  } catch (e: any) {
    renderError.value = '时序图渲染失败：' + (e?.message || String(e))
    // 保留语法可见以便排查
    if (chartRef.value) chartRef.value.innerHTML = ''
  } finally {
    rendering.value = false
  }
}

watch(() => props.events, () => render(), { deep: true })

onMounted(render)
onBeforeUnmount(() => {
  if (chartRef.value) chartRef.value.innerHTML = ''
})
</script>

<style scoped>
.seq-wrapper { width: 100%; }
.seq-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 0 4px 12px;
}
.seq-hint {
  margin-left: auto;
  color: var(--ha-muted);
  font-size: 12px;
}
/* 深色画布：与 mermaid theme:dark 背景同色，外层圆角显得是一块嵌在卡片里的“监控面板” */
.seq-chart {
  width: 100%;
  min-height: 240px;
  padding: 8px;
  background: #1e1e1e;
  border-radius: 8px;
  border: 1px solid #303030;
  overflow-x: auto;
}
.seq-chart :deep(svg) { max-width: 100%; height: auto; }
.seq-loading { opacity: 0.6; }

.seq-syntax-collapse { margin-top: 12px; }
.seq-syntax {
  margin: 0;
  padding: 10px;
  /* 语法面板跟主题走：深背景 + 浅文本 */
  background: #1e1e1e;
  color: #e6e6e6;
  border: 1px solid #303030;
  border-radius: 6px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow-y: auto;
}
</style>