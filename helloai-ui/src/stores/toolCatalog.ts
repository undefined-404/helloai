import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { toolApi, type ToolCatalogItem } from '@/api/event'

/**
 * 工具名目录（2026-10-18，工具名服务端单一来源）。
 *
 * <p>解决「时间线只显示 internal_run_in_sandbox 这种英文标识」的问题。与 eventCatalog store
 * 完全同构：懒加载、失败静默降级（回退原始英文名，绝不显示「未知工具」掩盖事实）、
 * 不随登出重置（目录与身份无关）。</p>
 */
export const useToolCatalogStore = defineStore('toolCatalog', () => {
  const items = ref<ToolCatalogItem[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  const byName = computed(() => new Map(items.value.map((it) => [it.toolName, it])))
  const labelMap = computed(() => new Map(items.value.map((it) => [it.toolName, it.label])))

  /** 工具名 → 人话短名；未命中返回 `fallback`（通常为原始工具名）。 */
  function labelOf(toolName: string | null | undefined, fallback: string): string {
    if (!toolName) return fallback
    return labelMap.value.get(toolName) ?? fallback
  }

  /** 工具名 → 一句话说明；未命中返回空串。 */
  function descOf(toolName: string | null | undefined): string {
    if (!toolName) return ''
    return byName.value.get(toolName)?.desc ?? ''
  }

  /** 懒加载 + 幂等；失败保持 loaded=false（全站降级为原始工具名），不抛异常。 */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      items.value = await toolApi.catalog()
      loaded.value = true
    } catch {
      // 静默：降级路径见 labelOf / descOf
    } finally {
      loading.value = false
    }
  }

  return { items, byName, labelOf, descOf, ensureLoaded, loaded }
})
