<template>
  <div class="page ha-entrance-up" v-loading="loading">
    <!-- Stats Cards -->
    <div class="stats-grid ha-stagger-entrance">
      <div
        v-for="stat in stats"
        :key="stat.label"
        class="stat-card"
      >
        <div class="stat-dot" :class="stat.color" aria-hidden="true" />
        <div class="stat-body">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ stat.value }}</div>
        </div>
      </div>
    </div>

    <!-- Error state -->
    <el-alert
      v-if="errorMsg"
      :title="errorMsg"
      type="error"
      show-icon
      :closable="false"
      class="dashboard-error"
    />

    <!-- Charts -->
    <div v-if="!errorMsg" class="charts-grid">
      <div class="chart-card ha-entrance-up" style="animation-delay: 100ms">
        <div class="chart-header">
          <el-icon color="#7C3AED"><User /></el-icon>
          <span>Agent 积分排行</span>
        </div>
        <div v-if="hasRankData" ref="rankChart" class="chart-body" />
        <el-empty v-else description="暂无可排行 Agent" :image-size="80" />
      </div>
      <div class="chart-card ha-entrance-up" style="animation-delay: 200ms">
        <div class="chart-header">
          <el-icon color="#10B981"><DataLine /></el-icon>
          <span>任务吞吐量</span>
        </div>
        <div v-if="hasThroughputData" ref="throughputChart" class="chart-body" />
        <el-empty v-else description="暂无吞吐量数据" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboardApi } from '@/api/dashboard'
import { DataLine } from '@element-plus/icons-vue'

const loading = ref(false)
const errorMsg = ref('')

interface Stat {
  label: string
  value: number
  color: 'primary' | 'success' | 'warning' | 'danger'
}

const stats = ref<Stat[]>([
  { label: '总任务', value: 0, color: 'primary' },
  { label: '活跃子任务', value: 0, color: 'success' },
  { label: '待审查', value: 0, color: 'warning' },
  { label: '阻塞任务', value: 0, color: 'danger' }
])

const rawData = ref<any>(null)

const hasRankData = computed(() => {
  const ranking = rawData.value?.agentRanking
  return Array.isArray(ranking) && ranking.length > 0
})

const hasThroughputData = computed(() => {
  const throughput = rawData.value?.throughput
  return Array.isArray(throughput) && throughput.length > 0
})

const rankChart = ref<HTMLDivElement>()
const throughputChart = ref<HTMLDivElement>()
let rankInstance: any = null
let throughputInstance: any = null

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function initRankChart(data: any) {
  if (!rankChart.value) return
  const axisLabelColor = cssVar('--ha-muted')
  const splitLineColor = cssVar('--ha-border-light')
  const primaryColor = cssVar('--ha-primary')
  const primaryLightColor = cssVar('--ha-primary-light')
  rankInstance = echarts.init(rankChart.value)
  rankInstance.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' }
    },
    grid: {
      left: 8,
      right: 8,
      top: 8,
      bottom: 8,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: (data?.agentRanking || []).map((a: any) => a.name),
      axisLabel: { fontSize: 12, color: axisLabelColor },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: { color: splitLineColor, type: 'dashed' }
      },
      axisLabel: { fontSize: 11, color: axisLabelColor }
    },
    series: [{
      data: (data?.agentRanking || []).map((a: any) => a.score),
      type: 'bar',
      barWidth: 20,
      borderRadius: [4, 4, 0, 0],
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: primaryColor },
          { offset: 1, color: primaryLightColor }
        ])
      },
      animationDuration: 800,
      animationEasing: 'cubicOut'
    }]
  })
}

function initThroughputChart(data: any) {
  if (!throughputChart.value) return
  const axisLabelColor = cssVar('--ha-muted')
  const splitLineColor = cssVar('--ha-border-light')
  const successColor = cssVar('--ha-success')
  throughputInstance = echarts.init(throughputChart.value)
  throughputInstance.setOption({
    tooltip: {
      trigger: 'axis'
    },
    grid: {
      left: 8,
      right: 8,
      top: 8,
      bottom: 8,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: (data?.throughput || []).map((t: any) => t.date),
      axisLabel: { fontSize: 12, color: axisLabelColor },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: { color: splitLineColor, type: 'dashed' }
      },
      axisLabel: { fontSize: 11, color: axisLabelColor }
    },
    series: [{
      data: (data?.throughput || []).map((t: any) => t.count),
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, color: successColor },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(16, 185, 129, 0.15)' },
          { offset: 1, color: 'rgba(16, 185, 129, 0.01)' }
        ])
      },
      itemStyle: { color: successColor },
      animationDuration: 800,
      animationEasing: 'cubicOut'
    }]
  })
}

async function loadDashboard() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data: any = await dashboardApi.stats()
    rawData.value = data || {}
    if (data?.totalTasks !== undefined) {
      stats.value = [
        { label: '总任务', value: data.totalTasks || 0, color: 'primary' },
        { label: '活跃子任务', value: data.activeSubTasks || 0, color: 'success' },
        { label: '待审查', value: data.pendingReviews || 0, color: 'warning' },
        { label: '阻塞任务', value: data.blockedTasks || 0, color: 'danger' }
      ]
    }
    await nextTick()
    initRankChart(data)
    initThroughputChart(data)
  } catch (e: any) {
    errorMsg.value = '加载仪表盘数据失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => loadDashboard())

onUnmounted(() => {
  rankInstance?.dispose()
  throughputInstance?.dispose()
})
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }

.dashboard-error {
  margin-bottom: 16px;
}

/* ---- Stats ---- */
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
  transition: transform var(--ha-duration-normal) var(--ha-ease-out),
              box-shadow var(--ha-duration-normal) var(--ha-ease-out);
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 20px rgba(124, 58, 237, 0.12);
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
}

.chart-card {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  padding: 20px;
  transition: transform var(--ha-duration-normal) var(--ha-ease-out),
              box-shadow var(--ha-duration-normal) var(--ha-ease-out);
}
.chart-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 20px rgba(124, 58, 237, 0.12);
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
  .charts-grid {
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
