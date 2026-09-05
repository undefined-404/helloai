package com.helloai.core.agent.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Agent 执行会话实体（{@code agent_session} 表，Phase 1 Step 3）。
 *
 * <p>一次执行尝试（ADR-001 Turn 级）的执行快照 / 中断点 / 恢复上下文载体：
 * 与 {@code agent_execution_record}（命令生命周期 CAS）互补，记录
 * 「执行到哪（turn/step）+ 装配了什么（snapshot）」，供租约回收/重派
 * 路径识别中断点并落 timeline（N-007 执行恢复载体）。</p>
 *
 * <p>写入为 best-effort 可观测数据：不参与业务决策，失败不阻断执行主链路；
 * turn 在 reworkFresh/死信重派清零计数器后可能复用，读取一律取最新。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSession extends BaseEntity {

    /** Run 标识（run-{taskId}-{roundNum}，见 ADR-001）。 */
    private String runId;

    private Long taskId;

    private Long subTaskId;

    /** 执行 Agent ID。 */
    private Long agentId;

    /** 执行尝试序号（ADR-001 Turn，从 1 起；清零后可能复用）。 */
    private Integer turn;

    /** 中断点（最近执行到哪：2=上下文装配完成/LLM 前，4=LLM 完成）。 */
    private Integer step;

    /** 会话状态（SessionStatus 枚举，见 {@code com.helloai.common.constant.SessionStatus}）。 */
    private String status;

    /** 恢复上下文快照（skills/tools/depCount 等装配事实）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> snapshot;

    /** 失败/中断原因摘要（截断 500 字符）。 */
    private String error;
}
