<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>活动流</span>
          <el-button
            size="small"
            type="primary"
            @click="load"
          >
            刷新
          </el-button>
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
          prop="action"
          label="动作"
          min-width="120"
          show-overflow-tooltip
        />
        <el-table-column
          prop="detail"
          label="详情"
          min-width="300"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.detail ? JSON.stringify(row.detail) : '-' }}
          </template>
        </el-table-column>
        <el-table-column
          label="级别"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.level==='ERROR'"
              size="small"
              type="danger"
            >
              ERROR
            </el-tag>
            <el-tag
              v-else-if="row.level==='WARN'"
              size="small"
              type="warning"
            >
              WARN
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
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="暂无活动记录"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { activityApi } from '@/api/activity'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await activityApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
</style>
