package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;

import java.util.List;

/**
 * 子任务执行服务：执行命令驱动的子任务执行链路（子任务态机推进 + 平台内/外部执行器路由 + 结果上报）。
 */
public interface SubTaskExecutionService {

    /**
     * 执行一条执行命令（本地消费路径，MQ 消费后调用）。
     *
     * <p>内部完成：子任务状态校验/推进（IN_PROGRESS 等）、执行器路由（平台内 API_KEY_LLM /
     * 外部 Agent）、LLM 调用、结果写入子任务 context。</p>
     *
     * @return 执行结果（成功/失败 + 产出/错误信息）
     */
    AgentResult executeCommand(ExecutionCommand command);

    /**
     * 对已处于可执行状态的子任务执行一次（重试/补发路径）。
     *
     * <p>Phase 1 Step 1 fix（LOG-20260904-009）：requiredSkills 由调用方装箱传入——
     * 执行侧不再反向查询 task（§6 依赖方向红线），由 {@code ExecutionCommand.requiredSkills}
     * 经执行命令链路透传到本参数。</p>
     */
    AgentResult executeOnce(SubTask subTask, Agent agent, List<String> requiredSkills);

    /**
     * 子任务状态进入指定状态时按需启动执行（幂等，仅未执行过且状态匹配时触发）。
     */
    void startIfNeeded(Long subTaskId, SubTaskStatus status);
}
