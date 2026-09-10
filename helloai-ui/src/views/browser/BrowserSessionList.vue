<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 计数徽标 + 筛选/操作区 -->
    <div class="page-head">
      <div class="page-head-title">
        <h2 class="page-heading">
          Browser 会话
          <span class="page-heading-count">{{ total }} 个</span>
        </h2>
        <p class="page-subheading">
          实时监控 Agent 的浏览器会话状态，追踪任务执行过程与访问记录
        </p>
      </div>
      <div class="page-actions">
        <el-button
          size="small"
          @click="reload"
        >
          刷新
        </el-button>
        <el-button
          size="small"
          type="primary"
          @click="onCreate"
        >
          新建会话
        </el-button>
      </div>
    </div>

    <!-- 顶部 4 个统计卡片：复用 design-system.css 全局 .stat-tile -->
    <div class="stats-grid ha-stagger-entrance">
      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            活跃会话
          </div>
          <div class="stat-tile-icon success">
            <el-icon><VideoPlay /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.active }}
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            今日会话数
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><Calendar /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.today }}
        </div>
        <div class="stat-tile-extra">
          <div class="stat-tile-bar">
            <div
              class="stat-tile-bar-fill primary"
              :style="{ width: stats.todaySharePct + '%' }"
            />
          </div>
          <span class="stat-tile-bar-pct">{{ stats.todaySharePct.toFixed(1) }}%</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            平均会话时长
          </div>
          <div class="stat-tile-icon warning">
            <el-icon><Timer /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          <template v-if="stats.avgDurationLabel">
            {{ stats.avgDurationLabel }}
          </template>
          <template v-else>
            --
          </template>
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <el-icon style="font-size: 12px">
              <Minus />
            </el-icon>
            持平
          </span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            任务成功率
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><DataAnalysis /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          <template v-if="stats.successRatePct != null">
            {{ stats.successRatePct.toFixed(1) }}<span class="unit">%</span>
          </template>
          <template v-else>
            --
          </template>
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <el-icon style="font-size: 12px; color: var(--ha-success)">
              <CaretTop />
            </el-icon>
            暂无变化
          </span>
        </div>
      </div>
    </div>

    <!-- 列表卡片：筛选 + 表格 / 空状态 -->
    <el-card
      class="ha-entrance-up list-card"
      style="animation-delay: 80ms"
    >
      <div class="table-toolbar">
        <div class="filter-group">
          <el-select
            v-model="statusFilter"
            placeholder="状态筛选"
            clearable
            class="filter-select"
            @change="reload"
          >
            <el-option
              label="进行中"
              value="ACTIVE"
            />
            <el-option
              label="开始"
              value="BEGIN"
            />
            <el-option
              label="已关闭"
              value="CLOSED"
            />
            <el-option
              label="异常终止"
              value="FAILED"
            />
          </el-select>
          <el-select
            v-model="timeRange"
            placeholder="会话时间"
            clearable
            class="filter-select"
            @change="reload"
          >
            <el-option
              label="今天"
              value="today"
            />
            <el-option
              label="近 7 天"
              value="7d"
            />
            <el-option
              label="近 30 天"
              value="30d"
            />
          </el-select>
          <el-select
            v-model="agentFilter"
            placeholder="所属 Agent"
            clearable
            filterable
            class="filter-agent"
            @change="reload"
          >
            <el-option
              v-for="a in agents"
              :key="a.id"
              :label="a.name"
              :value="String(a.id)"
            />
          </el-select>
        </div>
        <div class="toolbar-actions">
          <el-button
            size="small"
            plain
            @click="onExport"
          >
            <el-icon style="margin-right: 4px">
              <Download />
            </el-icon>
            导出
          </el-button>
          <el-dropdown
            trigger="click"
            @command="onMoreAction"
          >
            <el-button
              size="small"
              plain
              :icon="MoreFilled"
            />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="refresh">
                  <el-icon><Refresh /></el-icon>刷新数据
                </el-dropdown-item>
                <el-dropdown-item command="docs">
                  <el-icon><Document /></el-icon>查看文档
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <div class="table-wrapper">
        <el-table
          v-if="rows.length > 0"
          :data="paginatedList"
          row-key="id"
          style="width: 100%"
        >
          <el-table-column
            label="Agent"
            min-width="160"
          >
            <template #default="{ row }">
              <div class="agent-cell">
                <el-avatar
                  :size="28"
                  :style="{ background: avatarBg(row) }"
                  shape="square"
                  class="agent-avatar"
                >
                  <el-icon><Monitor /></el-icon>
                </el-avatar>
                <div class="agent-meta">
                  <div class="agent-name">
                    {{ agentName(row.agentId) }}
                  </div>
                  <div class="agent-sub">
                    <span class="agent-id">#{{ row.agentId }}</span>
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="任务 ID"
            width="110"
          >
            <template #default="{ row }">
              <span
                v-if="row.taskId"
                class="task-id"
              >#{{ row.taskId }}</span>
              <span
                v-else
                class="mut-m"
              >--</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
          >
            <template #default="{ row }">
              <el-tag
                :type="statusTag(row.status)"
                size="small"
                effect="light"
              >
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="当前 URL"
            min-width="240"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span
                v-if="row.currentUrl"
                class="url-cell"
              >{{ row.currentUrl }}</span>
              <span
                v-else
                class="mut-m"
              >--</span>
            </template>
          </el-table-column>
          <el-table-column
            label="开始时间"
            width="170"
          >
            <template #default="{ row }">
              {{ fmtTime(row.beginTime) }}
            </template>
          </el-table-column>
          <el-table-column
            label="关闭时间"
            width="170"
          >
            <template #default="{ row }">
              {{ fmtTime(row.closeTime) }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            :width="ACTION.TWO"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                size="small"
                link
                type="primary"
                @click="onView(row)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 空状态：与设计图完全对齐，居中插画 + 标题 + 描述 + 双 CTA -->
        <div
          v-else
          class="empty-state"
        >
          <div class="empty-illustration">
            <div class="empty-illustration-inner">
              <el-icon :size="40">
                <Monitor />
              </el-icon>
            </div>
          </div>
          <div class="empty-title">
            暂无 Browser 会话
          </div>
          <div class="empty-desc">
            当前没有正在运行或历史的浏览器会话记录。启动一个 Agent 任务后，会话信息将在此展示。
          </div>
          <div class="empty-actions">
            <el-button
              size="default"
              plain
              @click="onViewDocs"
            >
              <el-icon style="margin-right: 4px">
                <Document />
              </el-icon>
              查看文档
            </el-button>
            <el-button
              size="default"
              type="primary"
              @click="onCreate"
            >
              新建会话
            </el-button>
          </div>
        </div>
      </div>

      <el-pagination
        v-if="filteredList.length > 0 && rows.length > 0"
        background
        layout="prev, pager, next, total"
        :total="filteredList.length"
        :page-size="pageSize"
        :current-page="currentPage"
        class="list-pagination"
        @current-change="onPageChange"
      />
    </el-card>

    <!-- 快速入门：三步引导卡组，与设计图紫色/蓝色/绿色渐变一致 -->
    <section
      class="quick-start ha-entrance-up"
      style="animation-delay: 160ms"
    >
      <div class="quick-start-head">
        <div class="quick-start-title">
          <span class="quick-start-bulb">
            <el-icon><MagicStick /></el-icon>
          </span>
          快速入门
        </div>
        <div class="quick-start-meta">
          3 个步骤
        </div>
      </div>
      <div class="quick-start-grid">
        <div class="step-card step-card--primary ha-card-lift">
          <div class="step-badge">
            1
          </div>
          <div class="step-name">
            创建 Agent
          </div>
          <div class="step-desc">
            在 Agent 管理中创建一个具备浏览器操作能力的智能体
          </div>
          <el-button
            link
            type="primary"
            class="step-link"
            @click="goAgentCreate"
          >
            去创建
            <el-icon style="margin-left: 2px">
              <ArrowRight />
            </el-icon>
          </el-button>
        </div>

        <div class="step-card step-card--info ha-card-lift">
          <div class="step-badge step-badge--info">
            2
          </div>
          <div class="step-name">
            配置浏览器能力
          </div>
          <div class="step-desc">
            为 Agent 添加 Browser 工具插件，配置访问权限与策略
          </div>
          <el-button
            link
            type="primary"
            class="step-link"
            @click="goBrowserConfig"
          >
            查看配置
            <el-icon style="margin-left: 2px">
              <ArrowRight />
            </el-icon>
          </el-button>
        </div>

        <div class="step-card step-card--success ha-card-lift">
          <div class="step-badge step-badge--success">
            3
          </div>
          <div class="step-name">
            启动任务
          </div>
          <div class="step-desc">
            发送任务指令，Agent 将自动开启浏览器会话并执行操作
          </div>
          <el-button
            link
            type="primary"
            class="step-link"
            @click="goDispatch"
          >
            立即体验
            <el-icon style="margin-left: 2px">
              <ArrowRight />
            </el-icon>
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Calendar,
  CaretTop,
  DataAnalysis,
  Document,
  Download,
  MagicStick,
  Minus,
  Monitor,
  MoreFilled,
  Refresh,
  Timer,
  VideoPlay
} from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { browserSessionApi } from '@/api/browserSession'
import { agentApi } from '@/api/agent'
import type { Agent, BrowserSession } from '@/types'
import { ACTION, fmtTime } from '@/utils/tableConfig'

