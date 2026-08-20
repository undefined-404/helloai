package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.AgentExecutionRecord;

import java.util.List;

/**
 * Agent 执行记录服务（agent_execution_record 表）。
 * 覆盖执行命令的 PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT 状态推进与 DB Poller 扫描。
 */
public interface AgentExecutionRecordService extends IService<AgentExecutionRecord> {

    /**
     * 创建 PENDING 执行记录（冗余存储 trigger / agentId / accessType，便于 Poller 不查 Agent 表恢复）。
     */
    AgentExecutionRecord createPending(String eventId, Long subTaskId,
                                       Long agentId, AgentAccessType accessType, String trigger);

    /**
     * PENDING → RUNNING（CAS）。
     */
    boolean markRunning(Long id);

    /**
     * RUNNING → SUCCESS（CAS）。
     */
    boolean markSuccess(Long id);

    /**
     * RUNNING → FAILED（CAS，errorMsg 截断 500 字符）。
     */
    boolean markFailed(Long id, String errorMsg);

    /**
     * PENDING/RUNNING → TIMEOUT。
     */
    boolean markTimeout(Long id);

    /**
     * 子任务是否存在 PENDING/RUNNING 记录（防重复消费）。
     */
    boolean hasPendingOrRunning(Long subTaskId);

    /**
     * DB Poller 扫描：查找「长时间未被消费的 PENDING」记录（孤儿）。
     */
    List<AgentExecutionRecord> listOrphanPending(int thresholdSeconds, int limit);

    /**
     * 旧 DB Poller 主消费扫描：查找「所有未被消费的 PENDING」记录。
     *
     * @deprecated  Poller 不再调用本方法；保留仅为兼容历史代码与排查工具。
     *             新代码请使用 {@link #listOrphanPending(int, int)}。
     */
    @Deprecated
    List<AgentExecutionRecord> listAllPending(int limit);

    /**
     * DB Poller 触及痕迹：更新 last_attempt_at 为当前时间（不限制 status）。
     */
    boolean markPolled(Long id);
}
