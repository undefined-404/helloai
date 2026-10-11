import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { eventApi, type EventCatalogItem } from '@/api/event'

/**
 * 事件码目录（REF-6.8，事件码服务端单一来源）。
 *
 * - **懒加载**：消费点首次需要时调 `ensureLoaded()`；Pinia 单例 ⇒ 所有事件渲染共享一次请求。
 * - **失败静默降级**：请求失败保持 `loaded=false`，`labelOf` 回退调用方给的原始值
 *   ——与迁移前「未命中回退原文」语义一致，不阻断事件时间线渲染（fail-open）。
 * - **不做 logout 重置**：目录数据与用户身份无关（对齐技能目录 skillCatalog 口径）。
 * - `/api/events/catalog` 已在 `request.ts` 错误白名单内，失败不弹 toast。
 */
export const useEventCatalogStore = defineStore('eventCatalog', () => {
  const items = ref<EventCatalogItem[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  const byCode = computed(() => new Map(items.value.map((it) => [it.code, it])))
  const labelMap = computed(() => new Map(items.value.map((it) => [it.code, it.label])))

  /** 事件码 → 人话标签；未命中返回 `fallback`（通常为原始码）。 */
  function labelOf(code: string | null | undefined, fallback: string): string {
    if (!code) return fallback
    return labelMap.value.get(code) ?? fallback
  }

  /** 事件码 → 详细说明；未命中返回空。 */
  function descOf(code: string | null | undefined): string {
    if (!code) return ''
    return byCode.value.get(code)?.desc ?? ''
  }

  /** 懒加载 + 幂等；失败保持 loaded=false（全站降级为原始码），不抛异常。 */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      items.value = await eventApi.catalog()
      loaded.value = true
    } catch {
      // 静默：降级路径见 labelOf / descOf
    } finally {
      loading.value = false
    }
  }

  return { items, byCode, labelOf, descOf, ensureLoaded, loaded }
})