<template>
  <div v-loading="loading" class="page ha-entrance-up">
    <!-- 窗口选择 + 全局概览 -->
    <div class="dash-toolbar">
      <div class="dash-title">
        <el-icon style="color: var(--ha-primary)"><DataAnalysis /></el-icon>
        <span>质量度量看板</span>
      </div>
      <el-radio-group v-model="windowDays" size="small" @change="onWindowChange">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>

    <el-alert v-if="errorMsg" :title="errorMsg" type="error" show-icon :closable="false" class="dash-error" />

    <div v-if="!errorMsg" class="stats-grid ha-stagger-entrance">
      <div v-for="stat in stats" :key="stat.label" class="stat-card ha-card-lift">
        <div class="stat-dot" :class="stat.color" aria-hidden="true" />
        <div class="stat-body">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ stat.value }}</div>
        </div>
      </div>
    </div>

    <!-- 图表区 -->
    <template v-if="!errorMsg">
      <div class="charts-grid ha-entrance-up" style="animation-delay: 100ms">
        <div class="chart-card">
          <div class="chart-header">
            <el-icon style="color: var(--ha-primary)"><DataLine /></el-icon>
            <span>审查质量趋势</span>
          </div>
          <div v-if="hasTrends" ref="trendChart" class="chart-body" />
          <el-empty v-else description="窗口内暂无审查记录" :image-size="80" />
        </div>
        <div class="chart-card" style="animation-delay: 150ms">
          <div class="chart-header">
            <el-icon style="color: var(--ha-warning)"><Trophy /></el-icon>
            <span>Agent 一次通过率排行 TOP10</span>
          </div>
          <div v-if="hasRanks" ref="rankChart" class="chart-body" />
          <el-empty v-else description="暂无可排行 Agent" :image-size="80" />
        </div>
      </div>
      <div class="charts-grid three ha-entrance-up" style="animation-delay: 200ms">
        <div class="chart-card">
          <div class="chart-header">
            <el-icon style="color: var(--ha-danger)"><WarningFilled /></el-icon>
            <span>驳回原因分布</span>
          </div>
          <div v-if="hasDefects" ref="defectChart" class="chart-body" />
          <el-empty v-else description="窗口内无驳回标签" :image-size="80" />
        </div>
        <div class="chart-card">
          <div class="chart-header">
            <el-icon style="color: var(--ha-success)"><RefreshLeft /></el-icon>
            <span>返工轮次分布</span>
          </div>
          <div v-if="hasReworks" ref="reworkChart" class="chart-body" />
          <el-empty v-else description="窗口内暂无审查记录" :image-size="80" />
        </div>
        <div class="chart-card">
          <div class="chart-header">
            <el-icon style="color: var(--ha-info)"><Select /></el-icon>
            <span>Reviewer 放水率</span>
          </div>
          <div v-if="hasReviewers" ref="reviewerChart" class="chart-body" />
          <el-empty v-else description="窗口内暂无审查者" :image-size="80" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import type { ECharts } from 'echarts'
import { qualityApi } from '@/api/quality'
import type { AgentQualityRank, QualityDashboardResponse } from '@/types/quality'
import { useThemeStore } from '@/stores/theme'
import { DataAnalysis, DataLine, RefreshLeft, Select, Trophy, WarningFilled } from '@element-plus/icons-vue'

const loading = ref(false)
const errorMsg = ref('')
const windowDays = ref(30)
const data = ref<QualityDashboardResponse | null>(null)
const ranks = ref<AgentQualityRank[]>([])

const hasTrends = computed(() => Array.isArray(data.value?.trends) && data.value!.trends.length > 0)
const hasRanks = computed(() => ranks.value.length > 0)
const hasDefects = computed(() => Array.isArray(data.value?.defectDistributions) && data.value!.defectDistributions.length > 0)
const hasReworks = computed(() => Array.isArray(data.value?.reworkRounds) && data.value!.reworkRounds.length > 0)
const hasReviewers = computed(() => Array.isArray(data.value?.reviewers) && data.value!.reviewers.length > 0)

interface Stat {
  label: string
  value: string
  color: 'primary' | 'success' | 'warning' | 'danger'
}

const stats = computed<Stat[]>(() => {
  const o = data.value?.overview
  if (!o) {
    return [
      { label: '累计审查', value: '-', color: 'primary' },
      { label: '一次通过率', value: '-', color: 'success' },
      { label: '平均返工轮数', value: '-', color: 'warning' },
      { label: '活跃执行者', value: '-', color: 'danger' }
    ]
  }
  return [
    { label: '累计审查', value: String(o.totalReviewed ?? 0), color: 'primary' },
    { label: '一次通过率', value: `${o.firstPassRate ?? 0}%`, color: 'success' },
    { label: '平均返工轮数', value: (o.avgReworkRounds ?? 0).toFixed(2), color: 'warning' },
    { label: '活跃执行者', value: String(o.activeExecutors ?? 0), color: 'danger' }
  ]
})

