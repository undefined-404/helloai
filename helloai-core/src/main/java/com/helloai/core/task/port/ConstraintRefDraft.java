package com.helloai.core.task.port;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 类型化约束引用写入物料（`G-037` 任务约束类型化试点；task 域对外契约，随 {@code SubTaskDraft}）。
 *
 * <p><b>本 record 同时是拆解 Prompt 输出的 JSON 契约的元素形状</b>（{@code constraintRefs}
 * 数组元素），故显式声明 {@code @JsonIgnoreProperties(ignoreUnknown = true)}：模型多写一个键
 * 不该让整条约束引用解析失败（与 {@code UncertaintyDraft} 同款防御）。</p>
 *
 * <p><b>字段名与 {@code task.entity.ConstraintRef} 逐字对齐</b>（{@code kind} / {@code ref} /
 * {@code scope}）——该结构同时是拆解 Prompt 输出的 JSON 契约
 * （{@code prompts/planner-decompose.md}），字段名一变即静默改变拆解行为，故此处刻意同名同形。</p>
 *
 * @param kind  约束类型（值域单源 {@code helloai-common} 的 {@code ConstraintRefKind}）
 * @param ref   约束文档目录注册名（manifest 白名单；{@code TEXT} 类型可为空）
 * @param scope 章节 id 子集（空 = 整文档）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConstraintRefDraft(
        String kind,
        String ref,
        List<String> scope
) {
}