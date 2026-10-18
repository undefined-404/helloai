import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import request from '@/api/request'

/**
 * 枚举字典（2026-10-18，服务端单一事实源）。
 *
 * <p>解决「页面裸露 sub_task.approved / coding 这类英文码」：前端不再硬编码映射
 * （原 `types/dialog.ts` 的 INBOX_EVENT_TYPE_MAP 只覆盖 8 个键，后端实际发 10 种）。
 * 与 eventCatalog / toolCatalog 同构：懒加载、失败静默回退原码、不随登出重置。</p>
 */

export interface EnumItem {
  code: string
  label: string
  desc: string
}

/** 分组名 → 枚举项列表（后端 catalog 的 key 全集）。 */
type EnumCatalog = Record<string, EnumItem[]>

export const useEnumCatalogStore = defineStore('enumCatalog', () => {
  const items = ref<EnumCatalog>({})
  const loading = ref(false)
  const loaded = ref(false)

  /** 组内 code → label 映射（按需计算的缓存）。 */
  const labelMaps = computed<Record<string, Map<string, string>>>(() => {
    const out: Record<string, Map<string, string>> = {}
    for (const [group, list] of Object.entries(items.value)) {
      out[group] = new Map(list.map((it) => [it.code, it.label]))
    }
    return out
  })

  const descMaps = computed<Record<string, Map<string, string>>>(() => {
    const out: Record<string, Map<string, string>> = {}
    for (const [group, list] of Object.entries(items.value)) {
      out[group] = new Map(list.map((it) => [it.code, it.desc]))
    }
    return out
  })

  /** 分组内 code → 中文名；未命中返回 fallback（通常为原码）。 */
  function labelOf(group: string, code: string | null | undefined, fallback?: string): string {
    if (!code) return fallback ?? ''
    return labelMaps.value[group]?.get(code) ?? fallback ?? code
  }

  /** 分组内 code → 说明；未命中返回空串。 */
  function descOf(group: string, code: string | null | undefined): string {
    if (!code) return ''
    return descMaps.value[group]?.get(code) ?? ''
  }

  /** 懒加载 + 幂等；失败保持 loaded=false（全站降级为原始码），不抛异常。 */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      items.value = await request.get<any, EnumCatalog>('/enums/catalog')
      loaded.value = true
    } catch {
      // 静默：降级路径见 labelOf / descOf
    } finally {
      loading.value = false
    }
  }

  return { items, labelOf, descOf, ensureLoaded, loaded }
})
