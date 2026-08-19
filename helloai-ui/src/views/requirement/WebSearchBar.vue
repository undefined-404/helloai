<template>
  <div
    class="ws-bar"
    :class="{ failed: trace.failed }"
  >
    <div
      class="ws-head"
      @click="expanded = !expanded"
    >
      <span class="ws-label">
        <template v-if="trace.failed">🌐 联网搜索失败</template>
        <template v-else-if="(trace.total ?? 0) === 0">🌐 未搜到相关网页</template>
        <template v-else>🌐 已联网搜索 {{ trace.total }} 个来源</template>
      </span>
      <span
        v-if="!trace.failed"
        class="ws-meta"
      >
        {{ providerLabel }}<template v-if="trace.costMs != null"> · {{ trace.costMs }}ms</template>
      </span>
      <el-icon
        class="ws-arrow"
        :class="{ up: expanded }"
      >
        <ArrowDown />
      </el-icon>
    </div>
    <div
      v-if="expanded"
      class="ws-body"
    >
      <div
        v-if="trace.failed"
        class="ws-reason"
      >
        原因：{{ trace.reason || '未知错误' }}
      </div>
      <div
        v-if="trace.query"
        class="ws-query"
      >
        搜索词：{{ trace.query }}
      </div>
      <div
        v-for="(r, i) in trace.results ?? []"
        :key="i"
        class="ws-item"
      >
        <div class="ws-item-title">
          <a
            v-if="r.url"
            :href="r.url"
            target="_blank"
            rel="noopener"
          >[{{ i + 1 }}] {{ r.title }}</a>
          <template v-else>[{{ i + 1 }}] {{ r.title }}</template>
          <span
            v-if="r.siteName"
            class="ws-site"
          >{{ r.siteName }}</span>
        </div>
        <div
          v-if="r.snippet"
          class="ws-snippet"
        >
          {{ r.snippet }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import type { WebSearchTrace } from '@/types'

// V41 联网搜索折叠查验条（对齐 DeepSeek「已搜索 xx 个网页」/ Kimi「已联网检索 · N 个信源」形态）：
// 挂在 assistant 回复消息上的可折叠状态条，折叠态一行概览，展开可见实际搜索词与来源明细，
// 让用户可查验"是否真正搜索了、搜了什么词、来源内容是否正确"；failed/total=0 态同样可见
const props = defineProps<{
  trace: WebSearchTrace
}>()

const expanded = ref(false)

const providerLabel = computed(() => props.trace.provider || '联网搜索')
</script>

<style scoped>
.ws-bar {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: var(--ha-surface-elevated);
  overflow: hidden;
}

.ws-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.ws-head:hover { background: var(--ha-primary-muted); }

.ws-label { font-size: 12px; color: var(--ha-muted); }
.ws-bar.failed .ws-label { color: var(--el-color-danger, #f56c6c); }
.ws-meta { font-size: 12px; color: var(--ha-muted); opacity: 0.75; flex: 1; }

.ws-arrow { font-size: 12px; color: var(--ha-muted); transition: transform 0.15s; flex-shrink: 0; }
.ws-arrow.up { transform: rotate(180deg); }

.ws-body {
  border-top: 1px solid var(--ha-border-light);
  padding: 8px 12px;
}

.ws-reason { font-size: 12px; color: var(--el-color-danger, #f56c6c); margin-bottom: 6px; }
.ws-query { font-size: 12px; color: var(--ha-muted); margin-bottom: 6px; }

.ws-item { margin-bottom: 8px; }
.ws-item:last-child { margin-bottom: 0; }

.ws-item-title {
  font-size: 12px;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.ws-item-title a { color: var(--ha-primary, #409eff); text-decoration: none; }
.ws-item-title a:hover { text-decoration: underline; }
.ws-site {
  font-size: 11px;
  color: var(--ha-muted);
  border: 1px solid var(--ha-border-light);
  border-radius: 3px;
  padding: 0 4px;
}

.ws-snippet {
  font-size: 12px;
  color: var(--ha-muted);
  line-height: 1.5;
  margin-top: 2px;
  word-break: break-word;
}
</style>
