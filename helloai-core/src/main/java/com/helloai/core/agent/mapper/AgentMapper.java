package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.Agent;
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
     * 行锁读取 Agent（E2 并发额度原子防线）。
     *
     * <p>{@code SELECT ... FOR UPDATE} 锁定 agent 行，使同一 Agent 的并发派发
     * （{@code SubTaskService.assignNext}）在 PostgreSQL 行锁上串行化，
     * 锁内重新统计在飞数判定额度，杜绝"选人通过但落库前被并发占满"的超发窗口。</p>
     *
     * @param agentId Agent ID
     * @return Agent；不存在或已删除时返回 null
     */
    Agent selectByIdForUpdate(@Param("agentId") Long agentId);

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

    // ══════════════════════════════════════════════════════════════
    //  N11 阈值回退：CLI_CLIENT Agent 连续失败计数 + 候选扫描
    //  详见 AgentMapper.xml 对应 SQL 注释
    // ══════════════════════════════════════════════════════════════

    /**
     * 原子累加 CLI_CLIENT Agent 连续失败次数。
     *
     * @return 1 = 成功累加；0 = Agent 不存在 / 非 CLI_CLIENT / 已删除
     */
    int incrementConsecutiveFailure(@Param("agentId") Long agentId,
                                    @Param("now") OffsetDateTime now);

    /**
     * 重置连续失败计数（成功路径上调用）。
     *
     * @return 1 = 成功重置；0 = Agent 不存在 / 非 CLI_CLIENT / 已删除
     */
    int resetConsecutiveFailure(@Param("agentId") Long agentId,
                                @Param("now") OffsetDateTime now);

    /**
     * 标记回退已触发：清零计数 + 写入 last_fallback_at。
     *
     * @return 1 = 成功标记；0 = Agent 不存在 / 非 CLI_CLIENT / 已删除
     */
    int markFallbackTriggered(@Param("agentId") Long agentId,
                              @Param("now") OffsetDateTime now);

    /**
     * 扫描超阈值候选 Agent。
     *
     * <p>条件：CLI_CLIENT + 未删除 + 连续失败次数 &gt;= threshold +
     * 处于 cooldown 之外（last_fallback_at 为空或早于 cooldownCutoff）+
     * 心跳新鲜（v2.6 §4.1：last_seen_time 非空且晚于 lastSeenCutoff，
     * 与 AgentSelector / AgentHealthCheckTask 共用 AgentHealthProperties.offlineMinutes）。</p>
     */
    List<Agent> selectFallbackCandidates(@Param("threshold") int threshold,
                                         @Param("cooldownCutoff") OffsetDateTime cooldownCutoff,
                                         @Param("lastSeenCutoff") OffsetDateTime lastSeenCutoff);

    /**
     * 物理删除 Agent（真实 DELETE，绕过 {@code @TableLogic} 逻辑删除改写）。
     *
     * <p>仅供 {@code AgentService.deleteAgentCascade} 级联删除使用；
     * 其余路径一律走 MyBatis-Plus 逻辑删除。</p>
     *
     * @return 影响行数；0 表示 Agent 不存在
     */
    int physicalDeleteById(@Param("agentId") Long agentId);
}
