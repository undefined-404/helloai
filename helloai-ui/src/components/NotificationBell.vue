<template>
  <Teleport to="body">
    <!--
      MSG-5 全局站内消息铃铛（设计 §3.9）。
      闸门 store.visible = isAdmin && notificationEnabled（默认 OFF）：关闭时不渲染、不发请求、不建连。
      与工作台铃铛共享 notification store；SSE 由 store 统一建连 / 退避重连。
      形态二选一：默认停靠工作台（本组件不渲染）；拖出后为悬浮（本组件渲染），拖回停靠坑位即复原。
    -->
    <div
      v-if="store.visible && store.bellMode === 'floating'"
      class="notif-bell-wrap"
      :style="posStyle"
    >
      <el-badge
        :value="badgeCount"
        :hidden="badgeCount === 0"
        :max="99"
        class="notif-badge"
      >
        <button
          class="notif-bell"
          type="button"
          title="站内消息（拖回工作台铃铛处可停靠）"
          aria-label="站内消息"
          @pointerdown="onPointerDown"
          @click="onBellClick"
        >
          <el-icon :size="18">
            <Bell />
          </el-icon>
        </button>
      </el-badge>
    </div>
    <NotificationDrawer />
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { useNotificationStore } from '@/stores/notification'
import { useAnnouncementStore } from '@/stores/announcement'
import { isOverDock, usePointerDrag } from '@/composables/usePointerDrag'
import NotificationDrawer from './NotificationDrawer.vue'

const store = useNotificationStore()
const announcementStore = useAnnouncementStore()

// 角标 = 消息未读 + 公告未读（MSG-6：公告纯版本已读，公告 OFF 时其未读恒 0，不计入）
const badgeCount = computed(() => store.unreadCount + announcementStore.unreadCount)

const posStyle = computed(() => ({
  right: store.bellPos.right + 'px',
  top: store.bellPos.top + 'px'
}))

// ── 悬浮拖拽：跟手移动；拖回工作台停靠坑位（[data-notif-dock]）即复原为工作台铃铛 ──
// 拖拽壳与命中判定走公用 usePointerDrag（与额度胶囊同一套），锚点坐标仍在各自组件
let start = { right: 0, top: 0 }

const { onPointerDown, consumeClick } = usePointerDrag({
  onPress: () => {
    start = { right: store.bellPos.right, top: store.bellPos.top }
  },
  onDrag: (dx, dy) => {
    store.setBellPos({ right: start.right - dx, top: start.top + dy })
  },
  onRelease: (e, dragged) => {
    if (dragged && isOverDock('[data-notif-dock]', e.clientX, e.clientY)) store.setBellMode('docked')
  }
})

function onBellClick() {
  // 拖拽结束的 pointerup 之后浏览器可能补发 click：拦截一次，避免误开抽屉
  if (consumeClick()) return
  // 开合切换：抽屉已打开时再点悬浮铃铛即收回（与点遮罩关闭同效）
  if (store.drawerOpen) store.closeDrawer()
  else store.openDrawer()
}

// 回到前台重读开关：设置可能在其他标签页被改过（与 QuotaFloatWidget 同口径）
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
.notif-bell-wrap {
  position: fixed;
  z-index: 3000;
  user-select: none;
  touch-action: none;
}
.notif-bell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--ha-border, rgba(128, 128, 128, .3));
  background: var(--ha-surface-elevated, #FFFFFF);
  color: var(--ha-ink-secondary, #4A5568);
  cursor: pointer;
  box-shadow: var(--ha-shadow-md, 0 4px 12px rgba(0, 0, 0, .12));
  transition: color var(--ha-duration-fast, .15s) var(--ha-ease-out, ease),
              background-color var(--ha-duration-fast, .15s) var(--ha-ease-out, ease);
}
.notif-bell:hover {
  color: var(--ha-primary, #7C3AED);
  background: var(--ha-surface-hover, rgba(0, 0, 0, .04));
}
.notif-badge :deep(.el-badge__content) {
  border: none;
}
</style>