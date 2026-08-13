package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.task.entity.ActivityLog;

import java.util.List;
import java.util.Map;

/**
 * 活动日志基础服务。提供 activity_log 表的通用查询与写入能力。
 * 复杂统计查询由 AgentService 走 Mapper 完成。
 */
public interface ActivityLogService extends IService<ActivityLog> {

    /**
     * 分页或全量查询活动日志（按创建时间倒序）。
     *
     * <p>当 {@code page <= 0} 时返回全量列表，否则按分页参数返回；任意过滤参数为
     * null 时跳过对应条件。逻辑删除由 {@code @TableLogic} 自动过滤。</p>
     *
     * @param agentId   可选 Agent ID 过滤；null 表示不限
     * @param subTaskId 可选子任务 ID 过滤；null 表示不限
     * @param page      页码（从 1 开始）；&lt;= 0 表示不分页
     * @param pageSize  每页条数（仅当 page &gt; 0 时生效）
     * @return 分页结果或全量列表；绝不返回 null
     */
    IPage<ActivityLog> list(Long agentId, Long subTaskId, int page, int pageSize);

    /**
     * 按过滤条件查询活动日志（不分页），用于 FeedController 等场景。
     *
     * @param level  可选 level 过滤；null/空表示不限
     * @param source 可选 source 过滤；null/空表示不限
     * @return 全量列表（按创建时间倒序）；绝不返回 null
     */
    List<ActivityLog> listAll(String level, String source);

    /**
     * Agent 写入活动日志。幂等由调用方控制，本方法只负责单条插入。
     *
     * <p>{@code level} 缺省 {@code INFO}；{@code source} 缺省 {@code agent}。
     * 子任务 ID 与 detail 字段允许为空。</p>
     *
     * @param agentId   调用方 Agent ID（来自 AuthInterceptor）
     * @param action    动作标识
     * @param level     日志级别，可空
     * @param source    日志来源，可空
     * @param subTaskId 关联子任务 ID，可空
     * @param detail    详情 JSONB，可空
     * @return 已持久化的 ActivityLog 实体
     */
    ActivityLog record(Long agentId, String action, String level, String source,
                       Long subTaskId, Map<String, Object> detail);
}
