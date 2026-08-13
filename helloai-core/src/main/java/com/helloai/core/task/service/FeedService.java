package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.ActivityLog;

import java.util.List;
import java.util.Map;

/**
 * Feed 聚合服务（前端活动流 {@code /api/feed} 数据源）。
 *
 * <p>聚合 ActivityLog 与 Agent 两张表的活动日志分页列表与 Agent 摘要列表，
 * 为避免在 Controller 直接注入 Mapper，将查询与名称解析下移至本服务。</p>
 */
public interface FeedService {

    /**
     * 分页查询活动日志（按创建时间倒序）。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @param level    可选 level 过滤；null/空表示不限
     * @param source   可选 source 过滤；null/空表示不限
     * @return MyBatis-Plus Page 包装的活动日志（绝不返回 null）
     */
    Page<ActivityLog> listActivityLogs(int page, int pageSize, String level, String source);

    /**
     * 按日志 agentId 批量查询 Agent 名称，返回 id → name 映射；
     * 若 agentId 为 null 或对应 Agent 已删除则该 id 不出现在结果中。
     *
     * @param logs 活动日志列表
     * @return agentId → agentName 映射（绝不返回 null）
     */
    Map<Long, String> resolveAgentNames(List<ActivityLog> logs);

    /**
     * Agent 摘要列表（用于活动流右侧 Agent 选择下拉框）。
     *
     * <p>只取核心展示字段（id/name/role/status/score），逻辑删除过滤由
     * MyBatis-Plus @TableLogic 自动处理。</p>
     *
     * @return Agent 列表（绝不返回 null）
     */
    List<Agent> listAgentSummaries();
}
