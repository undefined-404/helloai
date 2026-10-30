package com.helloai.api.dto.requirement;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.common.constant.SkillExecutionStatus;
import com.helloai.core.planner.entity.ChatSkillRun;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 技能执行状态响应视图（SKX-13，Controller 层投影，不直接暴露 Entity）。
 *
 * <p>刻意<b>不返回</b>产物正文与 payload：产物走会话详情里那条 assistant 消息（前端按消息渲染），
 * 本视图只回答「还在跑吗、跑到哪一步、失败原因是什么」。</p>
 */
@Data
public class SkillRunStatusVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    private String skillName;

    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED。 */
    private String status;

    /** SPEC / RENDER / CHECK / EXPORT / DONE；供前端显示「正在渲染」这类阶段文案。 */
    private String phase;

    /** 阶段的可读说明（前端可直接展示）。 */
    private String phaseDetail;

    /** 失败原因（用户可见级别）；成功时为空。 */
    private String errorMessage;

    /** 产物附件 id（成功后）；前端用它渲染产物入口。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long attachmentId;

    /** 是否已终态（前端据此停止轮询）。 */
    private boolean terminal;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    public static SkillRunStatusVO from(ChatSkillRun entity) {
        if (entity == null) {
            return null;
        }
        SkillRunStatusVO vo = new SkillRunStatusVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setSkillName(entity.getSkillName());
        vo.setStatus(entity.getStatus());
        vo.setPhase(entity.getPhase());
        vo.setPhaseDetail(entity.getPhaseDetail());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setAttachmentId(entity.getAttachmentId());
        SkillExecutionStatus status = entity.statusEnum();
        vo.setTerminal(status != null && status.terminal());
        vo.setStartedAt(entity.getStartedAt());
        vo.setFinishedAt(entity.getFinishedAt());
        return vo;
    }
}
