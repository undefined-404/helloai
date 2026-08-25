<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 筛选/操作区（与 ReviewList / SubTaskList 共享模式：标题外置，操作区右侧水平排列） -->
    <div class="page-head">
      <h2 class="page-heading">
        Agent 打卡上班
      </h2>
      <div class="page-actions">
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          class="filter-select"
          @change="load(1)"
        >
          <el-option
            label="在岗"
            value="ACTIVE"
          />
          <el-option
            label="下班"
            value="CLOSED"
          />
          <el-option
            label="超时"
            value="EXPIRED"
          />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索 Agent 名称或会话ID"
          clearable
          class="filter-search"
          :prefix-icon="Search"
          @input="onKeywordInput"
          @clear="reload"
        />
        <el-button
          size="small"
          type="primary"
          @click="load(currentPage)"
        >
          刷新
        </el-button>
      </div>
    </div>

    <!-- 顶部 4 个统计卡片：复用 design-system.css 全局 .stat-tile -->
    <div class="stats-grid ha-stagger-entrance">
      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            在岗 Agent
          </div>
          <div class="stat-tile-icon success">
            <el-icon><CircleCheck /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.active }}
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <span class="dot success" />实时在线
          </span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            超时 Agent
          </div>
          <div class="stat-tile-icon warning">
            <el-icon><Clock /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.expired }}
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <el-icon style="color: var(--ha-warning); font-size: 12px">
              <Warning />
            </el-icon>
            需关注处理
          </span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            已下班 Agent
          </div>
          <div class="stat-tile-icon">
            <el-icon><User /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.closed }}
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <el-icon style="font-size: 12px">
              <Moon />
            </el-icon>
            今日已下班
          </span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            并发配置上限
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><Files /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.totalCap }}
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-meta">
            <el-icon style="font-size: 12px">
              <Files />
            </el-icon>
            覆盖 {{ stats.configuredAgents }} 个 Agent
          </span>
        </div>
      </div>
    </div>

    <!-- 列表卡片 -->
    <el-card
      class="ha-entrance-up"
      style="animation-delay: 80ms"
    >
      <el-table
        v-loading="loading"
        :data="filteredList"
        border
        stripe
        style="width: 100%"
        empty-text="暂无打卡记录"
      >
        <el-table-column
          label="Agent"
          min-width="220"
        >
          <template #default="{ row }">
            <div class="agent-cell">
              <el-avatar
                :size="32"
                :style="{ background: avatarBg(row) }"
                shape="square"
                class="agent-avatar"
              >
                <el-icon><UserFilled /></el-icon>
              </el-avatar>
              <div class="agent-meta">
                <div class="agent-name">
                  {{ row.agentName || '—' }}
                </div>
                <div class="agent-sub">
                  <span class="agent-id">#{{ row.agentId }}</span>
                  <span class="agent-extra">{{ row.sessionId.slice(0, 12) }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="最新状态"
          width="110"
        >
          <template #default="{ row }: { row: DutyAgentLatestResponse }">
            <el-tag
              :type="DUTY_LEASE_STATUS_MAP[row.status]?.type || 'info'"
              size="small"
              effect="light"
            >
              {{ DUTY_LEASE_STATUS_MAP[row.status]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="sessionId"
          label="最新会话"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          label="上班时间"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="续约时间"
          width="170"
        >
          <template #default="{ row }">
            <span
              :class="recentClass(row.lastRenewedAt)"
            >{{ fmtTime(row.lastRenewedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="超时时间"
          width="170"
        >
          <template #default="{ row }">
            <span
              :class="recentClass(row.expiresAt)"
            >{{ fmtTime(row.expiresAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          :width="ACTION.ONE"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              link
              type="primary"
              @click="openHistory(row)"
            >
              更多
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > 0"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        style="margin-top: 16px; text-align: center"
        @current-change="load"
      />
    </el-card>

    <DutyLeaseHistoryDialog
      v-model="historyVisible"
      :agent-id="historyAgentId"
      :agent-name="historyAgentName"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  Search,
  User,
  UserFilled,
  CircleCheck,
  Clock,
  Warning,
  Moon,
  Files
} from '@element-plus/icons-vue'
import { dutyApi } from '@/api/duty'
import { DUTY_LEASE_STATUS_MAP, type DutyAgentLatestResponse, type DutyLeaseStatus } from '@/types/duty'
import type { LongId } from '@/types'
import { fmtTime, ACTION } from '@/utils/tableConfig'
import DutyLeaseHistoryDialog from './components/DutyLeaseHistoryDialog.vue'

const list = ref<DutyAgentLatestResponse[]>([])
const total = ref(0)
const pageSize = ref(20)
const currentPage = ref(1)
const loading = ref(false)
const statusFilter = ref<'' | DutyLeaseStatus>('')
const keyword = ref('')
let searchTimer: ReturnType<typeof setTimeout> | null = null

// 顶部统计：在过滤后的全量列表上聚合（不含关键字过滤，保持筛选一致语义）
// 备注：后端未提供 currentLoad（当前并发占用）字段，本卡只统计 maxConcurrent 配置上限与配置 Agent 数，
//      避免用 leaseCount（历史租约总数）冒充"当前占用"造成拼接错位
const stats = computed(() => {
  const source = list.value
  let active = 0
  let closed = 0
  let expired = 0
  let totalCap = 0
  let configuredAgents = 0
  for (const r of source) {
    if (r.status === 'ACTIVE') active++
    else if (r.status === 'CLOSED') closed++
    else if (r.status === 'EXPIRED') expired++
    if (r.maxConcurrent != null) {
      totalCap += r.maxConcurrent
      configuredAgents++
    }
  }
  return { active, closed, expired, totalCap, configuredAgents }
})

// 时间热度：与当前时间比对，未来 24h 内为 danger（即将超时），
// 过去 24h 内为 warning（近期活动），其余默认 ink 色
function recentClass(t: string | null): string {
  if (!t) return ''
  const ms = Date.parse(t)
  if (Number.isNaN(ms)) return ''
  const diff = ms - Date.now()
  if (diff >= 0 && diff <= 24 * 3600 * 1000) return 'soon'
  if (diff < 0 && diff >= -24 * 3600 * 1000) return 'recent'
  return ''
}

// Agent 头像底色：按状态取色，无 agentName 时回退灰
function avatarBg(row: DutyAgentLatestResponse): string {
  const map: Record<DutyLeaseStatus, string> = {
    ACTIVE: 'color-mix(in srgb, var(--ha-success) 14%, transparent)',
    CLOSED: 'color-mix(in srgb, var(--ha-muted) 14%, transparent)',
    EXPIRED: 'color-mix(in srgb, var(--ha-warning) 14%, transparent)'
  }
  return map[row.status] || 'var(--ha-primary-muted)'
}

// 列表过滤：状态筛选 + 关键词搜索（Agent 名 / 会话ID）
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return list.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (!kw) return true
    return (row.agentName || '').toLowerCase().includes(kw)
      || row.sessionId.toLowerCase().includes(kw)
  })
})

async function load(page = 1) {
  loading.value = true
  currentPage.value = page
  try {
    const res = await dutyApi.listByAgent({ page, size: pageSize.value })
    list.value = res?.list || []
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}

function reload() { load(currentPage.value) }

function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { /* filteredList 由 computed 自动重算 */ }, 200)
}

// ── 单 Agent 历史打卡记录（分页对话框）──
const historyVisible = ref(false)
const historyAgentId = ref<LongId | null>(null)
const historyAgentName = ref<string | null>(null)
function openHistory(row: DutyAgentLatestResponse) {
  historyAgentId.value = row.agentId
  historyAgentName.value = row.agentName
  historyVisible.value = true
}

onMounted(() => load(1))
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--ha-primary);
  letter-spacing: -0.02em;
  margin: 0;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-select { width: 140px; }
.filter-search { width: 260px; }
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
/* 顶都会计卡中性图标：已下班 Agent 用中性灰背景，避免 success/warning/danger 都抢注意力 */
.stat-tile-icon { background: var(--ha-surface-hover); color: var(--ha-muted); }

/* Agent 列表项：方形头像 + 名称 / ID / sessionId 摘要 */
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
.agent-id { color: var(--ha-primary); font-weight: 500; }
.agent-extra {
  font-family: var(--ha-font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}

/* 时间热度：即将超时 danger、近期活动 warning，默认 ink-secondary */
.soon { color: var(--ha-danger); font-weight: 600; font-variant-numeric: tabular-nums; }
.recent { color: var(--ha-warning); font-weight: 600; font-variant-numeric: tabular-nums; }
:deep(.el-table td.el-table__cell) .recent,
:deep(.el-table td.el-table__cell) .soon { font-weight: 600; }

@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .stats-grid { grid-template-columns: 1fr; }
  .filter-select, .filter-search { width: 100%; }
}
</style>