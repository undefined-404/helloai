package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.entity.AgentExecutionRecord;
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
}
