package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.SkillExecutionPhase;
import com.helloai.common.constant.SkillExecutionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 对话内技能执行运行记录（V128，SKX-13）。
 *
 * <p>它是「一次执行」的载体，不是会话内容：一行 = 一次技能执行。
 * 之所以不把状态挂到对话消息上，是因为同一会话可能先后跑多次技能
 * （重试、编辑重发），而消息只有一条 —— 状态挂在消息上会丢历史。</p>
 *
 * <p>本表的读侧只有三个：① 前端轮询单个 runId 的状态与阶段；
 * ② 同会话互斥预检查（有没有活跃记录，即 {@code PENDING} 或 {@code RUNNING}）；
 * ③ 打开会话时恢复进度（该会话最近一条活跃记录）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_skill_run")
public class ChatSkillRun extends BaseEntity {

    /** 所属会话。 */
    private Long conversationId;

    /** 发起者用户 id（归属与权限校验用）。 */
    private Long userId;

    /** 技能包名（如 {@code archify}）。 */
    private String skillName;

    /** 触发方式：{@code CHAT}（对话内选技能）/ {@code EDIT}（编辑消息后重发）。 */
    private String triggerSource;

    /** 状态机（见 {@link SkillExecutionStatus}）。 */
    private String status;

    /** 执行阶段（见 {@link SkillExecutionPhase}）。 */
    private String phase;

    /** 阶段的可读说明（前端展示用）。 */
    private String phaseDetail;

    /** 失败原因摘要（用户可见级别，不含堆栈）。 */
    private String errorMessage;

    /** 产物附件 id（成功后）。 */
    private Long attachmentId;

    /** 产物消息 payload（成功后，与对话里那条 assistant 消息的 payload 同口径）。 */
    private String resultPayload;

    /** 服务端硬超时上限（秒）。 */
    private Integer timeoutSeconds;

    /** 实际开始执行的时间。 */
    private OffsetDateTime startedAt;

    /** 终态时间。 */
    private OffsetDateTime finishedAt;

    /** 状态枚举（避免各处字符串比较）。 */
    public SkillExecutionStatus statusEnum() {
        return status == null ? null : SkillExecutionStatus.valueOf(status);
    }

    /** 阶段枚举。 */
    public SkillExecutionPhase phaseEnum() {
        return phase == null ? null : SkillExecutionPhase.valueOf(phase);
    }
}