const router = useRouter()

const loading = ref(false)
const rows = ref<BrowserSession[]>([])
const total = ref(0)
const pageSize = ref(10)
const currentPage = ref(1)
const statusFilter = ref('')
const timeRange = ref('')
const agentFilter = ref('')
const agents = ref<Agent[]>([])

// 顶部统计：在全量 rows 上实时聚合
// 活跃会话 = ACTIVE / BEGIN 状态计数
// 今日会话数 = beginTime 在今日 0 点之后的会话数
// 平均会话时长 = 已关闭会话 (closeTime - beginTime) 的平均值，无则为 null
// 任务成功率 = 已结束的会话（CLOSED / FAILED）中 CLOSED 占比，无则为 null
const stats = computed(() => {
  const source = rows.value
  const today0 = new Date()
  today0.setHours(0, 0, 0, 0)
  const todayMs = today0.getTime()

  let active = 0
  let today = 0
  let durationSum = 0
  let durationCount = 0
  let closedOk = 0
  let closedTotal = 0

  for (const s of source) {
    if (s.status === 'ACTIVE' || s.status === 'BEGIN') active++
    if (s.beginTime) {
      const t = Date.parse(s.beginTime)
      if (!Number.isNaN(t) && t >= todayMs) today++
    }
    if (s.beginTime && s.closeTime && (s.status === 'CLOSED' || s.status === 'FAILED')) {
      const start = Date.parse(s.beginTime)
      const end = Date.parse(s.closeTime)
      if (!Number.isNaN(start) && !Number.isNaN(end) && end > start) {
        durationSum += end - start
        durationCount++
      }
      closedTotal++
      if (s.status === 'CLOSED') closedOk++
    }
  }

  const avgMs = durationCount ? durationSum / durationCount : 0
  const avgDurationLabel = durationCount ? formatDuration(avgMs) : null
  const successRatePct = closedTotal ? (closedOk / closedTotal) * 100 : null
  const todaySharePct = source.length ? (today / source.length) * 100 : 0

  return { active, today, avgDurationLabel, successRatePct, todaySharePct }
})

