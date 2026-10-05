package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AgentExecutionRecordMapper extends BaseMapper<AgentExecutionRecord> {

    @Select("SELECT * FROM agent_execution_record WHERE status = #{status} AND create_time < #{before} AND deleted = 0")
    List<AgentExecutionRecord> selectByStatusAndCreateTimeBefore(@Param("status") ExecutionStatus status,
                                                                  @Param("before") OffsetDateTime before);

    @Select("SELECT * FROM agent_execution_record WHERE status = #{status} AND start_time < #{before} AND deleted = 0")
    List<AgentExecutionRecord> selectByStatusAndStartTimeBefore(@Param("status") ExecutionStatus status,
                                                                 @Param("before") OffsetDateTime before);

    @Update("UPDATE agent_execution_record SET status = #{status}, error_msg = #{errorMsg}, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") ExecutionStatus status, @Param("errorMsg") String errorMsg);

    /** 统计某任务下全部子任务的执行记录数（删除前风险提示用）。 */
    @Select("SELECT COUNT(*) FROM agent_execution_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int countByTaskId(@Param("taskId") Long taskId);

    /**
     * B5.2（Fleet 成本选人）：某 Agent「最近 N 次成功执行」的 token 均值。
     *
     * <p>口径：{@code status='SUCCESS' AND token_usage IS NOT NULL AND deleted=0}，
     * 按 {@code id DESC} 取最近 {@code limit} 条后求 AVG。无样本返回 {@code NULL}。</p>
     *
     * <p>返回类型为 {@code Double} 而非 {@code Long}：PostgreSQL 的 {@code avg(integer)}
     * 返回 {@code numeric}，映射 {@code Double} 由 {@code DoubleTypeHandler} 处理
     * （SQL NULL → Java null，不会退化成 0.0）。</p>
     */
    @Select("SELECT AVG(t.token_usage) FROM ("
            + "SELECT token_usage FROM agent_execution_record "
            + "WHERE agent_id = #{agentId} AND status = 'SUCCESS' AND token_usage IS NOT NULL AND deleted = 0 "
            + "ORDER BY id DESC LIMIT #{limit}) t")
    Double selectRecentAvgTokenUsage(@Param("agentId") Long agentId, @Param("limit") int limit);

    /** 物理删除某任务下全部执行记录（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM agent_execution_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
