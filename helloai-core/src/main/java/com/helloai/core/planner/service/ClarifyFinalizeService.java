package com.helloai.core.planner.service;

import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.task.port.TaskView;

import java.util.Map;

/**
 * 澄清会话的<b>成任务链路</b>接口（RM9-c 从 {@link RequirementClarifyService} 实现类拆出的职责契约）：
 * 终稿 → {@code Task}（含 CAS 幂等推进与会话摘要归档）。
 *
 * <p>实现见 {@code com.helloai.core.planner.service.impl.ClarifyFinalizeServiceImpl}。</p>
 */
public interface ClarifyFinalizeService {

    /** 终稿确认：校验会话 ACTIVE 后建任务（终稿 → CAS 推进 FINALIZED → 建 Task → 通知 → timeline）。 */
    TaskView finalize(Long conversationId);

    /** 重新生成任务：会话已 FINALIZED 且原任务已被删除时，复用终稿重建 PENDING Task（会话保持 FINALIZED）。 */
    TaskView regenerate(Long conversationId);

    /**
     * 终稿产出后的建任务段（手动确认与 /task 直达共用）：幂等校验 + CAS 推进 ACTIVE → FINALIZED + 建任务。
     *
     * <p>直达轮与手动确认按钮并发时只有一方 CAS 成功，另一方拒绝建任务，避免重复建任务与状态回退。</p>
     */
    TaskView finalizeAfterDraft(RequirementConversation conversation, String timelineEvent);

    /**
     * 终稿字段定点写入（只更新 final_title / final_description / final_package 三列）。
     *
     * <p>description / finalPackage 保持条件写：null 不动列，防陈旧快照覆盖并发写入的 status / task_id。</p>
     */
    void updateFinalDraftFields(Long conversationId, String title, String description,
                                Map<String, Object> finalPackage);
}
