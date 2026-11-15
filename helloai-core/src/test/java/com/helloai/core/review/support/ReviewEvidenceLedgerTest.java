package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewEvidenceLedger}（`RV-8` 证据注入台账）单测。
 */
@DisplayName("ReviewEvidenceLedger（RV-8 台账 v1）")
class ReviewEvidenceLedgerTest {

    @Test
    @DisplayName("空清单也输出头行（reviewer 能知道平台没读到任何正文）")
    void rendersHeaderEvenWhenEmpty() {
        String ledger = ReviewEvidenceLedger.render(List.of(), 0);
        assertThat(ledger).isEqualTo("[EVIDENCE_LEDGER] v1 totalChars=0 entries=0 omitted=0");
    }

    @Test
    @DisplayName("v1 行格式：injected 带 range/shown，absent 带 reason，not_textual 不虚构正文")
    void rendersEntriesInV1Format() {
        List<ReviewEvidenceLedger.Entry> entries = List.of(
                new ReviewEvidenceLedger.Entry(1L, "report.md", 9000,
                        ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 9000, ""),
                new ReviewEvidenceLedger.Entry(2L, "data.csv", 155000,
                        ReviewEvidenceLedger.STATUS_TRUNCATED, ReviewEvidenceLedger.RANGE_HEAD_TAIL, 8192, ""),
                new ReviewEvidenceLedger.Entry(3L, "broken.csv", 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable"),
                new ReviewEvidenceLedger.Entry(4L, "chart.png", 0,
                        ReviewEvidenceLedger.STATUS_NOT_TEXTUAL, "", 0, ""));

        String ledger = ReviewEvidenceLedger.render(entries, 17192);

        assertThat(ledger).contains("[EVIDENCE_LEDGER] v1 totalChars=17192 entries=4 omitted=0");
        assertThat(ledger).contains("entry id=1 name=report.md chars=9000 status=injected range=full shown=9000");
        assertThat(ledger).contains("entry id=2 name=data.csv chars=155000 status=truncated range=head+tail shown=8192");
        assertThat(ledger).contains("entry id=3 name=broken.csv chars=0 status=absent reason=unreadable");
        assertThat(ledger).contains("entry id=4 name=chart.png chars=0 status=not_textual");
    }

    @Test
    @DisplayName("超过 60 条 ⇒ 只渲染前 60 行且头行 omitted=超限数")
    void capsAtSixtyEntries() {
        List<ReviewEvidenceLedger.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 61; i++) {
            entries.add(new ReviewEvidenceLedger.Entry((long) i, "f" + i + ".md", 10,
                    ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, ""));
        }
        String ledger = ReviewEvidenceLedger.render(entries, 610);

        assertThat(ledger).contains("entries=61 omitted=1");
        assertThat(ledger.lines().filter(line -> line.startsWith("entry "))).hasSize(60);
        assertThat(ledger).doesNotContain("entry id=61 ");
    }

    @Test
    @DisplayName("文件名空白归一为下划线，避免破坏行格式")
    void normalizesWhitespaceInName() {
        ReviewEvidenceLedger.Entry entry = new ReviewEvidenceLedger.Entry(7L, "a b\tc.md", 10,
                ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, "");
        assertThat(ReviewEvidenceLedger.render(List.of(entry), 10)).contains("name=a_b_c.md");
    }

    @Test
    @DisplayName("render → parse 往返一致（含 range/shown/reason）")
    void roundTripsThroughParse() {
        List<ReviewEvidenceLedger.Entry> entries = List.of(
                new ReviewEvidenceLedger.Entry(11L, "a.csv", 100,
                        ReviewEvidenceLedger.STATUS_TRUNCATED, ReviewEvidenceLedger.RANGE_HEAD, 50, ""),
                new ReviewEvidenceLedger.Entry(12L, "b.csv", 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable"));
        String ledger = ReviewEvidenceLedger.render(entries, 50);

        List<ReviewEvidenceLedger.Entry> parsed = ReviewEvidenceLedger.parse(ledger);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).id()).isEqualTo(11L);
        assertThat(parsed.get(0).range()).isEqualTo("head");
        assertThat(parsed.get(0).shown()).isEqualTo(50);
        assertThat(parsed.get(1).reason()).isEqualTo("unreadable");
    }

