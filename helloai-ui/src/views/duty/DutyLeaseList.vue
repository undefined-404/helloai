<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Agent 值班租约</span>
          <el-button size="small" @click="load(currentPage)">刷新</el-button>
        </div>
      </template>

      <!-- 过滤区 -->
      <div class="filter-bar">
        <div class="filter-item">
          <span class="filter-label">状态</span>
          <el-select
            v-model="filter.status"
            placeholder="全部"
            clearable
            style="width: 140px"
            @change="load(1)"
          >
            <el-option
              v-for="(meta, key) in DUTY_LEASE_STATUS_MAP"
              :key="key"
              :label="meta.label"
              :value="key"
            />
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">Agent ID</span>
          <el-input
            v-model.number="filter.agentId"
            placeholder="可选,精确匹配"
            clearable
            style="width: 200px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
        </div>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </div>

      <!-- 表格 -->
      <el-table
        :data="list"
        border
        stripe
        v-loading="loading"
        style="width: 100%"
        empty-text="暂无值班租约"
      >
        <el-table-column prop="id" label="租约 ID" width="120" />
        <el-table-column label="Agent" min-width="180">
          <template #default="{ row }">
            <span class="agent-name">{{ row.agentName || '—' }}</span>
            <span class="agent-id">#{{ row.agentId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="sessionId" label="会话" min-width="200" show-overflow-tooltip />
        <el-table-column prop="workMode" label="模式" width="100" />
        <el-table-column label="并发上限" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.maxConcurrent == null">—</span>
            <span v-else>{{ row.maxConcurrent }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }: { row: DutyLeaseResponse }">
            <el-tag :type="DUTY_LEASE_STATUS_MAP[row.status]?.type || 'info'" size="small">
              {{ DUTY_LEASE_STATUS_MAP[row.status]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="续约时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.lastRenewedAt) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column prop="closeReason" label="关闭原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!row.closeReason" class="muted">—</span>
            <span v-else>{{ row.closeReason }}</span>
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
        @current-change="load"
        style="margin-top: 16px; text-align: center"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { dutyApi } from '@/api/duty'
import { DUTY_LEASE_STATUS_MAP, type DutyLeaseResponse, type DutyLeaseStatus } from '@/types/duty'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<DutyLeaseResponse[]>([])
const total = ref(0)
const pageSize = ref(20)
const currentPage = ref(1)
const loading = ref(false)

const filter = reactive<{
  status: DutyLeaseStatus | null
  agentId: number | null
}>({
  status: null,
  agentId: null
})

async function load(page = 1) {
  loading.value = true
  currentPage.value = page
  try {
    const params: Record<string, any> = {
      page,
      size: pageSize.value
    }
    if (filter.status) params.status = filter.status
    if (filter.agentId != null && String(filter.agentId).trim() !== '') {
      params.agentId = filter.agentId
    }
    const res = await dutyApi.list(params)
    list.value = res?.list || []
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.page {
  max-width: var(--ha-content-width);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: var(--ha-muted);
  white-space: nowrap;
}

.agent-name {
  font-weight: 500;
  color: var(--ha-ink);
  margin-right: 6px;
}

.agent-id {
  font-size: 12px;
  color: var(--ha-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.muted {
  color: var(--ha-muted);
}
</style>