package com.helloai.core.review.adapter;

import com.helloai.common.constant.ReviewResult;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.review.mapper.ReviewRecordMapper;
import com.helloai.core.review.mapper.ReviewRecheckLogMapper;
import com.helloai.core.task.port.ReviewFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewPortAdapter} 级联删除单测（D-1，2026-10-05）。
 *
 * <p>回归背景：{@code review_recheck_log} 对 {@code sub_task.id} <b>有外键</b>，任务级联删除
 * 此前完全未清理本表（现网 42 行）→ 一旦所辖子任务存在抽检日志，删 sub_task 即撞 FK → HTTP 500。
 * 现 {@link ReviewPortAdapter#physicalDeleteByTaskId} 同批清理 review_record + review_recheck_log。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewPortAdapter 级联删除（D-1）")
class ReviewPortAdapterTest {

    @Mock
    private ReviewRecordMapper reviewRecordMapper;
    @Mock
    private ReviewRecheckLogMapper reviewRecheckLogMapper;
    @InjectMocks
    private ReviewPortAdapter adapter;

    @Test
    @DisplayName("physicalDeleteByTaskId：★子先父后——先删 review_recheck_log(子) 再删 review_record(父)")
    void shouldDeleteChildBeforeParent() {
        when(reviewRecheckLogMapper.physicalDeleteByTaskId(9L)).thenReturn(1);
        when(reviewRecordMapper.physicalDeleteByTaskId(9L)).thenReturn(2);

        int deleted = adapter.physicalDeleteByTaskId(9L);

        assertThat(deleted).isEqualTo(3);
        // review_recheck_log.review_record_id FK→review_record(id)：顺序颠倒会撞 FK → 500
        InOrder inOrder = inOrder(reviewRecheckLogMapper, reviewRecordMapper);
        inOrder.verify(reviewRecheckLogMapper).physicalDeleteByTaskId(9L); // 子先
        inOrder.verify(reviewRecordMapper).physicalDeleteByTaskId(9L);     // 父后
    }

    @Test
    @DisplayName("physicalDeleteByTaskId：同时清 review_record 与 review_recheck_log，返回行数之和")
    void shouldDeleteRecordAndRecheckLog() {
        when(reviewRecordMapper.physicalDeleteByTaskId(9L)).thenReturn(2);
        when(reviewRecheckLogMapper.physicalDeleteByTaskId(9L)).thenReturn(1);

        int deleted = adapter.physicalDeleteByTaskId(9L);

        assertThat(deleted).isEqualTo(3);
        verify(reviewRecordMapper).physicalDeleteByTaskId(9L);
        verify(reviewRecheckLogMapper).physicalDeleteByTaskId(9L);
    }

    @Test
    @DisplayName("physicalDeleteByTaskId：空 taskId 短路，不触达任何 Mapper")
    void shouldShortCircuitOnNull() {
        assertThat(adapter.physicalDeleteByTaskId(null)).isZero();
        verifyNoInteractions(reviewRecordMapper, reviewRecheckLogMapper);
    }

    @Test
    @DisplayName("RV-2: listReviewFactsBySubTaskId 排除 INCONCLUSIVE（不进隐式评分样本）")
    void shouldExcludeInconclusiveFromReviewFacts() {
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(
                record(ReviewResult.APPROVED, 5),
                record(ReviewResult.INCONCLUSIVE, 5),
                record(ReviewResult.REJECTED, 2)));

        List<ReviewFact> facts = adapter.listReviewFactsBySubTaskId(1L);

        assertThat(facts).containsExactly(new ReviewFact(5, true), new ReviewFact(2, false));
    }

    @Test
    @DisplayName("RV-2: 仅 INCONCLUSIVE → 空事实清单（不产生 0 分/未通过样本）")
    void shouldReturnEmptyFactsWhenOnlyInconclusive() {
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(record(ReviewResult.INCONCLUSIVE, 4)));

        assertThat(adapter.listReviewFactsBySubTaskId(1L)).isEmpty();
    }

    private static ReviewRecord record(ReviewResult result, int score) {
        ReviewRecord record = new ReviewRecord();
        record.setResult(result);
        record.setScore(score);
        return record;
    }
}
