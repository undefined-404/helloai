package com.helloai.core.agent.quality.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.quality.dto.AgentQualityRank;
import com.helloai.core.agent.quality.dto.QualityOverview;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;

import java.util.List;

/**
 * Agent 质量画像服务（反馈回路第 1 层）。
 *
 * <p>提供画像查询（调度回灌/历史表现摘要数据源）、质量分计算、历史表现节渲染
 * 与重算兜底（admin 端点与 verify-quality-profile.ps1 对账）。</p>
 */
public interface AgentQualityProfileService extends IService<AgentQualityProfile> {

    /**
     * 查询指定 Agent 的活跃画像。
     *
     * @param agentId Agent ID；null 返回 null
     * @return 画像；不存在时返回 null
     */
    AgentQualityProfile getProfile(Long agentId);

    /**
     * 计算质量分（0~100，null 安全）。
     *
     * <p>口径：首轮通过率（权重 0.5）+ 平均分归一（权重 0.5），
     * 首轮数据缺失时首轮通过率按中性 50 计。供 AgentSelector qualityRank
     * 与动态 TTL 复合分使用。</p>
     *
     * @param agentId Agent ID
     * @return 0~100 质量分；无画像或无评审数据时返回 null（调用方回退原逻辑）
     */
    Integer computeQualityScore(Long agentId);

    /**
     * 渲染「## 你的历史表现」节（Phase 3 注入执行 Prompt 用）。
     *
     * <p>包含累计评审数、一次通过率、最常见驳回原因 TOP3 与本轮提醒语；
     * 画像缺失返回空串（调用方据此省略注入，best-effort 哲学）。</p>
     *
     * @param agentId Agent ID
     * @return 渲染文本；无画像或无数据时返回空串
     */
    String renderHistorySection(Long agentId);

    /**
     * Reviewer 维度计数增量（反馈回路 Phase 4 双审/抽检）。
     *
     * <p>reviewer_reviewed_count / reviewer_disagreement_count 原子累加；
     * 画像行不存在时创建（仅 reviewer 维度字段非零），并发冲突时回退 UPDATE。
     * best-effort：内部异常不向调用方抛出（双审/抽检主链路绝不因计数失败阻断）。</p>
     *
     * @param reviewerAgentId   Reviewer Agent ID；null 直接忽略
     * @param reviewedDelta     核验次数增量（≥0）
     * @param disagreementDelta 分歧次数增量（≥0）
     */
    void incrementReviewerStats(Long reviewerAgentId, int reviewedDelta, int disagreementDelta);

    /**
     * 重算兜底：从 review_record 全量重算指定 Agent 的画像并覆盖落库。
     *
     * <p>口径与增量维护一致（首轮通过/评分累加/返工轮次/缺陷标签），
     * 供 admin 端点与 ps1 对账使用；执行者归属取 sub_task.assigned_agent_id
     * 当前值（改派后的历史记录会按新归属统计，与增量维护存在已知口径漂移）。</p>
     *
     * @param agentId Agent ID；null 或名下无任何评审记录时删除画像行
     */
    void rebuild(Long agentId);

    /**
     * 全局质量概览（Phase 5 质量度量看板 overview 卡片）。
     *
     * <p>数据源为画像表存量（执行者维度累计值，非时间窗口）；空表返回
     * 全 0 概览（Mapper COALESCE 兜底单行必返回）。</p>
     *
     * @return 全局概览
     */
    QualityOverview statsOverview();

    /**
     * Agent 质量排行（Phase 5 质量度量看板 agents 排行）。
     *
     * <p>排序：一次通过率降序 → 审查数降序 → agentId 升序；qualityScore
     * 复用 {@link #computeQualityScore} 逐行计算（口径唯一）；agentName 经
     * AgentService 批量补名（缺失显示 ID 字符串）。</p>
     *
     * @param limit 返回条数上限；null 或 &lt;=0 返回全部
     * @return 排行列表；无数据返回空列表
     */
    List<AgentQualityRank> statsAgentRankings(Integer limit);
}
