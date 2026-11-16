<template>
  <el-drawer
    v-model="store.drawerOpen"
    title="站内消息"
    direction="rtl"
    size="420px"
    :append-to-body="true"
    class="notif-drawer"
  >
    <template #header>
      <div class="nd-head">
        <span class="nd-title">站内消息</span>
        <el-button
          type="primary"
          link
          :disabled="store.unreadCount === 0"
          @click="onMarkAllRead"
        >
          全部已读
        </el-button>
      </div>
    </template>

    <div class="nd-toolbar">
      <el-select
        :model-value="store.typeFilter"
        placeholder="全部类型"
        size="small"
        class="nd-filter"
        @change="onFilterChange"
      >
        <el-option
          v-for="opt in TYPE_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-button
        size="small"
        :icon="Refresh"
        circle
        title="刷新"
        @click="store.loadList(true)"
      />
    </div>

    <div
      v-loading="store.loading"
      class="nd-body"
    >
      <el-empty
        v-if="!store.loading && store.rows.length === 0"
        description="暂无消息"
        :image-size="80"
      />
      <ul
        v-else
        class="nd-list"
      >
        <li
          v-for="row in store.rows"
          :key="row.id"
          class="nd-row"
          :class="{ unread: row.isRead === 0 }"
          @click="onRowClick(row)"
        >
          <span
            class="nd-dot"
            :class="severityClass(row.severity)"
          />
          <div class="nd-main">
            <div class="nd-row-title">
              {{ row.title }}
            </div>
            <div
              v-if="row.summary"
              class="nd-row-summary"
            >
              {{ row.summary }}
            </div>
            <div class="nd-row-time">
              {{ relTime(row.createTime) }}
            </div>
          </div>
          <button
            class="nd-archive"
            type="button"
            title="归档"
            @click.stop="onArchive(row.id)"
          >
            <el-icon :size="14">
              <Close />
            </el-icon>
          </button>
        </li>
      </ul>
    </div>

    <template #footer>
      <el-pagination
        v-if="store.total > PAGE_SIZE"
        :current-page="store.page"
        :page-size="PAGE_SIZE"
        :total="store.total"
        layout="prev, pager, next"
        small
        background
        @current-change="store.goToPage"
      />
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Close, Refresh } from '@element-plus/icons-vue'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationRow } from '@/api/notification'

const store = useNotificationStore()
const router = useRouter()
const PAGE_SIZE = 20

/** 首批激活的消息类型（与后端 NotificationMapping 首批登记一致）。 */
const TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'PLAN_DRAFT_READY', label: '拆解草案就绪' },
  { value: 'FINAL_REPORT_READY', label: '整合报告就绪' },
  { value: 'SUBTASK_SUBMITTED', label: '子任务产出提交' },
  { value: 'SUBTASK_DEAD_LETTER', label: '子任务死信' },
  { value: 'SUBTASK_NO_CANDIDATE', label: '暂无可用执行者' },
  { value: 'FINAL_REPORT_REVIEW_STUCK', label: '报告审查达上限' }
]

function severityClass(severity: string): string {
  if (severity === 'URGENT') return 'urgent'
  if (severity === 'HIGH') return 'high'
  return 'normal'
}

function relTime(ts: string): string {
  const t = Date.parse(ts)
  if (!t) return ''
  const diff = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (diff < 60) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前'
  return Math.floor(diff / 86400) + ' 天前'
}

/** 深链回源（设计 §3.4）：按 refType + refId 推导路由，后端不耦合 Vue 路由。 */
function resolveLink(row: NotificationRow): string | null {
  switch (row.refType) {
    case 'SUB_TASK':
      return row.refId ? `/sub-tasks/${row.refId}` : '/sub-tasks'
    case 'TASK':
      return row.refId ? `/tasks?taskId=${row.refId}` : '/tasks'
    case 'AGENT':
      return row.refId ? `/agents/${row.refId}` : '/agents'
    case 'CONVERSATION':
      return '/requirement-chat'
    default:
      return null
  }
}

async function onRowClick(row: NotificationRow) {
  if (row.isRead === 0) await store.markRead(row.id)
  const link = resolveLink(row)
  store.closeDrawer()
  if (link) await router.push(link)
}

async function onArchive(id: string) {
  await store.archive(id)
}

async function onMarkAllRead() {
  await store.markAllRead()
}

function onFilterChange(value: string) {
  store.setTypeFilter(value)
}
</script>

<style scoped>
.nd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.nd-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--ha-ink, #1A2233);
}
.nd-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.nd-filter {
  flex: 1;
}
.nd-body {
  min-height: 200px;
}
.nd-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.nd-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--ha-border, rgba(128, 128, 128, .18));
  background: var(--ha-surface-elevated, #FFFFFF);
  cursor: pointer;
  transition: background-color var(--ha-duration-fast, .15s) var(--ha-ease-out, ease);
}
.nd-row:hover {
  background: var(--ha-surface-hover, rgba(0, 0, 0, .04));
}
.nd-row.unread {
  border-left: 3px solid var(--ha-primary, #7C3AED);
}
.nd-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}
.nd-dot.normal { background: #3b82f6; }
.nd-dot.high { background: #f59e0b; }
.nd-dot.urgent { background: #ef4444; }
.nd-main {
  flex: 1;
  min-width: 0;
}
.nd-row-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ha-ink, #1A2233);
}
.nd-row-summary {
  font-size: 12.5px;
  color: var(--ha-ink-secondary, #4A5568);
  margin-top: 3px;
  line-height: 1.5;
}
.nd-row-time {
  font-size: 11px;
  color: var(--ha-muted, #8B96AB);
  margin-top: 4px;
}
.nd-archive {
  border: none;
  background: transparent;
  color: var(--ha-muted, #8B96AB);
  cursor: pointer;
  padding: 2px;
  border-radius: 6px;
  flex-shrink: 0;
}
.nd-archive:hover {
  color: var(--ha-ink, #1A2233);
  background: var(--ha-surface-hover, rgba(0, 0, 0, .06));
}
</style>