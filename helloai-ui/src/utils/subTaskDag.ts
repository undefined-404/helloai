import type { SubTask } from '@/types'

/**
 * Kahn 入度分层：第 i 批节点的所有前置依赖都落在更早批次，同批次之间无依赖、可并行。
 * 与后端 SubTaskDependencyOrder 的稳定 Kahn 语义一致（同层保持入参相对顺序）。
 * 防御成环/脏数据：跨集合的依赖 id 直接忽略；若出现环，剩余节点整体作为最后一批，不死循环。
 */
export function computeDagLayers(subTasks: SubTask[]): SubTask[][] {
  const idSet = new Set(subTasks.map(s => String(s.id)))
  // 只保留同集合内的依赖（自引用/跨任务残留 id 忽略）
  const depsOf = new Map<string, string[]>()
  subTasks.forEach(s => {
    const sid = String(s.id)
    depsOf.set(sid, (s.dependsOn || []).map(String).filter(d => idSet.has(d) && d !== sid))
  })
  const remaining = new Set(idSet)
  const layers: SubTask[][] = []
  while (remaining.size > 0) {
    const ready = subTasks.filter(s => remaining.has(String(s.id))
      && (depsOf.get(String(s.id)) || []).every(d => !remaining.has(d)))
    if (ready.length === 0) {
      // 成环兜底：剩余节点整体收尾（后端 validateDependencies 已防环，正常不会走到）
      layers.push(subTasks.filter(s => remaining.has(String(s.id))))
      break
    }
    ready.forEach(s => remaining.delete(String(s.id)))
    layers.push(ready)
  }
  return layers
}

/** 稳定拓扑正序（根在前，依赖恒指向更靠前的项），供「#序号」展示复用。 */
export function orderByDependency(subTasks: SubTask[]): SubTask[] {
  return computeDagLayers(subTasks).flat()
}

/**
 * 传递归约：若 A→B 可经更长路径 A→…→C→B 推导（B 的另一前置 C 的祖先中含 A），
 * 则直连边 A→B 为视觉冗余，从画图用的直接依赖中去除；调度语义不受影响（仅展示层）。
 * 注意：并行分支的边不会被误删——只有被其他路径完全覆盖的边才移除。
 */
export function reduceDependencies(subTasks: SubTask[]): Map<string, string[]> {
  const idSet = new Set(subTasks.map(s => String(s.id)))
  const depsOf = new Map<string, string[]>()
  subTasks.forEach(s => {
    const sid = String(s.id)
    depsOf.set(sid, (s.dependsOn || []).map(String).filter(d => idSet.has(d) && d !== sid))
  })
  // 祖先集合（全部传递前置）记忆化 DFS；visiting 防成环死递归（后端已防环，仅兜底）
  const ancCache = new Map<string, Set<string>>()
  const visiting = new Set<string>()
  function ancestorsOf(id: string): Set<string> {
    const hit = ancCache.get(id)
    if (hit) return hit
    if (visiting.has(id)) return new Set()
    visiting.add(id)
    const acc = new Set<string>()
    for (const d of depsOf.get(id) || []) {
      acc.add(d)
      ancestorsOf(d).forEach(a => acc.add(a))
    }
    visiting.delete(id)
    ancCache.set(id, acc)
    return acc
  }
  const reduced = new Map<string, string[]>()
  depsOf.forEach((deps, id) => {
    reduced.set(id, deps.filter(u => !deps.some(w => w !== u && ancestorsOf(w).has(u))))
  })
  return reduced
}
