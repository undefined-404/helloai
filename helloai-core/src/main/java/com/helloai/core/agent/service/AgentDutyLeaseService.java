package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.entity.AgentDutyLeaseLatestRow;

import java.util.Collection;
import java.util.Map;

/**
 * Agent 值班租约服务。
 *
 * <p>AgentHub V1 T3 最小骨架，提供值班态事实源的 CRUD。</p>
 *
 * <p>本轮只做：
 * <ul>
 *   <li>查询当前有效 lease</li>
 *   <li>关闭旧 lease（签退 / 强制关闭）</li>
 *   <li>新建 lease（打卡上班）</li>
 * </ul>
 * 本轮不做：checkIn/checkOut、selector 接入、dashboard。</p>
 */
public interface AgentDutyLeaseService extends IService<AgentDutyLease> {

    /**
     * 按 ID 批量查询 Agent 名称（用于值班租约报表面板填充 agentName）。
     *
     * <p>为避免 N+1，走 {@code AgentMapper.selectBatchIds} 一次性查询；
     * 入参中的 null 元素会被跳过；返回 Map 键为 Agent ID，值为 Agent 名称
     * （无记录的 ID 不在 Map 中）。</p>
     *
     * @param agentIds 待查询的 Agent ID 集合（可为 null 或空集合）
     * @return id → name 映射；输入为空时返回空 Map
     */
    Map<Long, String> getAgentNamesByIds(Collection<Long> agentIds);

    /**
     * 查询 Agent 当前有效的值班租约。
     *
     * @return null 如果当前没有 ACTIVE 租约
     */
    AgentDutyLease getActiveLease(Long agentId);

    /**
     * 判断 Agent 当前是否处于值班态（有 ACTIVE 租约）。
     */
    boolean isOnDuty(Long agentId);

    /**
     * 查询 Agent 最近一条值班租约（任意状态，按开始时间倒序取第一条）。
     *
     * <p>A0-6（§6.65）：checkOut 幂等返回当前状态时使用——租约已过期或从未打卡时
     * 也能给出可自检的语义（EXPIRED / NONE），而不是只给 closedCount=0。</p>
     *
     * @return null 如果该 Agent 从未有过租约
     */
    AgentDutyLease getLatestLease(Long agentId);

    /**
     * 为 Agent 开启新的值班租约（打卡上班）。
     *
     * <p>事务内先关闭该 Agent 的所有旧 ACTIVE 租约（防御性），再新建一条。</p>
     *
     * @param agentId       Agent ID
     * @param workMode      工作模式
     * @param maxConcurrent 最大并发数
     * @param ttlMinutes    租约有效期（分钟）
     * @return 新建的租约
     */
    AgentDutyLease startLease(Long agentId, String workMode,
                              Integer maxConcurrent, int ttlMinutes);

    /**
     * 关闭 Agent 当前有效的值班租约（签退）。
     *
     * @param agentId     Agent ID
     * @param closeReason 关闭原因
     * @return 关闭的租约条数（0 = 没有需要关闭的 ACTIVE 租约）
     */
    int closeLease(Long agentId, String closeReason);

    /**
     * 续约：延长当前 ACTIVE 租约的 expires_at 和 last_renewed_at。
     *
     * <p>按 agentId 精确指定，仅续约最新一条 ACTIVE 租约。
     * 如果 Agent 当前无 ACTIVE 租约，返回 null。</p>
     *
     * @param agentId    Agent ID
     * @param ttlMinutes 续约时长（分钟）
     * @return 续约后的租约；如果无 ACTIVE 租约则返回 null
     */
    AgentDutyLease renewLease(Long agentId, int ttlMinutes);

