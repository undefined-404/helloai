package com.helloai.core.task.port;

import java.util.List;

/**
 * 类型化约束引用只读快照（`G-037` 任务约束类型化试点；task 域对外契约，随 {@code SubTaskView}）。
 *
 * <p>与 {@code UncertaintyView} 同理：消费方（review 域 / planner 域）只读本投影，
 * 不 import {@code task.entity.ConstraintRef}。字段逐字对应实体：{@code kind} / {@code ref} /
 * {@code scope} 原样透传、不校验不归一化——kind 值域单源在 {@code helloai-common} 的
 * {@code ConstraintRefKind}。</p>
 *
 * @param kind  约束类型（{@code TEXT} / {@code DOCUMENT} / {@code SKILL}，透传）
 * @param ref   约束文档目录注册名（manifest 白名单；{@code TEXT} 类型为空）
 * @param scope 章节 id 子集（空 = 整文档）
 */
public record ConstraintRefView(
        String kind,
        String ref,
        List<String> scope
) {
}