<template>
  <!-- V42 任务执行迭代时间轴 -->
  <div class="iter-timeline" v-loading="loading || backfilling">
    <el-empty v-if="!loading && !items.length" description="暂无迭代记录（任务执行后自动回填）">
      <el-button type="primary" :loading="backfilling" @click="$emit('backfill')">回填历史迭代记录</el-button>
    </el-empty>
    <template v-else>
      <div v-for="item in items" :key="String(item.id)" class="iter-card">
        <!-- 头部信息栏 -->
        <div class="iter-head">
          <span class="iter-code">{{ item.taskCode }}</span>
          <span class="iter-name">{{ item.taskName }}</span>
          <el-tag :type="taskTypeTag(item.taskType)" size="small" class="iter-tag">{{ item.taskType }}</el-tag>
          <el-tag v-if="item.reviewResult" :type="reviewResultTag(item.reviewResult)" size="small" class="iter-tag">
            {{ item.reviewResult }}
          </el-tag>
          <span class="iter-meta">
            R{{ item.roundNum }}
            <template v-if="item.executorAgent"> · {{ item.executorAgent }}</template>
            <template v-if="item.createTime"> · {{ fmtTime(item.createTime) }}</template>
          </span>
          <el-button link size="small" type="primary" class="iter-toggle" @click="toggleExpand(String(item.id))">
            {{ expanded.has(String(item.id)) ? '收起详情' : '查看详情' }}
          </el-button>
        </div>

        <!-- V44 执行摘要：始终可见，点击即可 3 秒了解任务结论 -->
        <div v-if="item.outputSummary" class="iter-summary">
          <span class="iter-summary-icon">📝</span>
          <span class="iter-summary-text">{{ item.outputSummary }}</span>
        </div>

        <!-- 展开区域：完整产出、需求、驳回 -->
        <div v-if="expanded.has(String(item.id))" class="iter-body">
          <!-- 当前需求 -->
          <div v-if="item.currentRequirement" class="iter-section">
            <div class="iter-section-title">当前需求</div>
            <div class="iter-text">{{ item.currentRequirement }}</div>
          </div>

          <!-- LLM 完整响应 -->
          <div v-if="item.llmResponse" class="iter-section">
            <div class="iter-section-title">LLM 完整响应</div>
            <div class="iter-markdown-scroll">
              <MarkdownView :content="item.llmResponse" />
            </div>
          </div>

          <!-- 驳回历史 -->
          <div v-if="item.rejectionHistory && item.rejectionHistory.length" class="iter-section">
            <div class="iter-section-title">驳回记录（{{ item.rejectionHistory.length }} 次）</div>
            <div v-for="(rj, idx) in item.rejectionHistory" :key="idx" class="iter-rejection">
              <el-tag size="small" type="danger">驳回 #{{ rj.round || (idx + 1) }}</el-tag>
              <div v-if="rj.comment" class="iter-text">{{ rj.comment }}</div>
              <div v-if="rj.issues" class="iter-text iter-issues">{{ rj.issues }}</div>
              <span v-if="rj.score != null" class="iter-score">评分: {{ rj.score }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { fmtTime } from '@/utils/tableConfig'
import type { TaskIteration } from '@/types'

defineProps<{
  items: TaskIteration[]
  loading: boolean
  backfilling: boolean
}>()

defineEmits<{
  backfill: []
}>()

const expanded = ref(new Set<string>())

function toggleExpand(id: string) {
  if (expanded.value.has(id)) {
    expanded.value.delete(id)
  } else {
    expanded.value.add(id)
  }
}

function taskTypeTag(type: string) {
  const map: Record<string, string> = { DEVELOPMENT: '', TESTING: 'warning', PLANNING: 'info', OTHER: '' }
  return map[type] || ''
}

function reviewResultTag(result: string) {
  return result === 'PASSED' ? 'success' : 'danger'
}
</script>

<style scoped>
.iter-timeline {
  position: relative;
}
.iter-card {
  border: 1px solid var(--ha-border-light);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
  background: var(--ha-surface-elevated);
}
.iter-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.iter-code {
  font-weight: 700;
  font-size: 14px;
  color: var(--el-color-primary);
  min-width: 32px;
}
.iter-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--ha-ink);
}
.iter-tag {
  flex-shrink: 0;
}
.iter-meta {
  font-size: 12px;
  color: var(--ha-muted);
  margin-left: auto;
}
.iter-toggle {
  flex-shrink: 0;
}
.iter-body {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--ha-border-light);
}
.iter-summary {
  margin-top: 8px;
  padding: 8px 12px;
  background: var(--ha-surface);
  border-radius: 6px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.iter-summary-icon {
  flex-shrink: 0;
  font-size: 14px;
  line-height: 1.5;
}
.iter-summary-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
}
.iter-markdown-scroll {
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid var(--ha-border-light);
  border-radius: 6px;
  padding: 8px 12px;
  background: var(--ha-surface);
}
.iter-section {
  margin-bottom: 12px;
}
.iter-section:last-child {
  margin-bottom: 0;
}
.iter-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-muted);
  margin-bottom: 6px;
}
.iter-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
.iter-issues {
  color: var(--el-color-danger);
}
.iter-score {
  font-size: 12px;
  color: var(--ha-muted);
  display: inline-block;
  margin-top: 4px;
}
.iter-rejection {
  margin-bottom: 8px;
  padding: 8px;
  background: var(--ha-surface-hover);
  border-radius: 4px;
}
.iter-rejection:last-child {
  margin-bottom: 0;
}
</style>
