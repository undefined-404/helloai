<template>
  <Teleport to="body">
    <!--
      MSG-6 置顶公告条（设计 §3.9）。
      闸门 store.visible = announcementEnabled && 有已发布置顶（默认 OFF）：关闭时不渲染、不发请求。
      版本已读：seenVersion < version 即未读，未读新版本自动展开;折叠态按版本记忆（localStorage）。
      与铃铛共享 announcement store;公告未读并入铃铛角标。
    -->
    <div
      v-if="store.visible && published"
      class="ann-bar"
      :class="{ expanded }"
    >
      <div
        class="ann-head"
        role="button"
        tabindex="0"
        @click="toggle"
        @keydown.enter="toggle"
      >
        <el-icon
          :size="16"
          class="ann-icon"
        >
          <Notification />
        </el-icon>
        <span class="ann-title">{{ published.title }}</span>
        <el-tag
          size="small"
          type="info"
          class="ann-ver"
        >
          v{{ published.version }}
        </el-tag>
        <span
          v-if="published.unread"
          class="ann-dot"
          title="有新版本"
        />
        <el-icon
          :size="14"
          class="ann-chevron"
        >
          <ArrowUp v-if="expanded" />
          <ArrowDown v-else />
        </el-icon>
      </div>
      <div
        v-show="expanded"
        class="ann-body"
      >
        <div class="ann-text">
          {{ published.body }}
        </div>
        <div class="ann-actions">
          <span class="ann-time">{{ formatTime(published.publishedAt) }}</span>
          <el-button
            v-if="published.unread"
            size="small"
            type="primary"
            @click="onDismiss"
          >
            知道了
          </el-button>
          <el-button
            v-else
            size="small"
            text
            @click="collapse"
          >
            收起
          </el-button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown, ArrowUp, Notification } from '@element-plus/icons-vue'
import { useAnnouncementStore } from '@/stores/announcement'

const store = useAnnouncementStore()
const published = computed(() => store.published)
const expanded = ref(false)

/** 折叠记忆：记住用户手动收起的版本号;新版本（version 更大）重新自动展开。 */
const COLLAPSE_KEY = 'ha-announcement-collapsed-version'
let lastVersion = -1

watch(
  () => store.published,
  (p) => {
    if (!p) {
      expanded.value = false
      lastVersion = -1
      return
    }
    // 同版本刷新（如已读上报）不打断用户手动展开态
    if (p.version === lastVersion) return
    lastVersion = p.version
    const collapsedVersion = Number(localStorage.getItem(COLLAPSE_KEY) || 0)
    expanded.value = p.unread && p.version > collapsedVersion
  },
  { immediate: true }
)

function rememberCollapsed() {
  if (published.value) localStorage.setItem(COLLAPSE_KEY, String(published.value.version))
}

function toggle() {
  expanded.value = !expanded.value
  if (!expanded.value) rememberCollapsed()
}

function collapse() {
  expanded.value = false
  rememberCollapsed()
}

async function onDismiss() {
  const p = published.value
  if (!p) return
  await store.markSeen(p.id, p.version)
  expanded.value = false
  rememberCollapsed()
}

function formatTime(ts: string | null): string {
  if (!ts) return ''
  const t = Date.parse(ts)
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

// 回到前台重读开关 + 置顶：可能在其他标签页被改过 / 发布了新版本（与铃铛同口径）
function onVisibility() {
  if (document.visibilityState === 'visible') void store.reload()
}

onMounted(() => {
  void store.start()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  store.stop()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.ann-bar {
  position: fixed;
  top: 14px;
  right: 72px;
  width: min(560px, calc(100vw - 320px));
  z-index: 2900;
  border-radius: 12px;
  border: 1px solid var(--ha-border, rgba(128, 128, 128, .3));
  background: var(--ha-surface-elevated, #FFFFFF);
  box-shadow: var(--ha-shadow-md, 0 4px 12px rgba(0, 0, 0, .12));
  overflow: hidden;
}
.ann-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  cursor: pointer;
  user-select: none;
}
.ann-icon {
  color: var(--ha-primary, #7C3AED);
  flex-shrink: 0;
}
.ann-title {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ha-ink, #1A2233);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ann-ver {
  flex-shrink: 0;
}
.ann-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ha-danger, #ef4444);
  flex-shrink: 0;
}
.ann-chevron {
  color: var(--ha-muted, #8B96AB);
  flex-shrink: 0;
}
.ann-body {
  padding: 0 12px 10px;
  border-top: 1px solid var(--ha-border, rgba(128, 128, 128, .18));
}
.ann-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ha-ink-secondary, #4A5568);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
  padding-top: 8px;
}
.ann-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}
.ann-time {
  font-size: 11px;
  color: var(--ha-muted, #8B96AB);
}
</style>