    @Test
    @DisplayName("anyTruncated / anyAbsent / find 按 status 判定")
    void detectsTruncatedAndAbsent() {
        String truncatedLedger = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "a.csv", 100,
                        ReviewEvidenceLedger.STATUS_TRUNCATED, ReviewEvidenceLedger.RANGE_HEAD, 50, "")), 50);
        String absentLedger = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(2L, "b.csv", 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable")), 0);

        assertThat(ReviewEvidenceLedger.anyTruncated(truncatedLedger)).isTrue();
        assertThat(ReviewEvidenceLedger.anyAbsent(truncatedLedger)).isFalse();
        assertThat(ReviewEvidenceLedger.anyAbsent(absentLedger)).isTrue();
        assertThat(ReviewEvidenceLedger.find(truncatedLedger, 1L)).isNotNull();
        assertThat(ReviewEvidenceLedger.find(truncatedLedger, 99L)).isNull();
    }

    @Test
    @DisplayName("gap：指定 id 不可读/被截断算缺口；注入完整不算")
    void gapByAttachmentId() {
        String ledger = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "full.md", 10,
                        ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, ""),
                new ReviewEvidenceLedger.Entry(2L, "cut.csv", 100,
                        ReviewEvidenceLedger.STATUS_TRUNCATED, ReviewEvidenceLedger.RANGE_HEAD, 50, ""),
                new ReviewEvidenceLedger.Entry(3L, "gone.csv", 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable")), 60);

        assertThat(ReviewEvidenceLedger.gap(ledger, 1L)).isFalse();
        assertThat(ReviewEvidenceLedger.gap(ledger, 2L)).isTrue();
        assertThat(ReviewEvidenceLedger.gap(ledger, 3L)).isTrue();
    }

    @Test
    @DisplayName("gap(未知 id)：台账非空且未溢出 ⇒ 不算缺口（编造 id 不该换来降级）")
    void unknownIdIsNotGapWhenLedgerComplete() {
        String ledger = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "full.md", 10,
                        ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, "")), 10);

        assertThat(ReviewEvidenceLedger.omittedCount(ledger)).isZero();
        assertThat(ReviewEvidenceLedger.gap(ledger, 99L)).isFalse();
    }

    @Test
    @DisplayName("gap(未知 id)：空台账 / 条目溢出 ⇒ 算缺口（模型无从断言 id 真伪）")
    void unknownIdIsGapWhenLedgerEmptyOrOmitted() {
        String empty = ReviewEvidenceLedger.render(List.of(), 0);
        assertThat(ReviewEvidenceLedger.gap(empty, 99L)).isTrue();

        List<ReviewEvidenceLedger.Entry> many = new ArrayList<>();
        for (long i = 1; i <= ReviewEvidenceLedger.MAX_ENTRIES + 1; i++) {
            many.add(new ReviewEvidenceLedger.Entry(i, "f" + i + ".csv", 10,
                    ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, ""));
        }
        String overflow = ReviewEvidenceLedger.render(many, 610);

        assertThat(ReviewEvidenceLedger.omittedCount(overflow)).isEqualTo(1);
        // 第 61 条被 MAX_ENTRIES 截掉，台账里查不到但确实注入过 ⇒ 归平台缺口
        assertThat(ReviewEvidenceLedger.gap(overflow, ReviewEvidenceLedger.MAX_ENTRIES + 1L)).isTrue();
        assertThat(ReviewEvidenceLedger.omittedCount(empty)).isZero();
        assertThat(ReviewEvidenceLedger.omittedCount(null)).isZero();
    }

    @Test
    @DisplayName("gap(null id)：按计划口径只认「存在截断」，不可读不因未引用 id 判缺口")
    void gapWithoutIdRequiresTruncation() {
        String clean = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "a.md", 10,
                        ReviewEvidenceLedger.STATUS_INJECTED, ReviewEvidenceLedger.RANGE_FULL, 10, "")), 10);
        String truncated = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "a.md", 100,
                        ReviewEvidenceLedger.STATUS_TRUNCATED, ReviewEvidenceLedger.RANGE_HEAD, 10, "")), 10);
        String absentOnly = ReviewEvidenceLedger.render(List.of(
                new ReviewEvidenceLedger.Entry(1L, "gone.csv", 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable")), 10);

        assertThat(ReviewEvidenceLedger.gap(clean, null)).isFalse();
        assertThat(ReviewEvidenceLedger.gap(truncated, null)).isTrue();
        assertThat(ReviewEvidenceLedger.gap(absentOnly, null)).isFalse();
    }

    @Test
    @DisplayName("畸形台账 / null ⇒ parse 返回空清单，不抛异常")
    void parseToleratesMalformedText() {
        assertThat(ReviewEvidenceLedger.parse(null)).isEmpty();
        assertThat(ReviewEvidenceLedger.parse("")).isEmpty();
        assertThat(ReviewEvidenceLedger.parse("[EVIDENCE_LEDGER] v1 totalChars=0 entries=0 omitted=0")).isEmpty();
        assertThat(ReviewEvidenceLedger.parse("entry id=x name=a chars=1 status=injected")).isEmpty();
    }
}
