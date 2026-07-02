<template>
  <div v-loading="loading">
    <!-- 统计卡片 -->
    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="6" v-for="stat in stats" :key="stat.label">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-value" :class="stat.color">{{ stat.value }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header><span>Agent 积分排行</span></template>
          <div ref="rankChart" style="height:320px" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header><span>任务吞吐量</span></template>
          <div ref="throughputChart" style="height:320px" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { dashboardApi } from '@/api/dashboard'

const loading = ref(false)
const stats = ref([{ label:'总任务', value:0, color:'text-primary' }, { label:'活跃子任务', value:0, color:'text-warning' }, { label:'待审查', value:0, color:'text-danger' }, { label:'阻塞任务', value:0, color:'text-danger' }])
const rankChart = ref<HTMLDivElement>()
const throughputChart = ref<HTMLDivElement>()
let rankInstance: any = null
let throughputInstance: any = null

onMounted(async () => {
  loading.value = true
  try {
    const data: any = await dashboardApi.stats() || {}
    if (data.totalTasks !== undefined) {
      stats.value = [
        { label:'总任务', value:data.totalTasks || 0, color:'text-primary' },
        { label:'活跃子任务', value:data.activeSubTasks || 0, color:'text-warning' },
        { label:'待审查', value:data.pendingReviews || 0, color:'text-danger' },
        { label:'阻塞任务', value:data.blockedTasks || 0, color:'text-danger' }
      ]
    }
    await nextTick()
    if (rankChart.value) {
      rankInstance = echarts.init(rankChart.value)
      rankInstance.setOption({
        tooltip: { trigger: 'axis' },
        xAxis: { type:'category', data: (data.agentRanking||[]).map((a:any)=>a.name) },
        yAxis: { type:'value' },
        series: [{ data: (data.agentRanking||[]).map((a:any)=>a.score), type:'bar' }]
      })
    }
    if (throughputChart.value) {
      throughputInstance = echarts.init(throughputChart.value)
      throughputInstance.setOption({
        tooltip: { trigger: 'axis' },
        xAxis: { type:'category', data: (data.throughput||[]).map((t:any)=>t.date) },
        yAxis: { type:'value' },
        series: [{ data: (data.throughput||[]).map((t:any)=>t.count), type:'line', smooth:true }]
      })
    }
  } finally { loading.value = false }
})

onUnmounted(() => { rankInstance?.dispose(); throughputInstance?.dispose() })
</script>

<style scoped>
.stat-item { text-align:center; padding:8px 0; }
.stat-label { color:#909399; font-size:14px; margin-bottom:8px; }
.stat-value { font-size:28px; font-weight:bold; color:#303133; }
.text-primary { color:#409EFF; }
.text-warning { color:#E6A23C; }
.text-danger { color:#F56C6C; }
</style>