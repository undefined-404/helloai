package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.BrowserSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Browser 会话 Mapper（N-003，C3-S1）。
 *
 * <p>2026-10-05 D-1：{@code browser_session} 带 task_id/agent_id 语义列但无外键，
 * 任务/Agent 级联删除时被静默漏删；补两个物理删除入口专供级联删除使用。</p>
 */
@Mapper
public interface BrowserSessionMapper extends BaseMapper<BrowserSession> {

    /** 物理删除某任务下全部浏览器会话（cascade：task_id 语义列无外键）。 */
    @Delete("DELETE FROM browser_session WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /** 物理删除某 Agent 的全部浏览器会话（cascade：agent_id 语义列无外键）。 */
    @Delete("DELETE FROM browser_session WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
