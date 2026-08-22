package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.task.entity.RewardLog;

import java.util.Map;

/**
 * 积分奖励服务接口：积分流水写入、查询与 Agent 积分摘要。
 */
public interface RewardService extends IService<RewardLog> {

    /**
     * 给 Agent 加减积分并落流水，同时回写 Agent 总分。
     *
     * @param agentId  Agent ID
     * @param reason   变动原因
     * @param delta    积分增量（可为负）
     * @param subTaskId 关联子任务 ID，可空
     */
    void addReward(Long agentId, String reason, int delta, Long subTaskId);

    /**
     * 分页查询全局积分流水，按创建时间倒序。
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    IPage<RewardLog> listAllLogs(int page, int pageSize);

    /**
     * 查询指定 Agent 的积分摘要（总分、奖励/惩罚次数、流水总数）。
     *
     * @param agentId Agent ID
     * @return 摘要 Map
     */
    Map<String, Object> getAgentScoreSummary(Long agentId);

    // ══════════════════════════════════════════════════════════════
    //  阶段五 agent→task.mapper 清零承接（agent 域只依赖本服务接口）
    // ══════════════════════════════════════════════════════════════

    /**
     * 分页查询指定 Agent 的积分流水，按创建时间倒序。
     *
     * <p>原实现位于 agent 域 AgentStatsService（直捅 RewardLogMapper），阶段五收口。</p>
     *
     * @param agentId  Agent ID
     * @param page     页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    IPage<RewardLog> listLogsByAgent(Long agentId, int page, int pageSize);

    /**
     * 指定 Agent 的积分流水总数（级联删除前统计、详情页关联计数）。
     *
     * @param agentId Agent ID
     * @return 流水数
     */
    long countByAgent(Long agentId);

    /**
     * 级联删除前物理删除指定 Agent 的积分流水（@TableLogic 普通 delete 会改写为
     * UPDATE deleted=1，本方法走 Mapper 自定义 DELETE SQL 真删，不留残留行）。
     *
     * @param agentId Agent ID
     * @return 删除行数
     */
    int physicalDeleteByAgent(Long agentId);
}
