package com.helloai.core.agent.quality;

import com.helloai.core.agent.quality.dto.RebuildSourceRow;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper;
import com.helloai.core.agent.quality.service.impl.AgentQualityProfileServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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

    private AgentQualityProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new AgentQualityProfileServiceImpl());
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
}
