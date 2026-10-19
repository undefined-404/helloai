<template>
  <div class="page list-page ha-entrance-up">
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><Bell /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            活动流
            <span
              v-if="list.length"
              class="query-stats"
            >共 {{ list.length }} 条</span>
          </h2>
          <p class="page-subheading">
            平台操作与状态变更审计记录
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <el-button
          size="small"
          @click="load"
        >
          刷新
        </el-button>
      </div>
    </div>
    <el-card class="list-card">
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="动作"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <!-- 中文名由后端 /api/enums/catalog 下发（2026-10-18）；未收录回退原码 -->
            <span :title="actionDesc(row.action)">{{ actionLabel(row.action) }}</span>
          </template>
        </el-table-column>
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
          width="180"
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
import { Bell } from '@element-plus/icons-vue'
import { activityApi } from '@/api/activity'
import { useEnumCatalogStore } from '@/stores/enumCatalog'
import { fmtTime } from '@/utils/tableConfig'

// 枚举字典（2026-10-18）：活动 action 中文名由后端下发，前端不再硬编码
const enumStore = useEnumCatalogStore()
function actionLabel(code: string): string {
  enumStore.ensureLoaded() // fire-and-forget：就绪后重渲染生效
  return enumStore.labelOf('activityAction', code)
}
function actionDesc(code: string): string {
  return enumStore.descOf('activityAction', code)
}

const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await activityApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>

<style scoped>
/* 列表页通用外观由 design-system.css 的 List Page Template 统一提供。 */
</style>
