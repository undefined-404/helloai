<template>
  <el-empty
    v-if="!subTasks.length"
    description="暂无子任务"
  />
  <!-- 外层横向滚动包裹：批次过多时出现滚动条；表格本体宽度与列数呈正比 -->
  <div
    v-else
    ref="wrapperRef"
    class="dag-wrapper"
  >
    <div
      ref="chartRef"
      class="dag-chart"
      :style="{ height: chartHeight + 'px', width: chartWidth + 'px' }"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { SUB_TASK_STATUS_MAP } from '@/types'
import type { SubTask } from '@/types'
import { computeDagLayers, reduceDependencies } from '@/utils/subTaskDag'
import { fmtTime } from '@/utils/tableConfig'

// 分层流水线 DAG 视图：横轴 = 执行批次（Kahn 拓扑分层，同批可并行），
// 节点按状态着色，连线表示 dependsOn 依赖，点击节点跳详情（由父组件处理）。
const props = defineProps<{ subTasks: SubTask[] }>()
const emit = defineEmits<{ 'node-click': [id: string] }>()

const chartRef = ref<HTMLDivElement>()
const wrapperRef = ref<HTMLDivElement>()
let chartInstance: echarts.ECharts | null = null

// 节点尺寸 / 箭头尺寸（px），自绘边的端点裁剪依赖这两组常量
const NODE_W = 128
const NODE_H = 40
const ARROW_W = 9
const ARROW_H = 8

// 每个批次（Kahn 分层一列）占用的固定像素宽度：含节点 + 两侧间隙 + 贝塞尔曲线拉伸空间。
// 表格总宽 = 列数 × COL_GAP + 拖动双侧 padding；容器超此宽时出现横向滚动条。
const COL_GAP = 180
const CHART_MIN_WIDTH = 800
const CHART_PADDING = 32

// 状态 → 节点专属色（一对一，避免 el-tag type 归并导致不同状态同色不可辨）
const STATUS_COLOR: Record<SubTask['status'], string> = {
  PENDING:     '#909399', // 待分配：灰
  ASSIGNED:    '#79bbff', // 已分配：浅蓝
  IN_PROGRESS: '#409eff', // 执行中：蓝
  PAUSED:      '#9a7fd1', // 已暂停：紫
  REVIEW:      '#e6a23c', // 审查中：橙
  DONE:        '#67c23a', // 已完成：绿
  REWORK:      '#f89898', // 返工：浅红
  BLOCKED:     '#f56c6c', // 阻塞：红
  CANCELLED:   '#73767a', // 已取消：暗灰
  DEAD_LETTER: '#c45656', // 死信待人工：深红
  PENDING_PLAN_REVIEW: '#ebb563' // 草案待审：浅橙
}

// 活跃态：其入边渲染为流动虚线，提示「上游 → 该任务」的链路正在推进
const ACTIVE_EDGE_STATUS = new Set<SubTask['status']>(['ASSIGNED', 'IN_PROGRESS', 'REVIEW'])

const layers = computed(() => computeDagLayers(props.subTasks))

// 拓扑正序全局序号（#1..#N），与草案审阅弹窗的序号语义一致
const seqMap = computed(() => {
  const map = new Map<string, number>()
  layers.value.flat().forEach((s, i) => map.set(String(s.id), i + 1))
  return map
})

// 高度随最大批次节点数自适应，避免节点重叠
const chartHeight = computed(() => {
  const maxRows = Math.max(1, ...layers.value.map(l => l.length))
  return Math.max(240, maxRows * 76 + 70)
})

// 宽度按列数 × 固定列宽撑开（10 批 = 1800px）；超出视口由 wrapper 出现横向滚动条。
// 最小 800px 保证极少数列时仍能铺满视口，节点不会富余到拖出同心边界。
const chartWidth = computed(() => {
  const cols = Math.max(1, layers.value.length)
  return Math.max(CHART_MIN_WIDTH, cols * COL_GAP + CHART_PADDING)
})

