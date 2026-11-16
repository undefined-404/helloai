import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useAuthStore } from './auth'
import { notificationApi, type NotificationFeatures, type NotificationRow } from '@/api/notification'
import { streamNotifications } from '@/api/notificationStream'

/**
 * 人侧站内消息状态（MSG-5，设计 §3.9/§3.10）。
 *
 * 铃铛（NotificationBell）与工作台红框入口共享同一份数据：未读数 / 列表 / 抽屉开合 / SSE 连接。
 * 闸门 visible = isAdmin && notificationEnabled（默认 OFF）：关闭时不渲染、不发请求、不建连。
 * SSE 帧体只带 id，收到后防抖重拉未读 / 列表（正文回源查库，漏推/重复推只影响提示时机）。
 */
export const useNotificationStore = defineStore('notification', () => {
  const auth = useAuthStore()

  const features = ref<NotificationFeatures>({ notificationEnabled: false, announcementEnabled: false })
  const unreadCount = ref(0)
  const rows = ref<NotificationRow[]>([])
  const total = ref(0)
  const loading = ref(false)
  const drawerOpen = ref(false)
  /** 类型筛选：'' = 全部 */
  const typeFilter = ref('')
  const page = ref(1)
  const pageSize = 20

  const visible = computed(() => auth.isAdmin && features.value.notificationEnabled)

  // ── 铃铛形态：docked=停靠工作台（默认），floating=悬浮；二选一不并存 ──
  // 拖出工作台铃铛 → floating（悬浮件出现在原坑位，随后跟手拖动）；拖回停靠坑位 → docked。
  const BELL_MODE_KEY = 'ha.notification.bellMode'
  const BELL_POS_KEY = 'ha.notification.bellPos'
  const BELL_SIZE = 36
  const BELL_MARGIN = 4

  function readBellMode(): 'docked' | 'floating' {
    try {
      return localStorage.getItem(BELL_MODE_KEY) === 'floating' ? 'floating' : 'docked'
    } catch {
      return 'docked'
    }
  }

  function readBellPos(): { right: number; top: number } {
    try {
      const saved = localStorage.getItem(BELL_POS_KEY)
      if (!saved) return { right: 20, top: 14 }
      const p = JSON.parse(saved) as { right?: unknown; top?: unknown }
      if (typeof p.right !== 'number' || typeof p.top !== 'number') return { right: 20, top: 14 }
      return { right: p.right, top: p.top }
    } catch {
      return { right: 20, top: 14 }
    }
  }

  const bellMode = ref<'docked' | 'floating'>(readBellMode())
  const bellPos = ref(readBellPos())

  function setBellMode(mode: 'docked' | 'floating') {
    bellMode.value = mode
    try {
      localStorage.setItem(BELL_MODE_KEY, mode)
    } catch {
      /* ignore */
    }
  }

  /** 悬浮位（右/上偏移 px）：夹进视口并落 localStorage（与 QuotaFloatWidget 的 persistPos 同口径）。 */
  function setBellPos(pos: { right: number; top: number }) {
    const clamp = (v: number, max: number) => Math.max(BELL_MARGIN, Math.min(max, Math.round(v)))
    bellPos.value = {
      right: clamp(pos.right, window.innerWidth - BELL_SIZE - BELL_MARGIN),
      top: clamp(pos.top, window.innerHeight - BELL_SIZE - BELL_MARGIN)
    }
    try {
      localStorage.setItem(BELL_POS_KEY, JSON.stringify(bellPos.value))
    } catch {
      /* ignore */
    }
  }

  let started = false
  let abort: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let refreshDebounce: ReturnType<typeof setTimeout> | null = null

  async function loadFeatures() {
    try {
      features.value = await notificationApi.features()
    } catch {
      // 读取失败按关闭处理（fail-close）：不渲染、不发请求
      features.value = { notificationEnabled: false, announcementEnabled: false }
    }
  }

  async function refreshUnread() {
    if (!visible.value) {
      unreadCount.value = 0
      return
    }
    try {
      unreadCount.value = await notificationApi.unreadCount()
    } catch {
      // 保留旧值，不伪报
    }
  }

  async function loadList(reset = false) {
    if (!visible.value) {
      rows.value = []
      total.value = 0
      return
    }
    if (reset) page.value = 1
    loading.value = true
    try {
      const res = await notificationApi.page({
        type: typeFilter.value || undefined,
        page: page.value,
        pageSize
      })
      rows.value = res.list
      total.value = res.total
    } catch {
      // 保留旧数据 / 空态
    } finally {
      loading.value = false
    }
  }

  async function markRead(id: string) {
    await notificationApi.markRead(id)
    const row = rows.value.find((r) => r.id === id)
    if (row && row.isRead === 0) {
      row.isRead = 1
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  }

  async function markAllRead() {
    await notificationApi.markAllRead()
    rows.value.forEach((r) => { r.isRead = 1 })
    unreadCount.value = 0
  }

  async function archive(id: string) {
    await notificationApi.archive(id)
    const idx = rows.value.findIndex((r) => r.id === id)
    if (idx >= 0) {
      const wasUnread = rows.value[idx].isRead === 0
      rows.value.splice(idx, 1)
      total.value = Math.max(0, total.value - 1)
      if (wasUnread) unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  }

  function openDrawer() {
    drawerOpen.value = true
    void loadList(true)
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  function setTypeFilter(t: string) {
    typeFilter.value = t
    void loadList(true)
  }

  function goToPage(p: number) {
    page.value = Math.max(1, p)
    void loadList(false)
  }

  function scheduleReconnect() {
    if (!visible.value || !started || reconnectTimer) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, 5000)
  }

  function connect() {
    if (!visible.value || !started) return
    abort?.abort()
    abort = new AbortController()
    void streamNotifications({
      onNotification: () => {
        if (refreshDebounce) clearTimeout(refreshDebounce)
        refreshDebounce = setTimeout(() => {
          void refreshUnread()
          if (drawerOpen.value) void loadList()
        }, 300)
      },
      onClosed: () => scheduleReconnect()
    }, abort.signal)
  }

  /** 全局铃铛挂载时启动（幂等）：先读开关，开启才拉未读 + 建连。 */
  async function start() {
    if (started) return
    started = true
    await loadFeatures()
    if (visible.value) {
      void refreshUnread()
      connect()
    }
  }

  function stop() {
    started = false
    abort?.abort()
    abort = null
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (refreshDebounce) {
      clearTimeout(refreshDebounce)
      refreshDebounce = null
    }
  }

  /** 开关变更后重评闸门（Settings 保存后调用）：开启则建连拉未读，关闭则停发清空。 */
  async function reload() {
    await loadFeatures()
    if (visible.value) {
      void refreshUnread()
      if (started) connect()
    } else {
      unreadCount.value = 0
      rows.value = []
      total.value = 0
      drawerOpen.value = false
      abort?.abort()
      abort = null
    }
  }

  return {
    features,
    unreadCount,
    rows,
    total,
    loading,
    drawerOpen,
    typeFilter,
    page,
    visible,
    bellMode,
    bellPos,
    setBellMode,
    setBellPos,
    loadFeatures,
    refreshUnread,
    loadList,
    markRead,
    markAllRead,
    archive,
    openDrawer,
    closeDrawer,
    setTypeFilter,
    goToPage,
    start,
    stop,
    reload
  }
})