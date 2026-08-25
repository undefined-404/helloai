<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 筛选/操作区（与 SubTaskList 等列表页一致：标题外置，操作区右侧水平排列） -->
    <div class="page-head">
      <h2 class="page-heading">
        审查中心
      </h2>
      <div class="page-actions">
        <el-select
          v-model="resultFilter"
          placeholder="结果筛选"
          clearable
          class="filter-select"
          @change="reload"
        >
          <el-option
            label="全部"
            value=""
          />
          <el-option
            label="通过"
            value="APPROVED"
          />
          <el-option
            label="驳回"
            value="REJECTED"
          />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索子任务ID或问题关键词"
          clearable
          class="filter-search"
          :prefix-icon="Search"
          @input="onKeywordInput"
          @clear="reload"
        />
        <el-button
          size="small"
          type="primary"
          @click="load"
        >
          刷新
        </el-button>
      </div>
    </div>

    <!-- 顶部 4 个统计卡片：与 DutyLeaseList 共享 design-system.css 中的 .stat-tile 全局样式 -->
    <div class="stats-grid ha-stagger-entrance">
      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            总审查数
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><Document /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.total }}
          <span
            v-if="stats.delta"
            class="delta up"
          >
            <el-icon><CaretTop /></el-icon>
            {{ stats.delta }} 本周
          </span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            通过 <span class="accent">(APPROVED)</span>
          </div>
          <div class="stat-tile-icon success">
            <el-icon><CircleCheck /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.approved }}
        </div>
        <div class="stat-tile-extra">
          <div class="stat-tile-bar">
            <div
              class="stat-tile-bar-fill success"
              :style="{ width: stats.approvedPct + '%' }"
            />
          </div>
          <span class="stat-tile-bar-pct">{{ stats.approvedPct.toFixed(1) }}%</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            驳回 <span class="accent">(REJECTED)</span>
          </div>
          <div class="stat-tile-icon danger">
            <el-icon><CircleClose /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.rejected }}
        </div>
        <div class="stat-tile-extra">
          <div class="stat-tile-bar">
            <div
              class="stat-tile-bar-fill danger"
              :style="{ width: stats.rejectedPct + '%' }"
            />
          </div>
          <span class="stat-tile-bar-pct">{{ stats.rejectedPct.toFixed(1) }}%</span>
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            平均评分
          </div>
          <div class="stat-tile-icon warning">
            <el-icon><Star /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.avgScore.toFixed(1) }}<span class="unit">/ 5.0</span>
        </div>
        <div class="stat-tile-extra">
          <span class="stat-tile-stars">
            <el-icon
              v-for="i in 5"
              :key="i"
              :class="{ 'el-icon--empty': i > Math.round(stats.avgScore) }"
            >
              <StarFilled />
            </el-icon>
          </span>
        </div>
      </div>
    </div>

    <!-- 列表卡片 -->
    <el-card
      v-loading="loading"
      class="ha-entrance-up"
      style="animation-delay: 80ms"
    >
      <el-table
        :data="paginatedList"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="子任务"
          min-width="140"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              link
              type="primary"
              @click="goSubTask(row.subTaskId)"
            >
              #{{ row.subTaskId }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column
          label="结果"
          width="100"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.result==='APPROVED' ? 'success' : 'danger'"
              size="small"
              effect="light"
            >
              {{ row.result === 'APPROVED' ? 'APPROVE' : 'REJECTED' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="评分"
          width="70"
          align="center"
        >
          <template #default="{ row }">
            <span
              :class="['score-num', row.score >= 3 ? 'good' : 'bad']"
            >{{ row.score }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="问题"
          min-width="220"
        >
          <template #default="{ row }">
            <span class="multi-line">{{ row.issues || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="评价"
          min-width="220"
        >
          <template #default="{ row }">
            <span class="multi-line">{{ row.comment || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="轮次"
          width="70"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              type="info"
              effect="plain"
            >
              {{ row.round }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="时间"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.createTime) }}
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
              @click="goSubTask(row.subTaskId)"
            >
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="filteredList.length > 0"
        background
        layout="prev, pager, next, total"
        :total="filteredList.length"
        :page-size="pageSize"
        :current-page="currentPage"
        style="margin-top: 16px; text-align: center"
        @current-change="onPageChange"
      />
      <el-empty
        v-if="!filteredList.length && !loading"
        description="暂无审查记录"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Search,
  Document,
  CircleCheck,
  CircleClose,
  Star,
  StarFilled,
  CaretTop
} from '@element-plus/icons-vue'
import { reviewApi } from '@/api/review'
import { fmtTime, ACTION } from '@/utils/tableConfig'
import type { ReviewRecord } from '@/types'

const router = useRouter()
const list = ref<ReviewRecord[]>([])
const loading = ref(false)
const resultFilter = ref<'' | 'APPROVED' | 'REJECTED'>('')
const keyword = ref('')
// 客户端分页：后端 /api/reviews 未返 PageResult，全量拉取后在前端切片
// 这样避免后端契约改动，亦不破坏 SKILL.md 中外部 Agent 的全量契约
const pageSize = ref(20)
const currentPage = ref(1)
let searchTimer: ReturnType<typeof setTimeout> | null = null

// 顶部统计：根据过滤后列表实时计算，避免与表格数据脱节
const stats = computed(() => {
  const source = list.value
  const total = source.length
  const approved = source.filter(r => r.result === 'APPROVED').length
  const rejected = source.filter(r => r.result === 'REJECTED').length
  const scoresArr = source.map(r => r.score).filter(s => typeof s === 'number')
  const avgScore = scoresArr.length
    ? scoresArr.reduce((a, b) => a + b, 0) / scoresArr.length
    : 0
  // 通过率 / 驳回率：分母为 approved + rejected，避免 APPROVED/REJECTED 之外的记录稀释比例
  const decided = approved + rejected
  const approvedPct = decided ? (approved / decided) * 100 : 0
  const rejectedPct = decided ? (rejected / decided) * 100 : 0
  // 本周新增（最近 7 天）作为「+N 本周」增量展示
  const now = Date.now()
  const weekAgo = now - 7 * 24 * 3600 * 1000
  const delta = source.filter(r => {
    const t = Date.parse(r.createTime)
    return !Number.isNaN(t) && t >= weekAgo && t <= now
  }).length
  return { total, approved, rejected, avgScore, approvedPct, rejectedPct, delta }
})

// 列表过滤：结果筛选 + 关键词搜索（子任务ID / 问题文本 / 评价文本）
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return list.value.filter(row => {
    if (resultFilter.value && row.result !== resultFilter.value) return false
    if (!kw) return true
    return String(row.subTaskId).toLowerCase().includes(kw)
      || (row.issues || '').toLowerCase().includes(kw)
      || (row.comment || '').toLowerCase().includes(kw)
  })
})

// 客户端分页切片：filteredList 变化（筛选/搜索触发）时 currentPage 重置为 1
// 避免过滤后总页数缩小但页码停留在旧页导致越界
const paginatedList = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})