function statusColor(status: SubTask['status']): string {
  return STATUS_COLOR[status] || STATUS_COLOR.PENDING
}

function truncate(text: string, max: number): string {
  if (!text) return '-'
  return text.length > max ? text.slice(0, max) + '…' : text
}

function buildOption(): echarts.EChartsOption {
  const layerList = layers.value
  const maxRows = Math.max(1, ...layerList.map(l => l.length))
  const idSet = new Set(props.subTasks.map(s => String(s.id)))
  const byId = new Map(props.subTasks.map(s => [String(s.id), s]))

  // 节点：x = 批次序（category 轴），y = 批内行号（居中对齐）
  const posOf = new Map<string, [number, number]>()
  const nodes = layerList.flatMap((layer, li) =>
    layer.map((s, yi) => {
      const pos: [number, number] = [li, yi - (layer.length - 1) / 2]
      posOf.set(String(s.id), pos)
      return {
        id: String(s.id),
        name: '#' + seqMap.value.get(String(s.id)) + ' ' + truncate(s.title, 8),
        value: pos,
        itemStyle: { color: statusColor(s.status) }
      }
    })
  )
  // 边：由 custom series 自绘（graph 内置连线按圆形半径裁剪端点，矩形节点会盖住箭头）。
  // 画图用传递归约后的依赖（被更长路径覆盖的直连边不画，更像流程图）；tooltip 仍展示完整直接依赖。
  // value = [源x, 源y, 目标x, 目标y]；active = 目标节点处于活跃态（入边渲染为流动虚线）
  const reducedDeps = reduceDependencies(props.subTasks)
  const edges = props.subTasks.flatMap(s =>
    (reducedDeps.get(String(s.id)) || [])
      .map(d => {
        const sp = posOf.get(d)!
        const tp = posOf.get(String(s.id))!
        const active = ACTIVE_EDGE_STATUS.has(s.status)
        return {
          value: [sp[0], sp[1], tp[0], tp[1]],
          active,
          // 活跃边用目标节点状态色，普通边用中性深灰（比 #c0c4cc 明显更跳）
          color: active ? statusColor(s.status) : '#909399'
        }
      })
  )

  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params: any) => {
        if (params.dataType !== 'node') return ''
        const s = byId.get(String(params.data.id))
        if (!s) return ''
        const meta = SUB_TASK_STATUS_MAP[s.status]
        // DONE 是终态，最后一次更新时间即完成时刻
        const doneAt = s.status === 'DONE' && s.updateTime ? '（' + fmtTime(s.updateTime).slice(11) + '）' : ''
        const deps = (s.dependsOn || []).map(String).filter(d => idSet.has(d))
          .map(d => '#' + seqMap.value.get(d)).join('、')
        return [
          '<b>#' + seqMap.value.get(String(s.id)) + ' ' + s.title + '</b>',
          '状态：' + (meta?.label || s.status) + doneAt,
          '负责人：' + (s.assignedAgentName || '-'),
          '前置依赖：' + (deps || '无')
        ].join('<br/>')
      }
    },
    grid: { left: 16, right: 16, top: 36, bottom: 16, containLabel: true },
    xAxis: {
      type: 'category',
      position: 'top',
      data: layerList.map((_, i) => '第 ' + (i + 1) + ' 批'),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { fontSize: 12, color: '#909399', fontWeight: 'bold' },
      boundaryGap: true
    },
    yAxis: {
      type: 'value',
      show: false,
      inverse: true,
      min: -(maxRows - 1) / 2 - 0.7,
      max: (maxRows - 1) / 2 + 0.7
    },
    series: [
      // 自绘依赖边：贝塞尔曲线从源节点右缘到目标节点左缘外侧，箭头尖端刚好触达节点边缘不被遮挡
      {
        type: 'custom',
        coordinateSystem: 'cartesian2d',
        silent: true,
        z: 1,
        renderItem: (params: any, api: any) => {
          const p1 = api.coord([api.value(0), api.value(1)])
          const p2 = api.coord([api.value(2), api.value(3)])
          const edge = edges[params.dataIndex]
          const x1 = p1[0] + NODE_W / 2
          const y1 = p1[1]
          // 箭头尖端停在目标节点左缘外 2px；连线止于箭头底边，避免线头穿透箭头
          const tipX = p2[0] - NODE_W / 2 - 2
          const y2 = p2[1]
          const x2 = tipX - ARROW_W
          const dx = Math.max(24, (x2 - x1) * 0.45)
          const line: any = {
            type: 'bezierCurve',
            shape: { x1, y1, x2, y2, cpx1: x1 + dx, cpy1: y1, cpx2: x2 - dx, cpy2: y2 },
            style: {
              stroke: edge.color, fill: null, lineWidth: edge.active ? 2 : 1.8,
              opacity: 0.95, lineDash: edge.active ? [6, 5] : null
            }
          }
          if (edge.active) {
            // 流动虚线（跑马灯）：lineDashOffset 循环递减 = 虚线段向目标方向流动
            line.keyframeAnimation = {
              duration: 700,
              loop: true,
              keyframes: [
                { percent: 0, style: { lineDashOffset: 0 } },
                { percent: 1, style: { lineDashOffset: -11 } }
              ]
            }
          }
          return {
            type: 'group',
            children: [line, {
              type: 'polygon',
              shape: {
                points: [
                  [tipX, y2],
                  [tipX - ARROW_W, y2 - ARROW_H / 2],
                  [tipX - ARROW_W, y2 + ARROW_H / 2]
                ]
              },
              style: { fill: edge.color, opacity: 0.95 }
            }]
          }
        },
        data: edges
      },
      // 节点层
      {
        type: 'graph',
        coordinateSystem: 'cartesian2d',
        z: 2,
        symbol: 'roundRect',
        symbolSize: [NODE_W, NODE_H],
        label: { show: true, color: '#fff', fontSize: 12, overflow: 'truncate', width: 116 },
        emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0, 0, 0, 0.35)' } },
        data: nodes,
        animationDuration: 500
      }
    ]
  }
}

