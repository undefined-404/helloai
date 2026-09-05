package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

/**
 * Phase 2H ②a 引入：
 * {@code agent_command_outbox} 表的 MyBatis-Plus Mapper。
 *
 * <p>本 Mapper 仅承载 {@code agent_command_outbox} 行——执行命令 → MQ 的投递生命周期；
 * 与已有的 {@link AgentOutboxEventMapper}（SubTask 状态变更通知）严格分层，
 * 不共用 Service、不共用实体、不共用表。</p>
 *
 * <p>常规状态机流转使用 {@link com.baomidou.mybatisplus.extension.service.IService}
 * 内置的 {@code insert / updateById / lambdaUpdate}；
 * A4 S1 起补人工恢复窗口查询（FAILED 分页列表），走 {@code @Select} 显式 SQL。</p>
 */
@Mapper
public interface AgentCommandOutboxEventMapper extends BaseMapper<AgentCommandOutboxEvent> {

    /**
     * A4 S1：FAILED 行窗口分页（outbox 人工恢复的列表查询）。
     *
     * <p>仅返回 {@code status = FAILED(2)} 且未逻辑删除的行，供运维按创建时间窗口
     * 分页挑选重入；按 {@code create_time} 倒序（新失败在前，优先处理最新故障）。</p>
     *
     * <p>显式 SQL 不依赖 MyBatis-Plus lambda 缓存（无 MyBatis 容器环境不可用，
     * {@code AgentSessionMapper} 先例）；自定义 SQL 不会自动追加逻辑删除过滤，
     * 此处显式带 {@code deleted = 0}。</p>
     */
    @Select("SELECT * FROM agent_command_outbox " +
            "WHERE status = 2 AND deleted = 0 " +
            "AND create_time >= #{from} AND create_time <= #{to} " +
            "ORDER BY create_time DESC")
    IPage<AgentCommandOutboxEvent> listFailed(IPage<AgentCommandOutboxEvent> page,
                                              @Param("from") OffsetDateTime from,
                                              @Param("to") OffsetDateTime to);
}
