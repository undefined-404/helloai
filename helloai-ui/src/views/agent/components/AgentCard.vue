<template>
  <div
    class="agent-card animate-slide-up"
    :style="{ animationDelay: `${index * 40}ms` }"
    @click="$emit('click', agent.id)"
  >
    <!-- 角色色条 -->
    <div
      class="card-bar"
      :style="{ background: roleColor.bar }"
    />

    <div class="card-body">
      <!-- 头 -->
      <div class="card-head">
        <span
          class="card-name"
          :title="agent.name"
        >{{ agent.name }}</span>
        <span
          class="status-dot"
          :class="agent.status === 'ACTIVE' ? 'active' : 'disabled'"
          :title="agent.status === 'ACTIVE' ? '已注册' : '已注销'"
        />
      </div>

      <!-- 角色标签 -->
      <span
        class="card-role-tag"
        :style="{
          background: roleColor.bg,
          color: roleColor.text,
          borderColor: roleColor.border
        }"
      >
        {{ roleLabel(agent.role) }}
      </span>

      <!-- 描述 -->
      <p class="card-desc">
        {{ agent.description || agent.remark || '暂无描述' }}
      </p>

      <!-- 统计 -->
      <div class="card-stats">
        <span class="stat-item">
          <strong>{{ agent.totalScore || 0 }}</strong> 分
        </span>
        <span class="stat-sep">·</span>
        <span class="stat-item">#{{ agent.rank || '-' }}</span>
        <span class="stat-sep">·</span>
        <span class="stat-item">{{ taskCount }} 任务</span>
        <span class="stat-time">{{ relativeTime(agent.lastActivityAt || agent.createdAt) }}</span>
      </div>
    </div>

    <!-- Hover 操作栏 -->
    <div
      class="card-actions"
      @click.stop
    >
      <!-- 内部 LLM Agent 无 CLI 接入流程，不展示接入内容入口 -->
      <el-button
        v-if="agent.accessType !== 'API_KEY_LLM'"
        size="small"
        type="primary"
        plain
        @click="$emit('onboarding', agent)"
      >
        生成接入内容
      </el-button>
      <el-button
        size="small"
        @click="$emit('edit', agent)"
      >
        编辑
      </el-button>
      <el-button
        size="small"
        @click="$emit('toggle-status', agent)"
      >
        {{ agent.status === 'ACTIVE' ? '注销' : '恢复注册' }}
      </el-button>
      <el-button
        size="small"
        type="danger"
        @click="$emit('delete', agent)"
      >
        删除
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AgentListItem, AgentRole } from '@/types'
import { ROLE_COLOR_MAP } from '@/types'

const props = defineProps<{
  agent: AgentListItem
  index: number
}>()

defineEmits<{
  click: [id: string]
  edit: [agent: AgentListItem]
  'toggle-status': [agent: AgentListItem]
  delete: [agent: AgentListItem]
  onboarding: [agent: AgentListItem]
}>()

const roleColor = computed(() => ROLE_COLOR_MAP[props.agent.role] || ROLE_COLOR_MAP.EXECUTOR)

const roleLabels: Record<AgentRole, string> = {
  PLANNER: '规划者',
  EXECUTOR: '执行者',
  REVIEWER: '审查者'
}
function roleLabel(role: AgentRole) {
  return roleLabels[role] || role
}

const taskCount = computed(() => {
  const a = props.agent
  return (a.assignedCount || 0) + (a.inProgressCount || 0) + (a.doneCount || 0) + (a.blockedCount || 0)
})

function relativeTime(dateStr: string | null): string {
  if (!dateStr) return ''
  const diff = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}
</script>

<style scoped>
.agent-card {
  position: relative;
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-lg);
  box-shadow: var(--ha-shadow-sm);
  overflow: hidden;
  cursor: pointer;
  transition: transform var(--ha-duration-normal) var(--ha-ease-out),
              box-shadow var(--ha-duration-normal) var(--ha-ease-out);
}

.agent-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 20px rgba(124, 58, 237, 0.14);
}

.card-bar {
  height: 3px;
  width: 100%;
}

.card-body {
  padding: 14px 16px 12px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-name {
  font-weight: 650;
  font-size: 15px;
  color: var(--ha-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.active  { background: var(--ha-success); }
.status-dot.disabled { background: var(--ha-muted); }

.card-role-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  margin-top: 8px;
  border-radius: 999px;
  border: 1px solid;
  line-height: 1.4;
}

.card-desc {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ha-ink-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2rem;
}

.card-stats {
  margin-top: 10px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--ha-muted);
}

.stat-item strong {
  color: var(--ha-ink);
  font-weight: 600;
}

.stat-sep {
  opacity: 0.4;
}

.stat-time {
  margin-left: auto;
  white-space: nowrap;
}

/* Hover actions */
.card-actions {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 8px;
  padding: 10px;
  background: linear-gradient(to top, var(--ha-surface-elevated) 60%, transparent);
  opacity: 0;
  transform: translateY(8px);
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out),
              transform var(--ha-duration-fast) var(--ha-ease-out);
  pointer-events: none;
}

.agent-card:hover .card-actions {
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

/* Entrance animation */
@keyframes slide-up {
  from { opacity: 0; transform: translateY(12px); }
  to   { opacity: 1; transform: translateY(0); }
}
.animate-slide-up {
  animation: slide-up 0.35s var(--ha-ease-out) both;
}
</style>