async function render() {
  if (!props.subTasks.length) {
    chartInstance?.dispose()
    chartInstance = null
    return
  }
  await nextTick()
  if (!chartRef.value) return
  if (!chartInstance) chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption(buildOption(), true)
  chartInstance.off('click')
  chartInstance.on('click', (params: any) => {
    if (params.dataType === 'node' && params.data?.id) emit('node-click', String(params.data.id))
  })
  chartInstance.resize()
}

function handleResize() { chartInstance?.resize() }

watch(() => props.subTasks, render, { deep: true })
// 高度变化后容器尺寸更新需要 resize（nextTick 等 DOM 应用新高度）
watch(chartHeight, async () => { await nextTick(); chartInstance?.resize() })
// 宽度变化后同样需要 resize（列数变化驱动滚动场景下画布不需要重算，只需重新适应容器）
watch(chartWidth, async () => { await nextTick(); chartInstance?.resize() })

onMounted(() => {
  render()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<style scoped>
/* 外层包裹：横向滚动；表格宽度由 inline style 撑开，超出视口自动出滚动条 */
.dag-wrapper {
  width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  /* 滚动条薄一些避免遮挡节点 */
  scrollbar-width: thin;
}
/* WebKit 滚动条样式（被淹没时不易挡住图表主体） */
.dag-wrapper::-webkit-scrollbar { height: 8px; }
.dag-wrapper::-webkit-scrollbar-thumb { background: #c0c4cc; border-radius: 4px; }
.dag-wrapper::-webkit-scrollbar-track { background: transparent; }
.dag-chart { width: 100%; }
</style>