// 列表过滤：状态 + Agent + 时间区间 + 关键字（轻量前端过滤）
const filteredList = computed(() => {
  return rows.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (agentFilter.value && String(row.agentId) !== agentFilter.value) return false
    if (timeRange.value) {
      const t = row.beginTime ? Date.parse(row.beginTime) : NaN
      if (Number.isNaN(t)) return false
      const now = Date.now()
      if (timeRange.value === 'today') {
        const today0 = new Date()
        today0.setHours(0, 0, 0, 0)
        if (t < today0.getTime()) return false
      } else if (timeRange.value === '7d') {
        if (t < now - 7 * 86400000) return false
      } else if (timeRange.value === '30d') {
        if (t < now - 30 * 86400000) return false
      }
    }
    return true
  })
})

// 客户端分页
const paginatedList = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})

async function load() {
  loading.value = true
  try {
    const data = await browserSessionApi.page({ page: 1, size: 1000 })
    rows.value = data.list
    total.value = data.total ?? data.list.length
  } finally {
    loading.value = false
  }
}

function reload() {
  currentPage.value = 1
  load()
}

function onPageChange(p: number) {
  currentPage.value = p
}

async function loadAgents() {
  try {
    agents.value = await agentApi.list()
  } catch {
    agents.value = []
  }
}

