package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskAgentMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Task-Team 成员 Mapper（V98）。
 *
 * <p><b>为什么 upsert 用原生 {@code @Insert + ON CONFLICT} 而不是"先查后写"</b>：
 * 「先查后写」在并发下会撞 {@code uk(task_id, agent_id)} 唯一索引抛
 * {@code DuplicateKeyException}，而 PostgreSQL 一旦报 duplicate key，<b>整个事务进入
 * aborted(25P02)</b>，后续语句全部失败——捕获异常后在同一事务里补救是无效的
 * （2026-10-02 在 {@code TaskRunningSpecTableServiceImpl} 已实证并改掉）。
 * {@code ON CONFLICT DO UPDATE} 是<b>单条原子语句</b>、不产生错误、不污染事务。</p>
 *
 * <p><b>为什么 id 由调用方传入</b>：自定义 {@code @Insert} 不会走 MyBatis-Plus 的
 * {@code ASSIGN_ID} 主键填充（填充发生在 {@code BaseMapper.insert} 内），故由服务层用
 * {@code IdWorker} 生成，与 MP 雪花 ID 同源。</p>
 */
@Mapper
public interface TaskAgentMemberMapper extends BaseMapper<TaskAgentMember> {

    /**
     * 幂等入队（upsert）：不存在则插入，已存在则保持 ACTIVE 并复活血统。
     *
     * <p>{@code join_source} 保留<b>首次入队</b>来源；仅当原值为 {@code 'REBUILT'}
     * （重建对账推断、无法观测真实入口）时，才被真实入口升级覆盖。</p>
     *
     * @param id         预生成主键（IdWorker 雪花 ID）
     * @param joinSource 入队来源枚举名（显式传 String，规避自定义 SQL 下的枚举 TypeHandler 不确定性）
     * @param operator   操作者标识（写入 create_by / update_by）
     * @return 影响行数（INSERT=1，UPDATE=1）
     */
    @Insert("""
            INSERT INTO task_agent_member
                (id, task_id, agent_id, join_source, status, join_time,
                 create_by, update_by, create_time, update_time, deleted)
            VALUES
                (#{id}, #{taskId}, #{agentId}, #{joinSource}, 'ACTIVE', CURRENT_TIMESTAMP,
                 #{operator}, #{operator}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            ON CONFLICT (task_id, agent_id) DO UPDATE
            SET status      = 'ACTIVE',
                deleted     = 0,
                join_source = CASE WHEN task_agent_member.join_source = 'REBUILT'
                                   THEN EXCLUDED.join_source
                                   ELSE task_agent_member.join_source END,
                update_by   = EXCLUDED.update_by,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("id") Long id,
               @Param("taskId") Long taskId,
               @Param("agentId") Long agentId,
               @Param("joinSource") String joinSource,
               @Param("operator") String operator);

    /** 某任务的全部在队成员 agentId（可见性判据 / 成员列表）。 */
    @Select("""
            SELECT agent_id FROM task_agent_member
            WHERE task_id = #{taskId} AND deleted = 0 AND status = 'ACTIVE'
            """)
    List<Long> selectActiveAgentIds(@Param("taskId") Long taskId);

    /**
     * 权威源兜底：该 agent 是否<b>当前</b>是某任务内任一子任务的执行者（derive-on-miss）。
     *
     * <p><b>为什么需要它（而不只依赖成员表）</b>：成员表写入是"事件驱动派生"，
     * 涉及至少三条独立写入路径（{@code SubTaskServiceImpl} 的归属变更 / {@code rework}、
     * 以及 {@code TaskDispatchPort#assignNext} 的调度改派）。只要漏挂任一条，成员表就<b>静默缺行</b>
     * ——这与 2026-10-02 反复出现的"判据/写入分散导致漂移"是同一类隐患。</p>
     *
     * <p>本方法把权威源（{@code sub_task.assigned_agent_id}）作为<b>判定兜底</b>：
     * 成员表未命中时再查一次当前归属，命中即视为成员。语义上"正在为该任务干活"必然蕴含
     * "属于该任务团队"，故是<b>保守且正确</b>的兜底；它<b>不可能漏路径</b>，因为无论哪条写入路径，
     * 最终都要落到 {@code assigned_agent_id}。</p>
     *
     * <p>补齐的历史执行者（已改派换下、成员表又无记录）不在本方法覆盖内——其自身产出由
     * 可见性策略的"上传者恒可读自传"分支兜住。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM sub_task
            WHERE task_id = #{taskId} AND assigned_agent_id = #{agentId} AND deleted = 0
            """)
    int countCurrentExecutor(@Param("taskId") Long taskId, @Param("agentId") Long agentId);

    /**
     * 权威源快照：全部「任务 → 当前执行者」对（重建对账用）。
     *
     * <p>权威源取 {@code sub_task.assigned_agent_id}（task 域内，**不跨域直捅他域表**）。
     * 历史执行者由"上传者恒可读自传附件"规则兜住（见 {@code AttachmentVisibilityPolicy}），
     * 且改派入口自本方案上线后被 REASSIGNED 钩子持续记录，故无需回扫他域执行记录。</p>
     */
    @Select("""
            SELECT DISTINCT task_id, assigned_agent_id AS agent_id
            FROM sub_task
            WHERE deleted = 0 AND assigned_agent_id IS NOT NULL
            """)
    List<TaskAgentMember> selectAuthoritativeAssignments();
}