// ---- ECharts 实例（定义明确类型 + null 兜底，避免 unmounted/dispose 时访问空对象） ----
const trendChart = ref<HTMLDivElement>()
const rankChart = ref<HTMLDivElement>()
const defectChart = ref<HTMLDivElement>()
const reworkChart = ref<HTMLDivElement>()
const reviewerChart = ref<HTMLDivElement>()
let trendInstance: ECharts | null = null
let rankInstance: ECharts | null = null
let defectInstance: ECharts | null = null
let reworkInstance: ECharts | null = null
let reviewerInstance: ECharts | null = null

const themeStore = useThemeStore()
// 主题切换联动：图表配色在 init 时经 cssVar 读取 --ha-*，换主题后 dispose 重建即可（不重拉接口）
watch(() => themeStore.theme, async () => {
  await nextTick()
  if (data.value) {
    initTrendChart(data.value)
  }
  initRankChart()
  if (data.value) {
    initDefectChart(data.value)
    initReworkChart(data.value)
    initReviewerChart(data.value)
  }
})

function disposeCharts() {
  ;[trendInstance, rankInstance, defectInstance, reworkInstance, reviewerInstance].forEach((inst) => {
    if (inst) {
      inst.dispose()
    }
  })
  trendInstance = rankInstance = defectInstance = reworkInstance = reviewerInstance = null
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/**
 * 统一 init + 重建：先释放旧实例再初始化，避免 ECharts 内部容器泄漏。
 * option 用 any（与 Dashboard.vue 先例一致）：本依赖 echarts 类型声明对
 * bar.borderRadius 等运行时合法属性校验过严（vue-tsc 0 错为硬门槛）。
 */
function initChart(el: HTMLDivElement | undefined, prev: ECharts | null, option: any): ECharts | null {
  if (!el) return prev
  if (prev) {
    prev.dispose()
    prev = null
  }
  const instance = echarts.init(el)
  instance.setOption(option)
  return instance
}

function initTrendChart(d: QualityDashboardResponse) {
  trendInstance = initChart(trendChart.value, trendInstance, {
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: cssVar('--ha-muted') } },
    grid: { left: 8, right: 8, top: 32, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: d.trends.map((t) => t.period.slice(5)),
      axisLabel: { fontSize: 11, color: cssVar('--ha-muted') },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: cssVar('--ha-border-light'), type: 'dashed' } },
      axisLabel: { fontSize: 11, color: cssVar('--ha-muted') }
    },
    series: [
      {
        name: '审查数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2, color: cssVar('--ha-primary') },
        itemStyle: { color: cssVar('--ha-primary') },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(99, 102, 241, 0.15)' },
            { offset: 1, color: 'rgba(99, 102, 241, 0.01)' }
          ])
        },
        data: d.trends.map((t) => t.reviewedCount)
      },
      {
        name: '通过数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2, color: cssVar('--ha-success') },
        itemStyle: { color: cssVar('--ha-success') },
        data: d.trends.map((t) => t.approvedCount)
      }
    ]
  })
}

function initRankChart() {
  const rows = ranks.value
  if (rows.length === 0) return
  const axisLabelColor = cssVar('--ha-muted')
  rankInstance = initChart(rankChart.value, rankInstance, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 8, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      max: 100,
      splitLine: { lineStyle: { color: cssVar('--ha-border-light'), type: 'dashed' } },
      axisLabel: { fontSize: 11, color: axisLabelColor, formatter: '{value}%' }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => (r.agentName ?? r.agentId).slice(0, 12)),
      axisLabel: { fontSize: 11, color: axisLabelColor },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        name: '一次通过率',
        type: 'bar',
        barWidth: 14,
        borderRadius: [0, 4, 4, 0],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: cssVar('--ha-primary-light') },
            { offset: 1, color: cssVar('--ha-primary') }
          ])
        },
        label: { show: true, position: 'right', fontSize: 11, color: axisLabelColor, formatter: '{c}%' },
        data: rows.map((r) => r.firstPassRate)
      }
    ]
  })
}

