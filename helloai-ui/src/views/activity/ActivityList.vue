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
          label="Agent"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="agent-cell">
              <span class="agent-name">{{ row.agentName || (row.agentId ? '#' + row.agentId : '-') }}</span>
              <el-tag
                v-if="row.agentAccessType"
                size="small"
                effect="plain"
                :type="accessTypeTag(row.agentAccessType)"
              >
                {{ accessTypeLabel(row.agentAccessType) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="动作"
          width="130"
        >
          <template #default="{ row }">
            <!-- 中文名由后端 /api/enums/catalog 下发（2026-10-18）；未收录回退原码 -->
            <el-tag
              size="small"
              :type="actionTag(row.action)"
              :title="actionDesc(row.action)"
            >
              {{ actionLabel(row.action) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="detail"
          label="详情"
          min-width="280"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.detail ? JSON.stringify(row.detail) : '-' }}
          </template>
        </el-table-column>
        <el-table-column
          label="级别"
          width="90"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="levelTag(row.level)"
            >
              {{ row.level || 'INFO' }}
            </el-tag>
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
import type { ActivityLogItem } from '@/types'

// 枚举字典（2026-10-18）：活动 action 中文名由后端下发，前端不再硬编码
const enumStore = useEnumCatalogStore()
function actionLabel(code: string): string {
  enumStore.ensureLoaded() // fire-and-forget：就绪后重渲染生效
  return enumStore.labelOf('activityAction', code)
}
function actionDesc(code: string): string {
  return enumStore.descOf('activityAction', code)
}

// ── 动作 → 标签配色（2026-10-22：让「类型」一眼可辨，空值回退 info）──
type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'
const ACTION_TAG: Record<string, TagType> = {
  delivery: 'success',   // 提交产出
  blocked: 'danger',     // 遇到阻塞
  reflection: 'warning', // 自省笔记
  plan: 'primary',       // 规划决策
  review: 'info',        // 审查记录
  coding: 'info',        // 执行记录
  patrol: 'info',        // 巡查记录
}
function actionTag(code: string): TagType {
  return ACTION_TAG[code] || 'info'
}

// ── 级别配色：ERROR 红 / WARN 橙 / 其余灰 ──
function levelTag(level: string): TagType {
  if (level === 'ERROR') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}

// ── Agent 接入类型 → 中文名 + 标签色（与 AgentSelect.vue 的映射保持一致）──
const ACCESS_TYPE_LABELS: Record<string, string> = {
  API_KEY_LLM: '内部 LLM',
  CLI_CLIENT: '外部 CLI',
  WEB_BROWSER: '外部网页',
}
function accessTypeLabel(t?: string): string {
  if (!t) return ''
  return ACCESS_TYPE_LABELS[t] || t
}
function accessTypeTag(t?: string): TagType {
  if (t === 'API_KEY_LLM') return 'warning'
  if (t === 'CLI_CLIENT') return 'success'
  if (t === 'WEB_BROWSER') return 'primary'
  return 'info'
}

const list = ref<ActivityLogItem[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await activityApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>

<style scoped>
/* 列表页通用外观由 design-system.css 的 List Page Template 统一提供。 */

/* Agent 单元格：名称 + 接入类型标签同行（2026-10-22 活动流增强） */
.agent-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.agent-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
