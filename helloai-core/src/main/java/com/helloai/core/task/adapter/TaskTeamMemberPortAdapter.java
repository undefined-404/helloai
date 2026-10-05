package com.helloai.core.task.adapter;

import com.helloai.core.agent.port.TaskTeamMemberPort;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link TaskTeamMemberPort} 的提供方（task 域）实现 —— 薄委托，不重写任何语义。
 *
 * <p>本类位于提供方 {@code task} 域，依赖消费方 {@code agent} 定义的端口，
 * 方向为 {@code task → agent}，属 CODE_STYLE §6 <b>顺向合法</b>。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskTeamMemberPortAdapter implements TaskTeamMemberPort {

    private final TaskAgentMemberMapper taskAgentMemberMapper;

    @Override
    public int physicalDeleteByAgentId(Long agentId) {
        if (agentId == null) {
            return 0;
        }
        // 薄委托：物理删除（避开 @TableLogic 软删），与调用方同事务
        return taskAgentMemberMapper.physicalDeleteByAgentId(agentId);
    }
}
