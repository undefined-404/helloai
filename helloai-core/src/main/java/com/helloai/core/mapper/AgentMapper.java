package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD，
 * 自定义方法集中在 {@code AgentMapper.xml}：
 * <ul>
 *   <li>{@link #insert} / {@link #updateById}：覆盖 BaseMapper，处理 PostgreSQL JSONB 字段</li>
 *   <li>{@link #markOfflineIfStale}：阶段 4 Reconcile 的 CAS UPDATE，防止 seen() 刷新覆盖</li>
 *   <li>{@link #selectByLastSeenBefore}：阶段 4 Reconcile 扫描超时未续约 Agent</li>
 * </ul>
 * </p>
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {

    /**
     * 把超时的 Agent 标 OFFLINE（CAS UPDATE，防止 seen() 刷新覆盖）。
     *
     * <p>CAS 条件：
     * <ul>
     *   <li>{@code id = #{agentId}}</li>
     *   <li>{@code last_seen_at < #{cutoff}} — 仍超时（防止 seen() 刚刷新又被标离线）</li>
     *   <li>{@code online_status IS DISTINCT FROM 'SLEEPING'} — SLEEPING 不自动改</li>
     *   <li>{@code deleted = 0}</li>
     * </ul>
     * </p>
     *
     * @return 影响行数；0 表示 CAS 失败（说明 seen() 已刷新或 Agent 已 SLEEPING）
     */
    int markOfflineIfStale(@Param("agentId") Long agentId,
                           @Param("cutoff") OffsetDateTime cutoff,
                           @Param("newStatus") String newStatus,
                           @Param("reason") String reason,
                           @Param("now") OffsetDateTime now);

    /**
     * 查 last_seen_at 早于 cutoff 的 Agent 列表（用于 Reconcile 扫描）。
     *
     * <p>过滤条件：
     * <ul>
     *   <li>{@code last_seen_at < #{cutoff}} — 超时未续约</li>
     *   <li>{@code online_status IS DISTINCT FROM 'SLEEPING'} — SLEEPING 不参与扫描</li>
     *   <li>{@code deleted = 0}</li>
     * </ul>
     * </p>
     *
     * <p>注意：包含当前 online_status 为 ONLINE/IDLE/OFFLINE 的 Agent，
     * 调用方需根据业务判断是否要标 OFFLINE（依赖 CAS 防止覆盖）。</p>
     */
    List<Agent> selectByLastSeenBefore(@Param("cutoff") OffsetDateTime cutoff);
}
