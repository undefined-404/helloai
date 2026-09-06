<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 筛选区 -->
    <div class="page-head">
      <h2 class="page-heading">
        Browser 会话
      </h2>
      <div class="page-actions">
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          class="filter-select"
          @change="load(1)"
        >
          <el-option label="进行中" value="ACTIVE" />
          <el-option label="已关闭" value="CLOSED" />
          <el-option label="异常终止" value="FAILED" />
        </el-select>
        <el-button
          size="small"
          @click="load(currentPage)"
        >
          刷新
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
      >
        <el-table-column label="Agent" width="160">
          <template #default="{ row }">
            {{ agentName(row.agentId) }}
          </template>
        </el-table-column>
        <el-table-column prop="taskId" label="任务 ID" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentUrl" label="当前 URL" min-width="240" show-overflow-tooltip />
        <el-table-column prop="beginTime" label="开始时间" width="170" />
        <el-table-column prop="closeTime" label="关闭时间" width="170" />
      </el-table>

      <div class="table-footer">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { browserSessionApi } from '@/api/browserSession'
import { agentApi } from '@/api/agent'
import type { Agent, BrowserSession } from '@/types'

const loading = ref(false)
const rows = ref<BrowserSession[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const agents = ref<Agent[]>([])

async function load(page = 1) {
  loading.value = true
  try {
    const data = await browserSessionApi.page({
      page,
      size: pageSize.value,
      status: statusFilter.value || undefined
    })
    rows.value = data.list
    total.value = data.total
    currentPage.value = data.current || page
  } finally {
    loading.value = false
  }
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

function statusLabel(status: string) {
  const map: Record<string, string> = { BEGIN: '开始', ACTIVE: '进行中', CLOSED: '已关闭', FAILED: '异常终止' }
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

onMounted(() => {
  load(1)
  loadAgents()
})
</script>
