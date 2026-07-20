package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.mapper.ActivityLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 活动日志基础服务。提供 activity_log 表的通用查询与写入能力。
 * 复杂统计查询由 {@link com.helloai.core.agent.service.AgentService} 走 Mapper 完成。
 */
@Slf4j
@Service
public class ActivityLogService extends ServiceImpl<ActivityLogMapper, ActivityLog> {

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
    public IPage<ActivityLog> list(Long agentId, Long subTaskId, int page, int pageSize) {
        LambdaQueryWrapper<ActivityLog> wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(agentId != null, ActivityLog::getAgentId, agentId)
                .eq(subTaskId != null, ActivityLog::getSubTaskId, subTaskId)
                .orderByDesc(ActivityLog::getCreateTime);

        if (page <= 0) {
            // 不分页：把全量列表包装成 IPage 返回，便于 Controller 统一处理
            List<ActivityLog> all = list(wrapper);
            Page<ActivityLog> full = new Page<>(1, all == null ? 0 : all.size());
            if (all != null) {
                full.setRecords(all);
            }
            return full;
        }
        return page(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 按过滤条件查询活动日志（不分页），用于 FeedController 等场景。
     *
     * @param level  可选 level 过滤；null/空表示不限
     * @param source 可选 source 过滤；null/空表示不限
     * @return 全量列表（按创建时间倒序）；绝不返回 null
     */
    public List<ActivityLog> listAll(String level, String source) {
        LambdaQueryWrapper<ActivityLog> wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(level != null && !level.isBlank(), ActivityLog::getLevel, level)
                .eq(source != null && !source.isBlank(), ActivityLog::getSource, source)
                .orderByDesc(ActivityLog::getCreateTime);
        List<ActivityLog> result = list(wrapper);
        return result != null ? result : Collections.emptyList();
    }

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
    @Transactional(rollbackFor = Exception.class)
    public ActivityLog record(Long agentId, String action, String level, String source,
                              Long subTaskId, Map<String, Object> detail) {
        ActivityLog entity = new ActivityLog();
        entity.setAgentId(agentId);
        entity.setAction(action);
        entity.setLevel(level != null ? level : "INFO");
        entity.setSource(source != null ? source : "agent");
        entity.setSubTaskId(subTaskId);
        entity.setDetail(detail);
        save(entity);
        log.info("活动日志写入: agentId={}, action={}, subTaskId={}", agentId, action, subTaskId);
        return entity;
    }
}