package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.common.constant.TaskMemberStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Task-Team 运行时成员（任务级授权边界）。
 *
 * <p>对应 {@code task_agent_member} 表（V98）。设计依据：
 * {@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §3。</p>
 *
 * <p><b>为什么持久化</b>：需应对分布式场景（网络中断 / 系统崩溃）——成员身份必须可恢复、
 * 可审计，并能支持"提前授权待命成员"（推导式做不到）。</p>
 *
 * <p><b>写入策略</b>：事件驱动派生，<b>不做双写</b>。唯一权威源是
 * {@code sub_task.assigned_agent_id} 的变更入口（分配 / 认领 / 改派 / 死信指派），
 * 本表是其派生快照（幂等 upsert）；并可由
 * {@code sub_task.assigned_agent_id} ∪ {@code agent_execution_record.agent_id}
 * 重建对账（启动一次性，幂等）自愈。</p>
 *
 * <p><b>语义</b>：加入过即成员 —— 改派不写 {@code leaveTime}、不置 LEFT，
 * 故 {@code (task_id, agent_id)} 唯一，一个 agent 在一个任务内只有一条记录。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_agent_member")
public class TaskAgentMember extends BaseEntity {

    /** 主任务 ID。 */
    private Long taskId;

    /** 团队成员（Agent）ID。 */
    private Long agentId;

    /** 入队来源：首次入队的真实入口；仅原值为 REBUILT 时才被真实入口升级覆盖。 */
    private TaskMemberJoinSource joinSource;

    /** 成员状态：当前恒为 ACTIVE（LEFT 预留）。 */
    private TaskMemberStatus status;

    /** 入队时间。 */
    private OffsetDateTime joinTime;

    /** 离队时间（当前不写，为用户"换下即失权"将来扩展预留）。 */
    private OffsetDateTime leaveTime;
}
