package com.helloai.core.agent.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper;
import com.helloai.core.task.entity.ReviewRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质量画像增量维护器单元测试（反馈回路第 1 层，Phase 1.5）。
 *
 * <p>覆盖：null 防御、首次建画像初始值口径、防重幂等（last_review_record_id 递增判定）、
 * 增量原子调用参数口径（首轮通过/返工轮次/评分）、incrementCore 0 行短路、
 * DuplicateKeyException 并发退化增量，以及 DefectLabelParser 四元组标签解析。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QualityProfileUpdater 画像增量维护")
class QualityProfileUpdaterTest {

    private static final long AGENT_ID = 1L;

    @Mock
    private AgentQualityProfileMapper profileMapper;

    private QualityProfileUpdater updater;

    @BeforeEach
    void setUp() {
        updater = new QualityProfileUpdater(profileMapper, new ObjectMapper());
    }

    private ReviewRecord record(long id, ReviewResult result, Integer score, int round, String issues) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setResult(result);
        r.setScore(score);
        r.setRound(round);
        r.setIssues(issues);
        return r;
    }

    private AgentQualityProfile existing(long lastReviewRecordId) {
        AgentQualityProfile p = new AgentQualityProfile();
        p.setId(99L);
        p.setAgentId(AGENT_ID);
        p.setLastReviewRecordId(lastReviewRecordId);
        return p;
    }

    // ════════════════════════════════════════════════════════════
    //  null 防御
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("executorAgentId 为 null → 跳过且不触碰 mapper")
    void skipWhenAgentIdNull() {
        updater.onReviewRecordPersisted(null, record(1L, ReviewResult.APPROVED, 5, 1, null));
        verify(profileMapper, never()).selectOne(any());
        verify(profileMapper, never()).incrementCore(anyLong(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("record 为 null 或 record.id 为 null → 跳过")
    void skipWhenRecordInvalid() {
        updater.onReviewRecordPersisted(AGENT_ID, null);
        ReviewRecord noId = new ReviewRecord();
        updater.onReviewRecordPersisted(AGENT_ID, noId);
        verify(profileMapper, never()).selectOne(any());
    }

    // ════════════════════════════════════════════════════════════
    //  首次建画像
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("画像不存在 → 插入初始行（首条 review 贡献作为初始值）")
    void insertInitialProfileOnFirstReview() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        ReviewRecord rec = record(10L, ReviewResult.APPROVED, 5, 1,
                "[defect] 缺单测 [location] x [impact] y [evidence] z");

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        ArgumentCaptor<AgentQualityProfile> captor = ArgumentCaptor.forClass(AgentQualityProfile.class);
        verify(profileMapper).insert(captor.capture());
        AgentQualityProfile saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(saved.getReviewedCount()).isEqualTo(1);
        assertThat(saved.getApprovedCount()).isEqualTo(1);
        assertThat(saved.getFirstReviewedCount()).isEqualTo(1);
        assertThat(saved.getFirstPassCount()).isEqualTo(1);
        assertThat(saved.getTotalScore()).isEqualTo(5);
        assertThat(saved.getReworkRoundSum()).isZero();
        assertThat(saved.getLastReviewRecordId()).isEqualTo(10L);
        assertThat(saved.getIssueDefectStats()).containsEntry("缺单测", 1);
        assertThat(saved.getReviewerReviewedCount()).isZero();
    }

    @Test
    @DisplayName("首条即 round=2 REJECTED → 首轮通过计数为 0、返工轮次 1")
    void insertInitialProfileRoundTwoRejected() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        ReviewRecord rec = record(10L, ReviewResult.REJECTED, 2, 2, null);

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        ArgumentCaptor<AgentQualityProfile> captor = ArgumentCaptor.forClass(AgentQualityProfile.class);
        verify(profileMapper).insert(captor.capture());
        AgentQualityProfile saved = captor.getValue();
        assertThat(saved.getReviewedCount()).isEqualTo(1);
        assertThat(saved.getApprovedCount()).isZero();
        assertThat(saved.getFirstReviewedCount()).isZero();
        assertThat(saved.getFirstPassCount()).isZero();
        assertThat(saved.getReworkRoundSum()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════
    //  防重幂等 + 增量口径
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("防重：last_review_record_id >= 本次 record.id → 不重复计数")
    void skipDuplicateReviewRecord() {
        when(profileMapper.selectOne(any())).thenReturn(existing(10L));
        ReviewRecord rec = record(10L, ReviewResult.APPROVED, 5, 1, null);

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        verify(profileMapper, never()).incrementCore(anyLong(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("round=2 REJECTED 增量口径：reviewed+1 / rework+1 / 首轮与通过均为 0")
    void incrementCoreRoundTwoRejected() {
        when(profileMapper.selectOne(any())).thenReturn(existing(9L));
        when(profileMapper.incrementCore(eq(AGENT_ID), eq(1), eq(0), eq(0), eq(0), eq(2), eq(1),
                eq(10L), anyString())).thenReturn(1);
        ReviewRecord rec = record(10L, ReviewResult.REJECTED, 2, 2, null);

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        verify(profileMapper).incrementCore(AGENT_ID, 1, 0, 0, 0, 2, 1, 10L, "quality-profile");
    }

    @Test
    @DisplayName("round=1 APPROVED 增量口径：通过/首轮/首轮通过均为 +1")
    void incrementCoreFirstRoundApproved() {
        when(profileMapper.selectOne(any())).thenReturn(existing(9L));
        when(profileMapper.incrementCore(eq(AGENT_ID), eq(1), eq(1), eq(1), eq(1), eq(5), eq(0),
                eq(10L), anyString())).thenReturn(1);
        ReviewRecord rec = record(10L, ReviewResult.APPROVED, 5, 1, null);

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        verify(profileMapper).incrementCore(AGENT_ID, 1, 1, 1, 1, 5, 0, 10L, "quality-profile");
    }

    @Test
    @DisplayName("incrementCore 返回 0 行（防重兜底）→ 跳过缺陷标签合并")
    void skipDefectMergeWhenIncrementZeroRows() {
        when(profileMapper.selectOne(any())).thenReturn(existing(9L));
        when(profileMapper.incrementCore(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyString())).thenReturn(0);
        ReviewRecord rec = record(10L, ReviewResult.REJECTED, 2, 2,
                "[defect] 缺单测 [location] x [impact] y [evidence] z");

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        verify(profileMapper, never()).mergeDefectStats(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("增量成功后缺陷标签 JSONB 合并（解析后 JSON 传入 mapper）")
    void mergeDefectStatsAfterIncrement() throws Exception {
        when(profileMapper.selectOne(any())).thenReturn(existing(9L));
        when(profileMapper.incrementCore(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyString())).thenReturn(1);
        ReviewRecord rec = record(10L, ReviewResult.REJECTED, 2, 2,
                "[defect] 缺单测 [location] x [impact] y [evidence] z");

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(profileMapper).mergeDefectStats(eq(AGENT_ID), jsonCaptor.capture(), eq("quality-profile"));
        Map<String, Integer> stats = new ObjectMapper().readValue(jsonCaptor.getValue(),
                new TypeReference<Map<String, Integer>>() {});
        assertThat(stats).containsEntry("缺单测", 1);
    }

    // ════════════════════════════════════════════════════════════
    //  并发退化
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("初始插入撞唯一索引 → 重查画像后退化为增量路径")
    void degradeToIncrementOnDuplicateKey() {
        when(profileMapper.selectOne(any())).thenReturn(null, existing(9L));
        when(profileMapper.insert(any(AgentQualityProfile.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(profileMapper.incrementCore(eq(AGENT_ID), eq(1), eq(0), eq(0), eq(0), eq(2), eq(1),
                eq(10L), anyString())).thenReturn(1);
        ReviewRecord rec = record(10L, ReviewResult.REJECTED, 2, 2, null);

        updater.onReviewRecordPersisted(AGENT_ID, rec);

        verify(profileMapper).incrementCore(AGENT_ID, 1, 0, 0, 0, 2, 1, 10L, "quality-profile");
    }

    @Test
    @DisplayName("增量链路异常 → best-effort 吞噬，不向调用方抛出")
    void swallowIncrementException() {
        when(profileMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        // 不应抛出异常
        updater.onReviewRecordPersisted(AGENT_ID, record(10L, ReviewResult.REJECTED, 2, 2, null));
    }

    // ════════════════════════════════════════════════════════════
    //  DefectLabelParser 四元组解析
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("四元组 [defect] 标签提取与归一化（空白折叠 + 计数合并）")
    void defectLabelParserNormalizesAndCounts() {
        String issues = "[defect]  缺少单元测试  [location] src/a [impact] 回归 [evidence] x\n"
                + "[defect] 缺少单元测试 [location] src/b [impact] 回归 [evidence] y\n"
                + "[defect] 接口未按契约实现 [location] c [impact] 联调阻塞 [evidence] z";
        Map<String, Integer> stats = DefectLabelParser.parse(issues);
        assertThat(stats).containsEntry("缺少单元测试", 2)
                .containsEntry("接口未按契约实现", 1);
    }

    @Test
    @DisplayName("无 [defect] 标签 → 空 map（合并侧跳过）")
    void defectLabelParserEmptyWhenNoDefect() {
        assertThat(DefectLabelParser.parse(null)).isEmpty();
        assertThat(DefectLabelParser.parse("普通评语，无四元组")).isEmpty();
    }
}
