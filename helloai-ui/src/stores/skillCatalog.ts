import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { skillApi, type SkillPackageView } from '@/api/skill'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'

/**
 * 平台技能包目录（REF-1.2c）。
 *
 * - **懒加载**：消费点首次需要时调 `ensureLoaded()`；Pinia 单例 ⇒ 5 个消费点共享一次请求。
 * - **失败静默降级**：请求失败只置 `loaded=false`，`options` 为空、`labelOf` 回退原始标签
 *   ——与迁移前「未命中回退原文」语义一致，自定义技能（如 kubernetes/golang）不会显示空白。
 * - **不做 logout 重置**：目录数据与用户身份无关，不存在跨账号串用（对比 auth store 的 token）。
 * - `/skills/catalog` 已在 `api/request.ts` 的错误白名单内，失败不弹 toast。
 */
export const useSkillCatalogStore = defineStore('skillCatalog', () => {
  const entries = ref<SkillPackageView[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  /** 健康技能包（corrupt 项不参与下拉与平台技能判定）。 */
  const healthy = computed(() => entries.value.filter((e) => !e.corrupt))

  /** 下拉选项：label 来自服务端。 */
  const options = computed(() => healthy.value.map((e) => ({ label: e.label, value: e.name })))

  /** 平台技能名集合（用于判断某个标签是否属于平台技能目录）。 */
  const nameSet = computed(() => new Set(healthy.value.map((e) => e.name)))

  /**
   * 技能标签 → 中文显示名。**两个来源，顺序与迁移前 `skillLabelOf` 一致**：
   * ① 能力词表 `AGENT_SKILL_OPTIONS`（shell / docker / …，本地常量，与后端派生词表对齐）；
   * ② 平台技能目录（eng-*，服务端下发）。
   * 均未命中（自定义技能如 kubernetes / golang，或目录尚未加载）→ 回退原文。
   */
  function labelOf(s: string | null | undefined): string {
    if (!s) return ''
    return (
      AGENT_SKILL_OPTIONS.find((o) => o.value === s)?.label
      ?? healthy.value.find((e) => e.name === s)?.label
      ?? s
    )
  }

  /** 懒加载 + 幂等；失败保持 loaded=false（全站降级为原始标签），不抛异常。 */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      entries.value = await skillApi.catalog()
      loaded.value = true
    } catch {
      // 静默：降级路径见 labelOf / options
    } finally {
      loading.value = false
    }
  }

  return { entries, healthy, options, nameSet, labelOf, ensureLoaded }
})
