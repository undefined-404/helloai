// ============================================================
// Agent 名称解析共享 composable（G-006 事件流工作台等消费）
//
// 一次性拉取全量 Agent 建 id→name 映射；拉取失败不阻断页面展示，
// 未命中（系统 / 已删除 Agent）降级为短 ID（Agent #后4位）。
// 解析口径与 SubTaskDetail 既有实现保持一致。
// ============================================================
import { ref } from 'vue'
import { agentApi } from '@/api/agent'

export function useAgentNames() {
  const agentNameMap = ref<Record<string, string>>({})

  async function loadAgentNames() {
    try {
      const list = await agentApi.list()
      const map: Record<string, string> = {}
      list.forEach((a) => { map[String(a.id)] = a.name })
      agentNameMap.value = map
    } catch {
      // 拉取失败降级短 ID，不阻断主流程
    }
  }

  // 优先注册名；未命中降级短 ID
  function resolveAgentName(agentId: string | number | null | undefined): string {
    if (!agentId) return ''
    const s = String(agentId)
    return agentNameMap.value[s] || ('Agent #' + s.slice(-4))
  }

  return { agentNameMap, loadAgentNames, resolveAgentName }
}
