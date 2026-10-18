package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.Uncertainty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class SubTaskResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    /** 主任务标题冗余字段（列表/详情展示归属任务用，由 Controller 批量回填） */
    private String taskTitle;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;
    private SubTaskStatus status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedAgent;
    /** Agent 名称冗余字段（列表展示负责人用，由 Controller 批量回填） */
    private String assignedAgentName;
    private String content;
    private Integer reworkCount;
    /** 子任务级技能标签（拆解侧指派；子任务级 ∪ 任务级 = 下发执行/审查的合并清单，G-010） */
    private List<String> requiredSkills;
    /** 执行约束（COARSE 粒度必填：不许改的事；其余档位可空） */
    private String constraints;
    /** 不确定性申报（G-011：kind=ASSUMPTION/UNCONFIRMED + note；拆解侧指派，草案确认 UI 可编辑，外部 agent 下行可见） */
    private List<Uncertainty> uncertainties;
    /** 执行上下文（JSONB）：含人工介入标记 manualIntervention，§6.52 前端人工介入面板判定依赖此字段 */
    private Map<String, Object> context;
    /** 依赖的子任务 id 列表（同 Task 内；空列表=无依赖） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> dependsOn;
    private OffsetDateTime deadline;
    private OffsetDateTime completedAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    /**
     * 复合评分（五因子加权，0~100）。
     *
     * <p><b>只在子任务完成（DONE）时写入</b>（{@code ReviewServiceImpl.complete} 触发
     * {@code SubTaskServiceImpl} 的隐式评分），未完成 / 已取消 / 未指派执行者时为空
     * <b>属预期</b>，不是「没算出来」。前端据此区分「空态」与「待评分」。</p>
     *
     * <p>2026-10-18 补：此前该字段<b>只存在于实体与库表</b>，响应 DTO 未携带 ⇒
     * 子任务列表「评分」列恒空，而详情页（走 review 接口）有分，形成对不上的观感。</p>
     */
    private Integer compositeScore;
    /** 评分等级（S/A/B/C/D，由 compositeScore 映射；null 语义同上）。 */
    private String scoreGrade;
    /** 五维度明细：timeScore / qualityScore / coopScore / stabilityScore / efficiencyScore。 */
    private Map<String, Object> scoreFactors;
}