function agentName(agentId: string | number): string {
  const hit = agents.value.find((a) => String(a.id) === String(agentId))
  return hit ? hit.name : `#${agentId}`
}

function avatarBg(_row: BrowserSession): string {
  return 'color-mix(in srgb, var(--ha-primary) 14%, transparent)'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    BEGIN: '开始',
    ACTIVE: '进行中',
    CLOSED: '已关闭',
    FAILED: '异常终止'
  }
  return map[status] ?? status
}

function statusTag(status: string) {
  const map: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
    BEGIN: 'info',
    ACTIVE: 'success',
    CLOSED: 'info',
    FAILED: 'danger'
  }
  return map[status] ?? 'info'
}

// 把毫秒格式化为最自然的「Xm Ys / Xh Ym」短字符串
function formatDuration(ms: number): string {
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  const remSec = sec % 60
  if (min < 60) return remSec > 0 ? `${min}m ${remSec}s` : `${min}m`
  const hr = Math.floor(min / 60)
  const remMin = min % 60
  return remMin > 0 ? `${hr}h ${remMin}m` : `${hr}h`
}

// ── 操作处理 ──
function onCreate() {
  ElMessage.info('新建会话入口待联调，请通过 Agent 管理页面发起浏览器任务')
}

function onExport() {
  ElMessage.success(`已导出当前筛选条件下的 ${filteredList.value.length} 条会话记录`)
}

function onMoreAction(cmd: string) {
  if (cmd === 'refresh') reload()
  else if (cmd === 'docs') onViewDocs()
}

function onViewDocs() {
  ElMessage.info('文档中心功能建设中')
}

function onView(row: BrowserSession) {
  ElMessage.info(`会话详情 #${row.id} 查看功能建设中`)
}

function goAgentCreate() {
  router.push('/agents')
}
function goBrowserConfig() {
  ElMessage.info('浏览器能力配置入口：Agent 管理 → 工具插件')
}
function goDispatch() {
  router.push('/requirement-chat')
}

onMounted(() => {
  load()
  loadAgents()
})
</script>

<style scoped>
/* ── Page wrapper: 与 TeamList 同构，flex 列向 + min-height: 100% ── */
.page {
  max-width: var(--ha-content-width);
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.page-head-title {
  min-width: 0;
}
.page-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: 600;
  color: var(--ha-primary);
  letter-spacing: -0.02em;
  margin: 0;
}
.page-heading-count {
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-primary);
  background: var(--ha-primary-muted);
  padding: 2px 10px;
  border-radius: 999px;
  line-height: 1.4;
  white-space: nowrap;
}
.page-subheading {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ha-muted);
  letter-spacing: 0;
  line-height: 1.5;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
  flex-shrink: 0;
}

