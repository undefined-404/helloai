package com.helloai.core.agent.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.session.entity.AgentSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 执行会话 Mapper（{@code agent_session} 表，Phase 1 Step 3）。
 *
 * <p>读取一律取最新（{@code create_time + id} 倒序）：turn 在计数器清零后
 * 可能复用，不能用 (sub_task_id, turn) 唯一推断当前会话。</p>
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {

    /**
     * 读取指定子任务的最新 ACTIVE 会话（中断点载体；无则返回 null）。
     *
     * <p>显式 SQL 而非 LambdaQueryWrapper：纯单元测试（无 MyBatis 容器）下
     * lambda 缓存不可用（同 AgentEventMapper.selectLastEventTypeBySubTaskId 先例）。</p>
     */
    @Select("SELECT * FROM agent_session "
            + "WHERE sub_task_id = #{subTaskId} AND status = 'ACTIVE' AND deleted = 0 "
            + "ORDER BY create_time DESC, id DESC LIMIT 1")
    AgentSession selectLatestActiveBySubTaskId(@Param("subTaskId") Long subTaskId);

    /**
     * 把指定子任务的全部 ACTIVE 会话置为 ABORTED（回收/重派中断，幂等防重入）。
     *
     * @return 实际更新行数
     */
    @Update("UPDATE agent_session SET status = 'ABORTED', update_time = CURRENT_TIMESTAMP "
            + "WHERE sub_task_id = #{subTaskId} AND status = 'ACTIVE' AND deleted = 0")
    int abortActiveBySubTaskId(@Param("subTaskId") Long subTaskId);

    /**
     * 推进 ACTIVE 会话的中断点 step（LLM 调用完成后）。
     *
     * <p>显式 SQL 而非 LambdaUpdateWrapper：纯单元测试（无 MyBatis 容器）下 lambda
     * 缓存不可用，会抛 {@code can not find lambda cache}（同 B3
     * {@code selectLastEventTypeBySubTaskId} 的 @Select 先例）。</p>
     *
     * @return 实际更新行数
     */
    @Update("UPDATE agent_session SET step = #{step}, update_time = CURRENT_TIMESTAMP "
            + "WHERE sub_task_id = #{subTaskId} AND turn = #{turn} AND status = 'ACTIVE' AND deleted = 0")
    int advanceStep(@Param("subTaskId") Long subTaskId, @Param("turn") int turn, @Param("step") int step);

    /**
     * ACTIVE 会话终态 → COMPLETED（执行成功）。
     *
     * @return 实际更新行数
     */
    @Update("UPDATE agent_session SET status = 'COMPLETED', update_time = CURRENT_TIMESTAMP "
            + "WHERE sub_task_id = #{subTaskId} AND turn = #{turn} AND status = 'ACTIVE' AND deleted = 0")
    int completeBySubTaskAndTurn(@Param("subTaskId") Long subTaskId, @Param("turn") int turn);

    /**
     * ACTIVE 会话终态 → FAILED + error（执行失败，error 截断 500）。
     *
     * @return 实际更新行数
     */
    @Update("UPDATE agent_session SET status = 'FAILED', error = #{error}, update_time = CURRENT_TIMESTAMP "
            + "WHERE sub_task_id = #{subTaskId} AND turn = #{turn} AND status = 'ACTIVE' AND deleted = 0")
    int failBySubTaskAndTurn(@Param("subTaskId") Long subTaskId, @Param("turn") int turn,
                             @Param("error") String error);
}
