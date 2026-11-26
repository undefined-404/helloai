package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.common.constant.AttachmentVisibility;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("attachment")
public class Attachment extends BaseEntity {

    private Long subTaskId;
    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;
    private String bucketName;
    private String objectKey;
    private String storageUrl;
    private String previewUrl;
    private AttachmentStatus status;

    /**
     * 可见范围（V99）：PERSONAL=仅上传者 / TASK=上传者+所属根任务团队（默认）/ PUBLIC=预留未启用。
     *
     * <p>权限判定<b>不直接用本字段比对任务</b>——统一走
     * {@code AttachmentVisibilityPolicy.canRead(...)}，内部以「本字段（声明范围）×
     * Task-Team 成员关系」判定，故将来层级演进不需改判据。</p>
     */
    private AttachmentVisibility visibility;

    /**
     * 上传者 Agent ID（V100）：由 {@code AttachmentService#register} 写入；平台侧/人工上传为空。
     *
     * <p>支撑 {@link AttachmentVisibility#PERSONAL}（仅上传者）以及 TASK 分支的
     * "上传者恒可读自传"兜底（agent 被改派换下后仍能读自己的旧产出）。</p>
     */
    private Long uploaderAgentId;

    /**
     * 轮次快照（`V157` / `VU-3` 第 4 条 / `G-054`）：登记时点所属轮次 = 登记时
     * {@code sub_task.currentRound()}（{@code round_base + rework_count + 1}），首轮为 1，
     * 此后不随后续返工或重置改写。
     *
     * <p><b>不得写成 {@code rework_count + 1}</b>（`V158` 修正）：{@code rework_count} 在人工
     * 驳回与死信重派两条路径上会被清零（那是返工预算语义），只按它算会让轮次倒退回 1，
     * 新旧两轮产物同号、彻底不可分。轮次必须单调，故引入 {@code sub_task.round_base} 承接
     * 被重置消耗掉的轮次。</p>
     *
     * <p><b>与 {@link #status} 分家</b>：{@code status} 管「能否被核验/依赖装载读到」（驳回即
     * {@code invalidateBySubTask} 原位置 INACTIVE），{@code round} 管「属于第几轮」。压在一个字段上
     * 会让返工后旧轮产物在审计面彻底消失（只剩 {@code review_record} 的冻结文本）。</p>
     *
     * <p><b>按执行批次而非逐文件递增</b>：一次执行产出多文件时同属一轮
     * （反例见 `G-054` 登记：`max(round)+1` 会把同批 2 个文件拆成相邻两轮，
     * 而轮次正是用来判断「是否同批」的依据）。</p>
     *
     * <p>历史行（INACTIVE/DELETED）在 `V158` 中按 {@code sub_task_execute_start} 锚点重算；
     * 无锚点的行保持默认 1 并以此注释为准。</p>
     */
    private Integer round;

    /** 回填字段（不落库）：主任务 ID 与标题、子任务标题，供附件管理按层级浏览时展示名称。 */
    @TableField(exist = false)
    private Long taskId;
    @TableField(exist = false)
    private String taskTitle;
    @TableField(exist = false)
    private String subTaskTitle;
}