// 分页变化回调
function onPageChange(p: number) {
  currentPage.value = p
}

// 防御边界：当前页越界（总页数缩小、比如最后一页被过滤掉）时回退到末页，
// 避免 el-table 渲染空集 + el-pagination 高亮不存在的页码
watch(filteredList, (list) => {
  const totalPages = Math.max(1, Math.ceil(list.length / pageSize.value))
  if (currentPage.value > totalPages) currentPage.value = totalPages
})

async function load() {
  loading.value = true
  currentPage.value = 1
  try {
    list.value = await reviewApi.list()
  } finally {
    loading.value = false
  }
}

function reload() { load() }

// 关键词输入防抖：避免每键击触发 list 重新计算 + 过滤
function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    // 关键词变化后过滤结果可能减少页数，重置到第一页避免越界
    currentPage.value = 1
  }, 200)
}

function goSubTask(id: string | number) {
  router.push('/sub-tasks/' + id)
}

onMounted(() => load())
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
.multi-line {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--ha-ink-secondary);
  line-height: 1.5;
}
.score-num {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.score-num.good { color: var(--ha-success); }
.score-num.bad { color: var(--ha-danger); }
@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .stats-grid { grid-template-columns: 1fr; }
  .filter-select, .filter-search { width: 100%; }
}
</style>