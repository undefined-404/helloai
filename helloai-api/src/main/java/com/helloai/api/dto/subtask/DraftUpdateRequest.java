package com.helloai.api.dto.subtask;

import lombok.Data;

import java.util.List;

/**
 * 草案编辑请求（G-010 草案确认 UI：确认前人工修订技能指派与执行约束）。
 *
 * <p>仅允许编辑 requiredSkills / constraints 两字段（可选编辑，空值放行——
 * null 表示不修改该字段，便于局部更新）。仅 PENDING_PLAN_REVIEW 状态可编辑。</p>
 */
@Data
public class DraftUpdateRequest {
    /** 子任务级技能标签；null=不修改，空数组=清空 */
    private List<String> requiredSkills;
    /** 执行约束（COARSE 粒度必填：不许改的事）；null=不修改 */
    private String constraints;
}