    /**
     * 解析租约 TTL 窗口（E1 动态 TTL 自适应，N12 A2 第 2 段）。
     *
     * <p>显式入参（checkIn 传了 ttlMinutes）永远优先；否则按 Agent 表现动态推断：
     * 有 score → 线性映射 [0, fullScore] → [min, max]；无 score →
     * 用 consecutive_failure_count 折算表现分（每次失败 -20，下限 0）；
     * 自适应开关关闭 / agentId 为空 / Agent 记录不存在 → defaultTtlMinutes 兜底。</p>
     *
     * @param agentId             Agent ID
     * @param explicitTtlMinutes  checkIn 显式传入的 TTL（分钟）；null 或 &lt;=0 表示走动态推断
     * @return 解析后的租约窗口（分钟），恒 &gt; 0
     */
    int resolveTtlMinutes(Long agentId, Integer explicitTtlMinutes);

    /**
     * 自适应续约（E1 动态 TTL 自适应）：按 Agent 当前状态动态计算续约窗口。
     *
     * <p>有在跑子任务（ASSIGNED / IN_PROGRESS / REWORK）→ 用最大窗口，任务执行期稳定保活；
     * 空闲 → 按表现分动态窗口（低分短、高分长）。无 ACTIVE 租约时返回 null，
     * 不自动打卡（保持 checkIn 的打卡语义）。供工具调用自动续租路径
     * （{@code McpToolService.refreshDutyLease}）使用。</p>
     *
     * @param agentId Agent ID
     * @return 续约后的租约；当前无 ACTIVE 租约返回 null
     */
    AgentDutyLease adaptiveRenew(Long agentId);

    /**
     * 扫描到期的 ACTIVE 租约并批量翻为 EXPIRED（AgentHub V1 P0-C）。
     *
     * <p>由 helloai-job 中的 {@code DutyLeaseExpirationTask} 周期性调用。
     * 每个 Agent 的翻转单独一条 UPDATE，沿用 {@link #closeLease} 相同的
     * 原子条件更新 SQL，仅将 status 从 'ACTIVE' 改为 'EXPIRED'，close_reason 为 'lease_expired'。</p>
     *
     * @param batchLimit 单轮扫描上限，建议 100～500
     * @return 成功翻为 EXPIRED 的行数
     */
    int expireLeases(int batchLimit);

    /**
     * 分页查询值班租约（AgentHub V1 P1 值班报表数据源，只读）。
     *
     * <p>为运营看板提供值班租约列表，支持按 Agent、状态过滤，
     * 按值班开始时间倒序（最近上班的在前）。逻辑删除由 {@code @TableLogic} 自动过滤。</p>
     *
     * @param agentId  可选，按 Agent 过滤；null 表示不限
     * @param status   可选，按租约状态过滤；null 表示不限
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果，绝不返回 null
     */
    IPage<AgentDutyLease> listLeases(Long agentId, AgentDutyLeaseStatus status,
                                     long pageNum, long pageSize);

    /**
     * Agent 维度分页：每个 Agent 只返回最新一条租约 + 该 Agent 租约总数（只读）。
     *
     * <p>total 为有租约记录的 Agent 数；排序按最新租约开始时间倒序
     * （最近上班的 Agent 在前）。分组取最新走 Mapper 自定义 DISTINCT ON SQL，
     * 非 MyBatis-Plus 分页插件链路，故手工拼 Page。</p>
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页 Agent 数
     * @return 分页结果，绝不返回 null
     */
    IPage<AgentDutyLeaseLatestRow> listLatestPerAgent(long pageNum, long pageSize);

    /**
     * 今日打卡概览：按 Agent 维度统计各状态的 Agent 数（只读）。
     *
     * <p>每个 Agent 只按其最新一条租约的状态计一次（要么在线、要么下班、
     * 要么超时），而非历史租约条数累计。口径：今日有打卡记录或当前仍
     * ACTIVE 在线（含昨日打卡至今未下班）的 Agent。缺失状态补 0，保证
     * 返回的键始终齐全（ACTIVE / CLOSED / EXPIRED），供看板卡片直接消费。</p>
     *
     * @return 状态 → Agent 数（顺序稳定），绝不返回 null
     */
    Map<AgentDutyLeaseStatus, Long> countTodayAgentsByStatus();
}
