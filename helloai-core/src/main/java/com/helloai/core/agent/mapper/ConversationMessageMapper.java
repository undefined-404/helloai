package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.ConversationMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /** 物理删除某任务下全部会话消息（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM conversation_message WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
