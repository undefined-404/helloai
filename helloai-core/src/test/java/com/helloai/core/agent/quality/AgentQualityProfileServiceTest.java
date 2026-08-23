package com.helloai.core.agent.quality;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.dto.AgentQualityRank;
import com.helloai.core.agent.quality.dto.QualityOverview;
import com.helloai.core.agent.quality.dto.RebuildSourceRow;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper;
import com.helloai.core.agent.quality.service.impl.AgentQualityProfileServiceImpl;
import com.helloai.core.agent.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 质量画像服务单元测试（反馈回路第 1 层，Phase 1.5）。
 *
 * <p>覆盖：质量分口径（首轮通过率 ×0.5 + 平均分归一 ×0.5、null 安全、clamp）、
 * 历史表现节渲染（TOP3 / 空画像省略）与 rebuild 重算一致性（口径与增量一致）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentQualityProfileService 画像查询与重算")
class AgentQualityProfileServiceTest {

    private static final long AGENT_ID = 1L;

    @Mock
    private AgentQualityProfileMapper profileMapper;

    @Mock
    private AgentService agentService;

    private AgentQualityProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new AgentQualityProfileServiceImpl(agentService));
        // ServiceImpl 的 baseMapper 为父类 protected 字段，单测环境通过反射注入 mock
        ReflectionTestUtils.setField(service, "baseMapper", profileMapper);
    }

    private AgentQualityProfile profile(int reviewed, int approved, int firstReviewed,
                                        int firstPass, int totalScore) {
        AgentQualityProfile p = new AgentQualityProfile();
        p.setAgentId(AGENT_ID);
        p.setReviewedCount(reviewed);
        p.setApprovedCount(approved);
        p.setFirstReviewedCount(firstReviewed);
        p.setFirstPassCount(firstPass);
        p.setTotalScore(totalScore);
        return p;
    }

    // ════════════════════════════════════════════════════════════
    //  computeQualityScore
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("agentId 为 null → 返回 null（调用方回退原逻辑）")
    void computeScoreNullAgentId() {
        assertThat(service.computeQualityScore(null)).isNull();
    }

    @Test
    @DisplayName("无画像 → 返回 null")
    void computeScoreNoProfile() {
        doReturn(null).when(service).getProfile(AGENT_ID);
        assertThat(service.computeQualityScore(AGENT_ID)).isNull();
    }

    @Test
    @DisplayName("reviewed=0 → 返回 null（无评审数据不产出质量分）")
    void computeScoreZeroReviewed() {
        doReturn(profile(0, 0, 0, 0, 0)).when(service).getProfile(AGENT_ID);
        assertThat(service.computeQualityScore(AGENT_ID)).isNull();
    }

    @Test
    @DisplayName("首轮通过率 60% + 平均分 4 分 → 0.5×60 + 0.5×75 = 68（round）")
    void computeScoreBlended() {
        doReturn(profile(10, 8, 10, 6, 40)).when(service).getProfile(AGENT_ID);
        assertThat(service.computeQualityScore(AGENT_ID)).isEqualTo(68);
    }

    @Test
    @DisplayName("无首轮数据 → 首轮通过率按中性 50 计")
    void computeScoreNoFirstRoundData() {
        doReturn(profile(10, 8, 0, 0, 50)).when(service).getProfile(AGENT_ID);
        // firstPassRate=50（中性），avgNorm = 50*25/10-25 = 100 → 0.5×50+0.5×100 = 75
        assertThat(service.computeQualityScore(AGENT_ID)).isEqualTo(75);
    }

    @Test
    @DisplayName("零通过 + 平均分 1 → clamp 到 0")
    void computeScoreClampedToZero() {
        doReturn(profile(10, 0, 10, 0, 10)).when(service).getProfile(AGENT_ID);
        // avg = 1 → avgNorm=0；firstPassRate=0 → 0
        assertThat(service.computeQualityScore(AGENT_ID)).isZero();
    }

    @Test
    @DisplayName("全满分 → 100（不越界）")
    void computeScoreMaxIsHundred() {
        doReturn(profile(10, 10, 10, 10, 50)).when(service).getProfile(AGENT_ID);
        assertThat(service.computeQualityScore(AGENT_ID)).isEqualTo(100);
    }

    // ════════════════════════════════════════════════════════════
    //  renderHistorySection
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无画像 → 空串（调用方省略注入）")
    void renderHistoryNoProfile() {
        doReturn(null).when(service).getProfile(AGENT_ID);
        assertThat(service.renderHistorySection(AGENT_ID)).isEmpty();
    }

    @Test
    @DisplayName("reviewed=0 → 空串")
    void renderHistoryZeroReviewed() {
        doReturn(profile(0, 0, 0, 0, 0)).when(service).getProfile(AGENT_ID);
        assertThat(service.renderHistorySection(AGENT_ID)).isEmpty();
    }

    @Test
    @DisplayName("正常渲染：含标题/通过率/TOP3 驳回原因/本轮提醒语")
    void renderHistoryFull() {
        AgentQualityProfile p = profile(10, 8, 10, 6, 40);
        p.setIssueDefectStats(Map.of("缺少单测", 5, "文档不全", 3, "命名不规范", 2));
        doReturn(p).when(service).getProfile(AGENT_ID);

        String section = service.renderHistorySection(AGENT_ID);

        assertThat(section).contains("## 你的历史表现");
        assertThat(section).contains("累计评审 10 次，通过率 80%");
        assertThat(section).contains("一次通过率 60%");
        assertThat(section).contains("最常见驳回原因 TOP3");
        assertThat(section).contains("缺少单测");
        assertThat(section).contains("文档不全");
        assertThat(section).contains("命名不规范");
        assertThat(section).contains("本轮提醒");
    }

    @Test
    @DisplayName("无缺陷标签 → 省略 TOP3 行，其余正常渲染")
    void renderHistoryWithoutDefects() {
        doReturn(profile(10, 8, 10, 6, 40)).when(service).getProfile(AGENT_ID);

        String section = service.renderHistorySection(AGENT_ID);

        assertThat(section).contains("## 你的历史表现");
        assertThat(section).doesNotContain("最常见驳回原因");
        assertThat(section).contains("本轮提醒");
    }

    // ════════════════════════════════════════════════════════════
    //  rebuild 重算兜底
    // ════════════════════════════════════════════════════════════

    private RebuildSourceRow row(long recordId, String result, int score, int round, String issues) {
        RebuildSourceRow r = new RebuildSourceRow();
        r.setRecordId(recordId);
        r.setResult(result);
        r.setScore(score);
        r.setRound(round);
        r.setIssues(issues);
        return r;
    }

    @Test
    @DisplayName("agentId 为 null → 直接返回（不触碰数据源）")
    void rebuildNullAgentId() {
        service.rebuild(null);
        verify(profileMapper, never()).selectRebuildSource(anyLong());
    }

    @Test
    @DisplayName("无评审记录 → 清除画像行")
    void rebuildNoRowsRemovesProfile() {
        doReturn(List.of()).when(profileMapper).selectRebuildSource(AGENT_ID);
        doReturn(true).when(service).remove(any());

        service.rebuild(AGENT_ID);

        verify(service).remove(any());
    }

    @Test
    @DisplayName("全量重算口径与增量一致：首轮通过/评分累加/返工轮次/缺陷标签/最后记录 ID")
    void rebuildAggregatesConsistently() {
        doReturn(List.of(
                row(11L, "APPROVED", 5, 1, "[defect] 缺单测 [location] x [impact] y [evidence] z"),
                row(12L, "REJECTED", 2, 2, "[defect] 缺单测 [location] x [impact] y [evidence] z"),
                row(13L, "REJECTED", 3, 1, "[defect] 文档不全 [location] x [impact] y [evidence] z")))
                .when(profileMapper).selectRebuildSource(AGENT_ID);
        doReturn(null).when(service).getProfile(AGENT_ID);
        doReturn(true).when(service).saveOrUpdate(any());

        service.rebuild(AGENT_ID);

        ArgumentCaptor<AgentQualityProfile> captor = ArgumentCaptor.forClass(AgentQualityProfile.class);
        verify(service).saveOrUpdate(captor.capture());
        AgentQualityProfile saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(saved.getReviewedCount()).isEqualTo(3);
        assertThat(saved.getApprovedCount()).isEqualTo(1);
        assertThat(saved.getFirstReviewedCount()).isEqualTo(2);
        assertThat(saved.getFirstPassCount()).isEqualTo(1);
        assertThat(saved.getTotalScore()).isEqualTo(10);
        assertThat(saved.getReworkRoundSum()).isEqualTo(1);
        assertThat(saved.getLastReviewRecordId()).isEqualTo(13L);
        assertThat(saved.getIssueDefectStats())
                .containsEntry("缺单测", 2)
                .containsEntry("文档不全", 1);
    }

    // ════════════════════════════════════════════════════════════
    //  incrementReviewerStats（反馈回路 Phase 4：双审/抽检 reviewer 维度计数）
    //  ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reviewerAgentId 为 null 或 delta 全非正 → 早退不触碰数据源")
    void reviewerStatsEarlyReturn() {
        service.incrementReviewerStats(null, 1, 0);
        service.incrementReviewerStats(AGENT_ID, 0, 0);
        service.incrementReviewerStats(AGENT_ID, -1, 0);
        verify(profileMapper, never()).incrementReviewerStats(anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("画像行存在 → UPDATE 命中（返回 1），不 INSERT")
    void reviewerStatsUpdateHit() {
        when(profileMapper.incrementReviewerStats(AGENT_ID, 1, 1, "review")).thenReturn(1);

        service.incrementReviewerStats(AGENT_ID, 1, 1);

        verify(profileMapper).incrementReviewerStats(AGENT_ID, 1, 1, "review");
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("画像行不存在 → UPDATE 未命中（返回 0）→ INSERT 仅 reviewer 维度画像")
    void reviewerStatsInsertWhenNoRow() {
        when(profileMapper.incrementReviewerStats(AGENT_ID, 2, 1, "review")).thenReturn(0);
        doReturn(true).when(service).save(any());

        service.incrementReviewerStats(AGENT_ID, 2, 1);

        ArgumentCaptor<AgentQualityProfile> captor = ArgumentCaptor.forClass(AgentQualityProfile.class);
        verify(service).save(captor.capture());
        AgentQualityProfile saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(AGENT_ID);
        // 执行者维度保持 0（reviewer 维度计数独立，rebuild 可覆盖）
        assertThat(saved.getReviewedCount()).isZero();
        assertThat(saved.getApprovedCount()).isZero();
        assertThat(saved.getTotalScore()).isZero();
        assertThat(saved.getReviewerReviewedCount()).isEqualTo(2);
        assertThat(saved.getReviewerDisagreementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("INSERT 并发唯一索引冲突 → 回退 UPDATE（另一路已建行）")
    void reviewerStatsConflictFallbackToUpdate() {
        when(profileMapper.incrementReviewerStats(AGENT_ID, 1, 0, "review")).thenReturn(0);
        doThrow(new RuntimeException("duplicate key")).when(service).save(any());

        service.incrementReviewerStats(AGENT_ID, 1, 0);

        // save 冲突后回退 UPDATE：incrementReviewerStats 总调用 2 次
        verify(profileMapper, times(2)).incrementReviewerStats(AGENT_ID, 1, 0, "review");
    }

    @Test
    @DisplayName("UPDATE 抛异常 → 静默吞掉（best-effort 不阻断双审/抽检主链路）")
    void reviewerStatsSwallowError() {
        when(profileMapper.incrementReviewerStats(AGENT_ID, 1, 0, "review"))
                .thenThrow(new RuntimeException("db down"));

        service.incrementReviewerStats(AGENT_ID, 1, 0);

        // 不抛异常即通过；不得触发 INSERT 兜底
        verify(service, never()).save(any());
    }

    // ════════════════════════════════════════════════════════════
    //  statsOverview / statsAgentRankings（Phase 5 质量度量看板）
    //  ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.147: statsOverview 透传 Mapper 聚合，null 兜底全 0")
    void statsOverviewPassthrough() {
        QualityOverview row = new QualityOverview(25, 18, 72, 0.6, 4);
        when(profileMapper.selectOverviewRow()).thenReturn(row);
        assertThat(service.statsOverview()).isEqualTo(row);

        // SQL COALESCE 理论上单行必返回，防御性 null 兜底也要成立
        when(profileMapper.selectOverviewRow()).thenReturn(null);
        assertThat(service.statsOverview()).isEqualTo(new QualityOverview(0, 0, 0, 0.0, 0));
    }

    @Test
    @DisplayName("§6.147: statsAgentRankings 补名 + qualityScore 复用 computeQualityScore 口径")
    void statsAgentRankingsAssemble() {
        List<AgentQualityRank> rows = List.of(
                new AgentQualityRank(1L, "", 10, 60, 0),
                new AgentQualityRank(7L, "", 5, 100, 0));
        when(profileMapper.selectRankingRows(10)).thenReturn(rows);
        Agent a1 = new Agent();
        a1.setId(1L);
        a1.setName("执行者甲");
        when(agentService.listByIds(List.of(1L, 7L))).thenReturn(List.of(a1));
        // qualityScore 由 computeQualityScore 逐行重算（SQL 只排序，占位 0 会被覆盖）
        doReturn(profile(10, 8, 10, 6, 40)).when(service).getProfile(1L);
        doReturn(profile(5, 5, 5, 5, 25)).when(service).getProfile(7L);

        List<AgentQualityRank> result = service.statsAgentRankings(10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).agentName()).isEqualTo("执行者甲");
        // 首轮通过率 60%×0.5 + 平均分 4 分归一 75×0.5 = 68
        assertThat(result.get(0).qualityScore()).isEqualTo(68);
        assertThat(result.get(1).agentName()).isEqualTo("7");
        // 100% 通过 + 满分 → 100
        assertThat(result.get(1).qualityScore()).isEqualTo(100);
        verify(profileMapper).selectRankingRows(10);
    }
}
