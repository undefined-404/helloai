<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>收件箱</span>
          <div>
            <el-tag
              v-if="unreadCount > 0"
              type="danger"
              size="small"
            >
              未读 {{ unreadCount }}
            </el-tag>
            <el-button
              size="small"
              class="ml-sm"
              @click="load"
            >
              刷新
            </el-button>
          </div>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="类型"
          width="120"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="eventTypeTag(row.eventType)"
            >
              {{ INBOX_EVENT_TYPE_MAP[row.eventType] || row.eventType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="title"
          label="标题"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column
          label="优先级"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.priority==='URGENT'"
              size="small"
              type="danger"
            >
              紧急
            </el-tag>
            <el-tag
              v-else-if="row.priority==='HIGH'"
              size="small"
              type="warning"
            >
              高
            </el-tag>
            <span v-else>-</span>
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
          :width="ACTION.TWO"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-cell">
              <el-button
                v-if="row.isRead===0"
                size="small"
                type="primary"
                @click="markRead(row)"
              >
                标为已读
              </el-button>
              <el-button
                size="small"
                @click="markArchived(row)"
              >
                归档
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="收件箱为空"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { inboxApi } from '@/api/inbox'
import { INBOX_EVENT_TYPE_MAP } from '@/types'
import { ACTION } from '@/utils/tableConfig'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<any[]>([])
const loading = ref(false)
const unreadCount = ref(0)

const eventTagMap: Record<string, string> = {
  'sub_task.assigned': '', 'sub_task.submitted': 'warning',
  'sub_task.rejected': 'danger', 'sub_task.blocked': 'danger',
  'sub_task.paused': 'info', 'sub_task.resumed': 'success', 'sub_task.cancelled': 'info'
}
function eventTypeTag(type: string) { return eventTagMap[type] || '' }

async function load() {
  loading.value = true
  try {
    list.value = await inboxApi.list(50)
    const c = await inboxApi.count()
    unreadCount.value = c?.total_unread || 0
  } finally { loading.value = false }
}
async function markRead(row: any) {
  await inboxApi.markRead(row.id); row.isRead = 1
  unreadCount.value = Math.max(0, unreadCount.value - 1)
  ElMessage.success('已标记为已读')
}
async function markArchived(row: any) {
  await inboxApi.markArchived(row.id); ElMessage.success('已归档'); load()
}
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.ml-sm { margin-left: var(--ha-space-sm); }
</style>
