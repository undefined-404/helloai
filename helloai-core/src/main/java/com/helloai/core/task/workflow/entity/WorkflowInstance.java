package com.helloai.core.task.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Workflow 实例（N-001，C1-S2）。
 *
 * <p>实例化 = 模板当前激活版本 + 参数 → 一次性物化 task/sub_task（D1）；
 * 本表只做"绑定快照"：version_id + params + 物化出的 task_id。</p>
 *
 * <p>实例状态【纯查询聚合】（D6-2 用户拍板方案 A）：不落权威 status 列，
 * 节点完成度每次从 sub_task 现算；{@link #statusSnapshot} 仅展示快照（无状态机、无约束力）。
 * 反锁禁令（D6-3）：实例是派生态投影，绝不反向约束 task/sub_task 操作。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_instance")
public class WorkflowInstance extends BaseEntity {

    /** 模板 ID。 */
    private Long templateId;

    /** 版本 ID（绑定不可变版本快照）。 */
    private Long versionId;

    /** 实例化参数（占位符渲染输入）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> params;

    /** 物化出的主任务 ID（实例 ↔ Task 1:1）。 */
    private Long taskId;

    /** 展示快照（可选，无状态机、无约束力；权威状态从 sub_task 纯查询聚合）。 */
    private String statusSnapshot;

    /** 实例化时间。 */
    private OffsetDateTime startTime;

    /** 结束时间（可空——实例为派生态投影，运行期不写）。 */
    private OffsetDateTime endTime;
}
