package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.ExecutionCommand;

import java.util.List;

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
     * <p>Phase 1 Step 1 fix（LOG-20260904-009）：新增 {@code requiredSkills} 入参——
     * task 域数据由调用方<b>装箱传入</b>（§6 依赖方向红线：本服务在 agent 域，
     * 不反向查询 task），null 由调用方契约保证或由命令 builder 默认值兜底。</p>
     *
     * @param subTaskId 目标子任务 ID
     * @param agentId 目标 Agent ID
     * @param trigger 命令触发来源
     * @param requiredSkills 任务声明的技能标签清单（可为 null，由 ExecutionCommand builder 默认值规范化为空列表）
     * @return 创建的执行命令（含 MQ/本地投递路径信息）
     */
    ExecutionCommand createAssignedCommand(Long subTaskId, Long agentId, String trigger,
                                           List<String> requiredSkills);
}
