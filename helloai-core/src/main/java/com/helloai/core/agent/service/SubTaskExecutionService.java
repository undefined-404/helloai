package com.helloai.core.agent.service;

import com.helloai.common.constant.SubTaskStatus;

/**
 * 子任务执行服务（G-002 单轨后仅保留状态推进入口）。
 *
 * <p>旧链 executeCommand / executeOnce 已随 Legacy 执行链退役（2026-09-30）：执行编排
 * 收敛到 {@code LocalExecutionCommandConsumer}（startIfNeeded → 上下文装配 → Runtime 真身
 * → 结果回写），本接口仅提供消费侧调用的幂等状态前置。</p>
 */
public interface SubTaskExecutionService {

    /**
     * 子任务状态进入指定状态时按需启动执行（幂等，仅未执行过且状态匹配时触发）。
     */
    void startIfNeeded(Long subTaskId, SubTaskStatus status);
}