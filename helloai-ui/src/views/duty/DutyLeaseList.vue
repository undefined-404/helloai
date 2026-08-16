<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Agent 打卡上班</span>
          <el-button
            size="small"
            @click="load(currentPage)"
          >
            刷新
          </el-button>
        </div>
      </template>

      <!-- Agent 维度表格：每个 Agent 一行，展示最新一条打卡记录 -->
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width: 100%"
        empty-text="暂无打卡记录"
      >
        <el-table-column
          label="Agent"
          min-width="180"
        >
          <template #default="{ row }">
            <span class="agent-name">{{ row.agentName || '—' }}</span>
            <span class="agent-id">#{{ row.agentId }}</span>
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
          prop="workMode"
          label="模式"
          width="90"
        />
        <el-table-column
          label="并发上限"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.maxConcurrent == null">—</span>
            <span v-else>{{ row.maxConcurrent }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="上班时间"
          width="160"
        >
          <template #default="{ row }">
            {{ fmtTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="续约时间"
          width="160"
        >
          <template #default="{ row }">
            {{ fmtTime(row.lastRenewedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="超时时间"
          width="160"
        >
          <template #default="{ row }">
            {{ fmtTime(row.expiresAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="打卡总数"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            {{ row.leaseCount }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="90"
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
import { ref, onMounted } from 'vue'
import { dutyApi } from '@/api/duty'
import { DUTY_LEASE_STATUS_MAP, type DutyAgentLatestResponse } from '@/types/duty'
import type { LongId } from '@/types'
import { fmtTime } from '@/utils/tableConfig'
import DutyLeaseHistoryDialog from './components/DutyLeaseHistoryDialog.vue'

const list = ref<DutyAgentLatestResponse[]>([])
const total = ref(0)
const pageSize = ref(20)
const currentPage = ref(1)
const loading = ref(false)

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
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.agent-name {
  font-weight: 600;
  color: var(--ha-ink);
  margin-right: 8px;
}
.agent-id {
  font-size: 12px;
  color: var(--ha-ink-secondary);
}
</style>