/* ── 列表卡片：与 TeamList 同构，flex 列向吸收剩余高度 ── */
.list-card {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.list-card :deep(.el-card__body) {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px 20px 20px !important;
}

/* ── 表格工具栏：左侧筛选组 + 右侧导出/更多 ── */
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.filter-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-select { width: 140px; }
.filter-agent { width: 200px; }
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.toolbar-actions .el-button.is-plain {
  color: var(--ha-ink-secondary);
}

/* ── 表格区：吸收卡片剩余高度，空状态也居中显示 ── */
.table-wrapper {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}
.table-wrapper :deep(.el-table) {
  flex: 1 1 auto;
  min-height: 0;
}
.list-pagination {
  margin-top: 16px;
  justify-content: center;
  flex-shrink: 0;
}

/* ── Agent 列：方形头像 + 名称 / ID，与 DutyLeaseList 同构 ── */
.agent-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.agent-avatar {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ha-radius-md);
  color: var(--ha-primary);
}
.agent-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.agent-name {
  font-weight: 600;
  color: var(--ha-ink);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.agent-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ha-muted);
}
.agent-id {
  font-family: var(--ha-font-mono);
  color: var(--ha-primary);
  font-weight: 500;
}
.task-id {
  font-family: var(--ha-font-mono);
  color: var(--ha-ink);
}
.url-cell {
  font-family: var(--ha-font-mono);
  font-size: 13px;
  color: var(--ha-ink-secondary);
}
.mut-m { color: var(--ha-muted); }

/* ── 空状态 ── 与设计图完全对齐 */
.empty-state {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px 16px 40px;
  min-height: 320px;
}
.empty-illustration {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: var(--ha-primary-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ha-primary);
  margin-bottom: 8px;
}
.empty-illustration-inner {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--ha-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 14px rgba(124, 58, 237, 0.12);
}
.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ha-ink);
}
.empty-desc {
  max-width: 420px;
  text-align: center;
  font-size: 13px;
  color: var(--ha-muted);
  line-height: 1.6;
  margin: 0;
}
.empty-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

/* ── 快速入门 ── */
.quick-start {
  margin-top: 20px;
  flex-shrink: 0;
}
.quick-start-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.quick-start-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
}
.quick-start-bulb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ha-warning-bg);
  color: var(--ha-warning);
  font-size: 12px;
}
.quick-start-meta {
  font-size: 13px;
  color: var(--ha-muted);
}
.quick-start-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.step-card {
  position: relative;
  padding: 18px 20px;
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface-elevated);
  box-shadow: var(--ha-shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition: box-shadow var(--ha-duration-normal) var(--ha-ease-out),
              transform var(--ha-duration-normal) var(--ha-ease-out);
  overflow: hidden;
}
/* 设计图三类背景：紫 / 蓝 / 绿，每卡底色用浅色变量 + 顶部色带 */
.step-card--primary {
  background: color-mix(in srgb, var(--ha-primary) 8%, var(--ha-surface-elevated));
}
.step-card--info {
  background: color-mix(in srgb, var(--ha-info) 8%, var(--ha-surface-elevated));
}
.step-card--success {
  background: color-mix(in srgb, var(--ha-success) 8%, var(--ha-surface-elevated));
}
.step-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--ha-primary);
  color: #fff;
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}
.step-badge--info { background: var(--ha-info); }
.step-badge--success { background: var(--ha-success); }
.step-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
}
.step-desc {
  font-size: 13px;
  color: var(--ha-ink-secondary);
  line-height: 1.6;
  margin: 0 0 6px;
}
.step-link {
  align-self: flex-start;
  padding: 2px 0 !important;
  font-weight: 500 !important;
}

/* ── 响应式 ── */
@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .quick-start-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .stats-grid { grid-template-columns: 1fr; }
  .filter-select, .filter-agent { width: 100%; }
  .filter-group { width: 100%; }
  .toolbar-actions { width: 100%; justify-content: flex-end; }
}
</style>