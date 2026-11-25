package com.helloai.core.agent.port;

import java.util.List;

/**
 * 类型化约束引用只读快照（`G-037` 任务约束类型化试点；agent 域自有数据契约，**零 task 实体泄漏**）。
 *
 * <p>对应 {@code task.entity.ConstraintRef}（JSONB 列 {@code sub_task.constraint_refs} 的元素）。
 * 与 {@link UncertaintySnapshot} 同款：只做透传投影（{@code kind} / {@code ref} / {@code scope}
 * 原样搬），不在 agent 侧定义任何常量、不做 kind 归一化或校验——值域单源在
 * {@code helloai-common} 的 {@code ConstraintRefKind}。</p>
 *
 * <p><b>为什么由 task 侧映射器投影而非 agent 侧自解析</b>：执行 Prompt 装配（D6 段）要渲染
 * 「约束文档引用: {@code ref[scope...]}」摘要；若让 agent 侧直接 import {@code task.entity}
 * 就踩了反向依赖（架构守卫阻断）。投影模式与 {@code Uncertainties} 完全同构。</p>
 *
 * @param kind  约束类型（{@code TEXT} / {@code DOCUMENT} / {@code SKILL}；原样透传）
 * @param ref   约束文档目录注册名（manifest 白名单；{@code TEXT} 类型为空）
 * @param scope 章节 id 子集（空 = 整文档）
 */
public record ConstraintRefSnapshot(
        String kind,
        String ref,
        List<String> scope
) {
}