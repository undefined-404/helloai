<template>
  <div class="dashboard" v-loading="loading">
    <!-- Stats Cards -->
    <div class="stats-grid ha-stagger-entrance">
      <div
        v-for="stat in stats"
        :key="stat.label"
        class="stat-card"
      >
        <div class="stat-dot" :class="stat.color" />
        <div class="stat-body">
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-value">{{ stat.value }}</div>
        </div>
      </div>
    </div>

    <!-- Charts -->
    <div class="charts-grid ha-stagger-entrance">
      <div class="chart-card">
        <div class="chart-header">
          <el-icon color="#2B5FD9"><User /></el-icon>
          <span>Agent 积分排行</span>
        </div>
        <div ref="rankChart" class="chart-body" />
      </div>
      <div class="chart-card">
        <div class="chart-header">
          <el-icon color="#10B981"><DataLine /></el-icon>
          <span>任务吞吐量</span>
        </div>
        <div ref="throughputChart" class="chart-body" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboardApi } from '@/api/dashboard'
import { DataLine } from '@element-plus/icons-vue'

const loading = ref(false)
const stats = ref([
  { label: '总任务', value: 0, color: 'primary' },
  { label: '活跃子任务', value: 0, color: 'success' },
  { label: '待审查', value: 0, color: 'warning' },
  { label: '阻塞任务', value: 0, color: 'danger' }
])

const rankChart = ref<HTMLDivElement>()
const throughputChart = ref<HTMLDivElement>()
let rankInstance: any = null
let throughputInstance: any = null

function initRankChart(data: any) {
  if (!rankChart.value) return
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
      axisLabel: { fontSize: 12, color: '#6B7280' },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: { color: '#F0F2F5', type: 'dashed' }
      },
      axisLabel: { fontSize: 11, color: '#9CA3AF' }
    },
    series: [{
      data: (data?.agentRanking || []).map((a: any) => a.score),
      type: 'bar',
      barWidth: 20,
      borderRadius: [4, 4, 0, 0],
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#2B5FD9' },
          { offset: 1, color: '#EEF2FF' }
        ])
      },
      animationDuration: 800,
      animationEasing: 'cubicOut'
    }]
  })
}

function initThroughputChart(data: any) {
  if (!throughputChart.value) return
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
      axisLabel: { fontSize: 12, color: '#6B7280' },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: { color: '#F0F2F5', type: 'dashed' }
      },
      axisLabel: { fontSize: 11, color: '#9CA3AF' }
    },
    series: [{
      data: (data?.throughput || []).map((t: any) => t.count),
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, color: '#10B981' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(16, 185, 129, 0.15)' },
          { offset: 1, color: 'rgba(16, 185, 129, 0.01)' }
        ])
      },
      itemStyle: { color: '#10B981' },
      animationDuration: 800,
      animationEasing: 'cubicOut'
    }]
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const data: any = await dashboardApi.stats() || {}
    if (data.totalTasks !== undefined) {
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
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  rankInstance?.dispose()
  throughputInstance?.dispose()
})
</script>

<style scoped>
.dashboard {
  max-width: 1200px;
}

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
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
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

.stat-body {
  flex: 1;
}

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
</style>
