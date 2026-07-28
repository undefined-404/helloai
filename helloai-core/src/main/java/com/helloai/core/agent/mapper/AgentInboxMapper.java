package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.AgentInbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentInboxMapper extends BaseMapper<AgentInbox> {

    /** 物理删除某 Agent 的全部收件箱消息（外键引用 agent.id，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM agent_inbox WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);

    /** 统计引用某任务及其子任务/审查记录的未读收件箱消息数（删除前风险提示用）。 */
    @Select("""
            SELECT COUNT(*) FROM agent_inbox
            WHERE is_read = 0 AND (
                  (ref_type = 'task' AND ref_id = #{taskId})
               OR (ref_type = 'sub_task' AND ref_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId}))
               OR (ref_type = 'review' AND ref_id IN (
                       SELECT id FROM review_record
                       WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId}))))
            """)
    int countUnreadByTaskRef(@Param("taskId") Long taskId);

    /**
     * 物理删除引用某任务及其子任务/审查记录的全部收件箱消息（仅供任务级联删除使用）。
     *
     * <p>子查询依赖 sub_task/review_record 行仍存在，必须在同一事务内
     * 先于子任务/审查记录执行。未读+已读一并清理，旧 Agent 上线后
     * pullTasks 拉不到已删任务的任何通知，从根上消除过期消息触发的白跑执行。</p>
     */
    @Delete("""
            DELETE FROM agent_inbox
            WHERE (ref_type = 'task' AND ref_id = #{taskId})
               OR (ref_type = 'sub_task' AND ref_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId}))
               OR (ref_type = 'review' AND ref_id IN (
                       SELECT id FROM review_record
                       WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})))
            """)
    int physicalDeleteByTaskRef(@Param("taskId") Long taskId);
}