function initDefectChart(d: QualityDashboardResponse) {
  const rows = d.defectDistributions.slice(0, 8)
  const axisLabelColor = cssVar('--ha-muted')
  defectInstance = initChart(defectChart.value, defectInstance, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 8, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: cssVar('--ha-border-light'), type: 'dashed' } },
      axisLabel: { fontSize: 11, color: axisLabelColor }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => r.defectTag.slice(0, 14)),
      axisLabel: { fontSize: 11, color: axisLabelColor },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        name: '出现次数',
        type: 'bar',
        barWidth: 14,
        borderRadius: [0, 4, 4, 0],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: 'rgba(239, 68, 68, 0.35)' },
            { offset: 1, color: cssVar('--ha-danger') }
          ])
        },
        data: rows.map((r) => r.count)
      }
    ]
  })
}

function initReworkChart(d: QualityDashboardResponse) {
  reworkInstance = initChart(reworkChart.value, reworkInstance, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 8, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: d.reworkRounds.map((r) => `第 ${r.round} 轮`),
      axisLabel: { fontSize: 11, color: cssVar('--ha-muted') },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: cssVar('--ha-border-light'), type: 'dashed' } },
      axisLabel: { fontSize: 11, color: cssVar('--ha-muted') }
    },
    series: [
      {
        name: '审查记录数',
        type: 'bar',
        barWidth: 18,
        borderRadius: [4, 4, 0, 0],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: cssVar('--ha-success') },
            { offset: 1, color: cssVar('--ha-success-light') }
          ])
        },
        data: d.reworkRounds.map((r) => r.subTaskCount)
      }
    ]
  })
}

function initReviewerChart(d: QualityDashboardResponse) {
  const rows = d.reviewers.slice(0, 8)
  const axisLabelColor = cssVar('--ha-muted')
  reviewerInstance = initChart(reviewerChart.value, reviewerInstance, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 8, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      max: 100,
      splitLine: { lineStyle: { color: cssVar('--ha-border-light'), type: 'dashed' } },
      axisLabel: { fontSize: 11, color: axisLabelColor, formatter: '{value}%' }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => (r.reviewerName ?? r.reviewerAgentId).slice(0, 12)),
      axisLabel: { fontSize: 11, color: axisLabelColor },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        name: '通过率',
        type: 'bar',
        barWidth: 14,
        borderRadius: [0, 4, 4, 0],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: 'rgba(59, 130, 246, 0.35)' },
            { offset: 1, color: cssVar('--ha-primary') }
          ])
        },
        label: { show: true, position: 'right', fontSize: 11, color: axisLabelColor, formatter: '{c}%' },
        data: rows.map((r) => r.approveRate)
      }
    ]
  })
}

async function loadDashboard() {
  loading.value = true
  errorMsg.value = ''
  try {
    const d = await qualityApi.dashboard(windowDays.value)
    data.value = d || null
    await nextTick()
    if (d) {
      initTrendChart(d)
      initDefectChart(d)
      initReworkChart(d)
      initReviewerChart(d)
    }
  } catch (e: any) {
    errorMsg.value = '加载质量看板失败，请稍后重试'
    console.warn('[quality-dashboard] load failed:', e?.message || e)
  } finally {
    loading.value = false
  }
}

async function loadRankings() {
  try {
    ranks.value = (await qualityApi.agentRankings(10)) || []
    await nextTick()
    initRankChart()
  } catch (e: any) {
    // 排行拉取失败不阻断主看板，仅静默
    console.warn('[quality-dashboard] rankings load failed:', e?.message || e)
  }
}

function onWindowChange() {
  loadDashboard()
}

onMounted(() => {
  loadDashboard()
  loadRankings()
})

onUnmounted(disposeCharts)
</script>

<style scoped>
.page {
  max-width: var(--ha-content-width);
}

.dash-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.dash-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ha-ink);
}

.dash-error {
  margin-bottom: 16px;
}

/* ---- Overview Cards ---- */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  padding: 20px;
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.stat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}
.stat-dot.primary { background: var(--ha-primary); }
.stat-dot.success { background: var(--ha-success); }
.stat-dot.warning { background: var(--ha-warning); }
.stat-dot.danger  { background: var(--ha-danger); }

.stat-body { flex: 1; }

.stat-label {
  font-size: 13px;
  color: var(--ha-muted);
  margin-bottom: 4px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--ha-ink);
  letter-spacing: -0.02em;
  line-height: 1.1;
}

/* ---- Charts ---- */
.charts-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
  margin-bottom: 16px;
}

.charts-grid.three {
  grid-template-columns: repeat(3, 1fr);
}

.chart-card {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  padding: 20px;
}

.chart-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
  margin-bottom: 4px;
}

.chart-body {
  height: 320px;
  width: 100%;
}

/* ---- Responsive ---- */
@media (max-width: 1200px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts-grid,
  .charts-grid.three {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
  .chart-body {
    height: 240px;
  }
}
</style>
