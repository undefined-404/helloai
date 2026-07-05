<template>
  <div class="page" v-loading="loading">
    <!-- 返回 -->
    <div class="back-bar">
      <el-button text :icon="ArrowLeft" @click="$router.push('/agents')">返回列表</el-button>
    </div>

    <template v-if="agent">
      <!-- 基本信息卡片 -->
      <div class="detail-card header-card">
        <div class="header-top">
          <div class="header-info">
            <h2 class="detail-name">{{ agent.name }}</h2>
            <p class="detail-desc">{{ agent.description || agent.remark || '暂无描述' }}</p>
            <div class="detail-tags">
              <span class="tag-role" :style="roleStyle">{{ roleLabel(agent.role) }}</span>
              <el-tag :type="agent.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ agent.status === 'ACTIVE' ? '活跃' : '已禁用' }}
              </el-tag>
            </div>
          </div>
        </div>
      </div>

      <!-- 统计 -->
      <div class="stats-row">
        <div class="stat-mini">
          <span class="stat-mini-label">总积分</span>
          <span class="stat-mini-value">{{ agent.totalScore || 0 }}</span>
        </div>
        <div class="stat-mini">
          <span class="stat-mini-label">排名</span>
          <span class="stat-mini-value">#{{ agent.rank || '-' }} / {{ agent.totalAgents || 0 }}</span>
        </div>
        <div class="stat-mini">
          <span class="stat-mini-label">奖励 / 惩罚</span>
          <span class="stat-mini-value">
            <span style="color:var(--ha-success)">+{{ agent.rewardCount || 0 }}</span>
            <span style="margin:0 4px;color:var(--ha-muted)">/</span>
            <span style="color:var(--ha-danger)">-{{ agent.penaltyCount || 0 }}</span>
          </span>
        </div>
      </div>

      <!-- 工作量 + 时间线 -->
      <div class="two-col">
        <div class="detail-card">
          <div class="card-subtitle">
            工作量
            <el-button size="small" text style="margin-left:auto" @click="$router.push(`/sub-tasks?agent=${agent.id}`)">
              查看子任务 →
            </el-button>
          </div>
          <div class="workload-grid">
            <div class="wl-item"><span class="wl-num">{{ agent.assignedCount || 0 }}</span><span class="wl-label">待办</span></div>
            <div class="wl-item"><span class="wl-num">{{ agent.inProgressCount || 0 }}</span><span class="wl-label">执行中</span></div>
            <div class="wl-item"><span class="wl-num">{{ agent.doneCount || 0 }}</span><span class="wl-label">已完成</span></div>
            <div class="wl-item"><span class="wl-num">{{ agent.blockedCount || 0 }}</span><span class="wl-label">阻塞</span></div>
            <div class="wl-item"><span class="wl-num">{{ agent.reviewCount || 0 }}</span><span class="wl-label">待审查</span></div>
          </div>
        </div>

        <div class="detail-card">
          <div class="card-subtitle">时间线</div>
          <div class="timeline-list">
            <div class="tl-row"><span class="tl-label">注册时间</span><span>{{ agent.createdAt || '-' }}</span></div>
            <div class="tl-row"><span class="tl-label">最后活动</span><span>{{ agent.lastActivityAt || '-' }}</span></div>
            <div class="tl-row"><span class="tl-label">Agent ID</span><code style="font-size:11px">{{ agent.id }}</code></div>
            <div class="tl-row"><span class="tl-label">API Key</span><code style="font-size:11px">{{ agent.apiKey ? agent.apiKey.substring(0, 12) + '...' : '-' }}</code></div>
          </div>
        </div>
      </div>

      <!-- 操作 -->
      <div class="detail-card">
        <div class="card-subtitle">操作</div>
        <div class="ops-row">
          <el-button @click="openEdit">编辑信息</el-button>
          <el-button :type="agent.status === 'ACTIVE' ? 'warning' : 'success'" @click="openToggleStatus">
            {{ agent.status === 'ACTIVE' ? '禁用' : '启用' }}
          </el-button>
          <el-button type="primary" @click="handleResetKey">重置 API Key</el-button>
          <el-button type="danger" @click="openDelete">删除 Agent</el-button>
        </div>
      </div>

      <!-- 积分明细 -->
      <div class="detail-card">
        <div class="card-subtitle">积分明细</div>
        <el-table :data="scoreList" border stripe style="width:100%" v-loading="scoreLoading">
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="变动" width="100">
            <template #default="{ row }">
              <span :style="{ color: row.delta > 0 ? 'var(--ha-success)' : 'var(--ha-danger)', fontWeight:'600' }">
                {{ row.delta > 0 ? '+' : '' }}{{ row.delta }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="balance" label="余额" width="80" />
          <el-table-column prop="createTime" label="时间" width="170" />
        </el-table>
        <el-pagination
          v-if="scoreTotal > scorePageSize"
          layout="prev, pager, next" background size="small"
          :total="scoreTotal" :page-size="scorePageSize"
          @current-change="loadScoreLogs"
          style="margin-top:12px;justify-content:center"
        />
        <el-empty v-if="!scoreLoading && scoreList.length === 0" description="暂无积分记录" :image-size="60" />
      </div>

      <!-- 活动日志 -->
      <div class="detail-card">
        <div class="card-subtitle">活动日志</div>
        <el-table :data="activityList" border stripe style="width:100%" v-loading="activityLoading">
          <el-table-column prop="action" label="动作" min-width="160" show-overflow-tooltip />
          <el-table-column prop="level" label="级别" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.level === 'ERROR'" size="small" type="danger">ERROR</el-tag>
              <el-tag v-else-if="row.level === 'WARN'" size="small" type="warning">WARN</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="时间" width="170" />
        </el-table>
        <el-pagination
          v-if="activityTotal > activityPageSize"
          layout="prev, pager, next" background size="small"
          :total="activityTotal" :page-size="activityPageSize"
          @current-change="loadActivityLogs"
          style="margin-top:12px;justify-content:center"
        />
        <el-empty v-if="!activityLoading && activityList.length === 0" description="暂无活动记录" :image-size="60" />
      </div>
    </template>

    <!-- 编辑弹窗 -->
    <AgentEditDialog v-model="editDialog" :agent="editAgentData" @saved="loadDetail" />

    <!-- 状态切换 -->
    <AgentStatusDialog v-model="statusDialog" :agent="statusAgentData" @done="loadDetail" />

    <!-- 删除确认 -->
    <AgentDeleteDialog v-model="deleteDialog" :agent="deleteAgentData" @done="handleDeleted" />

    <!-- 重置 Key 结果 -->
    <el-dialog v-model="keyDialog" title="新 API Key" width="480px" top="10vh" append-to-body>
      <p style="font-size:13px;color:var(--ha-warning);margin:0 0 8px">
        此 Key 仅显示一次，请立即复制保存。
      </p>
      <el-input v-model="newApiKey" readonly>
        <template #append>
          <el-button @click="copyKey">复制</el-button>
        </template>
      </el-input>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { agentApi } from '@/api/agent'
import { ROLE_COLOR_MAP } from '@/types'
import type { AgentDetail, AgentListItem, ScoreLogItem, ActivityLogItem, AgentRole } from '@/types'
import AgentEditDialog from './components/AgentEditDialog.vue'
import AgentStatusDialog from './components/AgentStatusDialog.vue'
import AgentDeleteDialog from './components/AgentDeleteDialog.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const agent = ref<AgentDetail | null>(null)

function getAgentId() {
  return String(route.params.id || '')
}

const roleLabels: Record<AgentRole, string> = {
  PLANNER: '规划者', EXECUTOR: '执行者', REVIEWER: '审查者', PATROL: '巡查者'
}
function roleLabel(r: AgentRole) { return roleLabels[r] || r }

const roleStyle = computed(() => {
  if (!agent.value) return {}
  const c = ROLE_COLOR_MAP[agent.value.role]
  return c ? { background: c.bg, color: c.text, borderColor: c.border } : {}
})

async function loadDetail() {
  loading.value = true
  try {
    const id = getAgentId()
    agent.value = await agentApi.adminDetail(id)
    await Promise.all([loadScoreLogs(1), loadActivityLogs(1)])
  } catch (e: any) {
    const message = e?.response?.data?.msg || e?.message || 'Agent 详情加载失败'
    ElMessage.error(message)
    router.replace('/agents')
  } finally {
    loading.value = false
  }
}

// ── 积分明细 ──
const scoreList = ref<ScoreLogItem[]>([])
const scoreTotal = ref(0)
const scorePageSize = 10
const scoreLoading = ref(false)
async function loadScoreLogs(page = 1) {
  scoreLoading.value = true
  try {
    const id = getAgentId()
    const res = await agentApi.scoreLogs(id, { page, pageSize: scorePageSize })
    scoreList.value = res.list || []
    scoreTotal.value = res.total
  } finally { scoreLoading.value = false }
}

// ── 活动日志 ──
const activityList = ref<ActivityLogItem[]>([])
const activityTotal = ref(0)
const activityPageSize = 10
const activityLoading = ref(false)
async function loadActivityLogs(page = 1) {
  activityLoading.value = true
  try {
    const id = getAgentId()
    const res = await agentApi.activityLogs(id, { page, pageSize: activityPageSize })
    activityList.value = res.list || []
    activityTotal.value = res.total
  } finally { activityLoading.value = false }
}

// ── 弹窗操作 ──
const editDialog = ref(false)
const editAgentData = computed<AgentListItem | null>(() => agent.value ? agent.value as any : null)
function openEdit() { editDialog.value = true }

const statusDialog = ref(false)
const statusAgentData = computed<AgentListItem | null>(() => agent.value ? agent.value as any : null)
function openToggleStatus() { statusDialog.value = true }

const deleteDialog = ref(false)
const deleteAgentData = computed<AgentListItem | null>(() => agent.value ? agent.value as any : null)
function openDelete() { deleteDialog.value = true }
function handleDeleted() { router.push('/agents') }

// ── 重置 Key ──
const keyDialog = ref(false)
const newApiKey = ref('')
async function handleResetKey() {
  if (!agent.value) return
  try {
    const res = await agentApi.resetKey(agent.value.id)
    newApiKey.value = res.apiKey
    keyDialog.value = true
  } catch {}
}
function copyKey() {
  navigator.clipboard.writeText(newApiKey.value)
  ElMessage.success('已复制到剪贴板')
}

onMounted(() => loadDetail())
</script>

<style scoped>
.back-bar {
  margin-bottom: 16px;
}

.detail-card {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  padding: 20px;
  margin-bottom: 16px;
}

/* ── 头部 ── */
.header-top {
  display: flex;
  gap: 16px;
}

.detail-name {
  font-size: 22px;
  font-weight: 600;
  color: var(--ha-ink);
  margin: 0;
  letter-spacing: -0.02em;
}

.detail-desc {
  margin: 6px 0 10px;
  font-size: 14px;
  color: var(--ha-ink-secondary);
  max-width: 65ch;
}

.detail-tags {
  display: flex;
  gap: 8px;
  align-items: center;
}

.tag-role {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
  border: 1px solid;
  line-height: 1.5;
}

/* ── 统计 ── */
.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-mini {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  padding: 16px 20px;
  text-align: center;
}

.stat-mini-label {
  display: block;
  font-size: 12px;
  color: var(--ha-muted);
  margin-bottom: 4px;
}

.stat-mini-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--ha-ink);
  letter-spacing: -0.02em;
}

/* ── 两栏 ── */
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.card-subtitle {
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}

/* ── 工作量 ── */
.workload-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}

.wl-item {
  text-align: center;
  padding: 8px 4px;
  background: var(--ha-surface);
  border-radius: var(--ha-radius-md);
}

.wl-num {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: var(--ha-primary);
}

.wl-label {
  font-size: 11px;
  color: var(--ha-muted);
  margin-top: 2px;
}

/* ── 时间线 ── */
.timeline-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tl-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ha-ink-secondary);
}

.tl-label {
  color: var(--ha-muted);
}

/* ── 操作 ── */
.ops-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .stats-row { grid-template-columns: 1fr; }
  .two-col { grid-template-columns: 1fr; }
  .workload-grid { grid-template-columns: repeat(3, 1fr); }
  .ops-row { flex-direction: column; }
}
</style>
