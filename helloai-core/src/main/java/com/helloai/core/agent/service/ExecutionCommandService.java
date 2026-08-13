package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.ExecutionCommand;

/**
 * 执行命令创建服务：为已分配子任务生成执行命令并落库（ExecutionCommand / agent_execution_record /
 * agent_command_outbox 三表同事务），并发下通过子任务行锁保证不重复发命令。
 */
public interface ExecutionCommandService {

    /**
     * 为已分配子任务创建执行命令。
     *
     * <p>事务边界：{@code ExecutionCommand / agent_execution_record / agent_command_outbox}
     * 三表同事务写入；NONE 路径不写 outbox，EVENT 路径不写 outbox。</p>
     *
     * @return 创建的执行命令（含 MQ/本地投递路径信息）
     */
    ExecutionCommand createAssignedCommand(Long subTaskId, Long agentId, String trigger);
}
