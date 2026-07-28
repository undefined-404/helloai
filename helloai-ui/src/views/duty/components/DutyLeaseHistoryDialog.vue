<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="960px"
    top="6vh"
    append-to-body
    @open="load(1)"
    @close="$emit('close')"
  >
    <el-table
      :data="list"
      border
      stripe
      v-loading="loading"
      style="width: 100%"
      empty-text="暂无打卡记录"
    >
      <el-table-column prop="id" label="记录 ID" width="120" />
      <el-table-column prop="sessionId" label="会话" min-width="180" show-overflow-tooltip />
      <el-table-column prop="workMode" label="模式" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }: { row: DutyLeaseResponse }">
          <el-tag :type="DUTY_LEASE_STATUS_MAP[row.status]?.type || 'info'" size="small">
            {{ DUTY_LEASE_STATUS_MAP[row.status]?.label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上班时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="续约时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.lastRenewedAt) }}</template>
      </el-table-column>
      <el-table-column label="超时时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.expiresAt) }}</template>
      </el-table-column>
      <el-table-column prop="closeReason" label="关闭原因" min-width="140" show-overflow-tooltip>
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
      style="margin-top: 16px; justify-content: center"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { dutyApi } from '@/api/duty'
import { DUTY_LEASE_STATUS_MAP, type DutyLeaseResponse } from '@/types/duty'
import type { LongId } from '@/types'
import { fmtTime } from '@/utils/tableConfig'

const props = defineProps<{
  modelValue: boolean
  agentId: LongId | null
  agentName: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const dialogTitle = computed(() =>
  `打卡记录 — ${props.agentName || '未知 Agent'} #${props.agentId ?? ''}`
)

const list = ref<DutyLeaseResponse[]>([])
const total = ref(0)
const pageSize = ref(10)
const currentPage = ref(1)
const loading = ref(false)

async function load(page = 1) {
  if (props.agentId == null) return
  loading.value = true
  currentPage.value = page
  try {
    // LongId 保持 string 传参，防雪花 ID 精度丢失
    const res = await dutyApi.list({ agentId: props.agentId, page, size: pageSize.value })
    list.value = res?.list || []
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.muted { color: var(--ha-ink-secondary); }
</style>
