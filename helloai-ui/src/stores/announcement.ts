import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { announcementApi, type PublishedAnnouncement } from '@/api/announcement'
import { notificationApi } from '@/api/notification'

/**
 * 置顶公告状态（MSG-6，设计 §3.9/§3.10）。
 *
 * 公告条（AnnouncementBar）与铃铛角标共享同一份数据：当前置顶 / 已读位 / 未读。
 * 闸门 visible = announcementEnabled && 有已发布置顶（默认 OFF）：关闭时不渲染、不发请求。
 * 公告未读 = seenVersion < version（纯版本已读，首批不产生消息行）;unreadCount 并入铃铛角标。
 * 开关态复用 /user-notification/features（一次返回两开关），不另建 features 端点。
 */
export const useAnnouncementStore = defineStore('announcement', () => {
  const announcementEnabled = ref(false)
  const published = ref<PublishedAnnouncement | null>(null)
  const loading = ref(false)

  const visible = computed(() => announcementEnabled.value && published.value !== null)
  /** 公告未读（0/1），供铃铛角标与消息未读相加。 */
  const unreadCount = computed(() => (published.value?.unread ? 1 : 0))

  let started = false

  async function loadFeatures() {
    try {
      const f = await notificationApi.features()
      announcementEnabled.value = f.announcementEnabled
    } catch {
      // 读取失败按关闭处理（fail-close）：不渲染、不发请求
      announcementEnabled.value = false
    }
  }

  async function refreshPublished() {
    if (!announcementEnabled.value) {
      published.value = null
      return
    }
    loading.value = true
    try {
      published.value = await announcementApi.published()
    } catch {
      // 保留旧值 / 空态，不伪报
    } finally {
      loading.value = false
    }
  }

  /** 上报已读版本（幂等单调）：乐观更新已读位与未读，铃铛角标即时回落。 */
  async function markSeen(id: string, version: number) {
    await announcementApi.markSeen(id, version)
    const p = published.value
    if (p && p.id === id) {
      const seenVersion = Math.max(p.seenVersion, version)
      published.value = { ...p, seenVersion, unread: seenVersion < p.version }
    }
  }

  /** 公告条挂载时启动（幂等）：先读开关，开启才拉置顶。 */
  async function start() {
    if (started) return
    started = true
    await loadFeatures()
    if (announcementEnabled.value) await refreshPublished()
  }

  function stop() {
    started = false
  }

  /** 开关变更后重评闸门（Settings 保存 / 回到前台后调用）。 */
  async function reload() {
    await loadFeatures()
    if (announcementEnabled.value) {
      await refreshPublished()
    } else {
      published.value = null
    }
  }

  return {
    announcementEnabled,
    published,
    loading,
    visible,
    unreadCount,
    loadFeatures,
    refreshPublished,
    markSeen,
    start,
    stop,
    reload
  }
})