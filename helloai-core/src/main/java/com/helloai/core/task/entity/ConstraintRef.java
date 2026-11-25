package com.helloai.core.task.entity;

import com.helloai.common.constant.ConstraintRefKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 类型化约束引用（`G-037` 任务约束类型化试点；JSONB 列 {@code sub_task.constraint_refs} 的元素）。
 *
 * <p><b>kind 值域唯一定义源 = {@link ConstraintRefKind}</b>——本类只做透传投影、不校验
 * （与 {@link Uncertainty} 的 kind 处理同款：拆解侧写入、执行侧注入、审查侧核验链保持单源，
 * 实体层不复制常量、不归一化）。非法 kind 值在拆解落库侧被过滤（WARN + 丢弃），本类不解释。</p>
 *
 * @param kind   约束类型（{@code TEXT} / {@code DOCUMENT} / {@code SKILL}；原样透传）
 * @param ref    约束文档目录注册名（manifest 白名单）；{@code TEXT} 类型为空
 * @param scope  章节 id 子集（空列表 = 整文档）；仅 {@code DOCUMENT} 类型使用
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintRef {

    private String kind;

    private String ref;

    private List<String> scope;
}