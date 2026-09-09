package com.helloai.api.dto.subtask;

import com.helloai.core.task.entity.Uncertainty;
import lombok.Data;

import java.util.List;

/**
 * 草案编辑请求（G-010 草案确认 UI：确认前人工修订技能指派与执行约束；
 * G-011 扩展不确定性申报逐条增删改）。
 *
 * <p>仅允许编辑 requiredSkills / constraints / uncertainties 三字段（可选编辑，空值放行——
 * null 表示不修改该字段，便于局部更新）。仅 PENDING_PLAN_REVIEW 状态可编辑。</p>
 */
@Data
public class DraftUpdateRequest {
    /** 子任务级技能标签；null=不修改，空数组=清空 */
    private List<String> requiredSkills;
    /** 执行约束（COARSE 粒度必填：不许改的事）；null=不修改 */
    private String constraints;
    /**
     * 不确定性申报（G-011）；null=不修改，空数组=清空。
     * 人工编辑不做 kind 强校验（权威输入，同 requiredSkills 模式），
     * 非法 kind 由服务端落库侧降级 UNCONFIRMED（D3 fail-close 同口径）。
     */
    private List<Uncertainty> uncertainties;
}
