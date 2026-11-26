package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewMechanicalCheck}（`RV-10` 机械预检 v1）单测。
 *
 * <p>护栏：只测「确定性计算」本身——不依赖模型、不假设仓库源码；族校验与声称值比对
 * 复用同一份实测行（`RV-11` 的 {@link ReviewMechanicalCheck#measureOf} 从这里取数）。</p>
 */
@DisplayName("ReviewMechanicalCheck（RV-10 机械预检 v1）")
class ReviewMechanicalCheckTest {

    private static ReviewMechanicalCheck.Source csv(Long id, String name, String text) {
        return new ReviewMechanicalCheck.Source(id, name, text);
    }

    private static String csvRows(int dataRows, int cols) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            sb.append(c == 0 ? "" : ",").append("c").append(c);
        }
        sb.append('\n');
        for (int r = 0; r < dataRows; r++) {
            for (int c = 0; c < cols; c++) {
                sb.append(c == 0 ? "" : ",").append(r).append(c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("无表格类附件 ⇒ 空串（不产生「本就不是表格」的表单）")
    void returnsEmptyWhenNoTabularSource() {
        assertThat(ReviewMechanicalCheck.build(null)).isEmpty();
        assertThat(ReviewMechanicalCheck.build(List.of(
                csv(1L, "report.md", "# 标题\n正文")))).isEmpty();
    }

    @Test
    @DisplayName("单文件实测：物理行 / CSV 记录 / 数据行 / 列数 / 表头")
    void measuresSingleCsv() {
        ReviewMechanicalCheck.FileReport report = ReviewMechanicalCheck.analyze(
                csv(1L, "a.csv", "id,name\n1,a\n2,b\n"));

        assertThat(report.available()).isTrue();
        assertThat(report.lines()).isEqualTo(3);
        assertThat(report.rows()).isEqualTo(3);
        assertThat(report.data()).isEqualTo(2);
        assertThat(report.cols()).isEqualTo(2);
        assertThat(report.header()).isTrue();
    }

    @Test
    @DisplayName("列数不齐 ⇒ UNAVAILABLE reason=ragged_rows（不静默给数据行数）")
    void marksRaggedRowsUnavailable() {
        ReviewMechanicalCheck.FileReport report = ReviewMechanicalCheck.analyze(
                csv(2L, "b.csv", "a,b\n1,2\n3\n"));

        assertThat(report.available()).isFalse();
        assertThat(report.reason()).isEqualTo(ReviewMechanicalCheck.REASON_RAGGED_ROWS);
        assertThat(report.ragged()).isEqualTo(1);
    }

    @Test
    @DisplayName("编码损坏 ⇒ UNAVAILABLE reason=encoding_damaged")
    void marksEncodingDamaged() {
        ReviewMechanicalCheck.FileReport report = ReviewMechanicalCheck.analyze(
                csv(3L, "c.csv", "瀹炴祴,1\n"));

        assertThat(report.available()).isFalse();
        assertThat(report.reason()).isEqualTo(ReviewMechanicalCheck.REASON_ENCODING_DAMAGED);
        assertThat(report.encodingDamaged()).isTrue();
    }

    @Test
    @DisplayName("族完整：分片 sum_data 与主件 data 一致 ⇒ MATCH")
    void familyMatch() {
        String text = ReviewMechanicalCheck.build(List.of(
                csv(10L, "detail.csv", csvRows(10, 2)),
                csv(11L, "detail.part-01.csv", csvRows(4, 2)),
                csv(12L, "detail.part-02.csv", csvRows(6, 2))));

        assertThat(text).contains("family name=detail.csv parts=2 present=2 missing=- sum_data=10 main_data=10 MATCH");
        assertThat(text).doesNotContain("MISMATCH");
    }

    @Test
    @DisplayName("族不完整（分片合计与主件不一致）⇒ MISMATCH delta=±k")
    void familyMismatchWithDelta() {
        String text = ReviewMechanicalCheck.build(List.of(
                csv(20L, "detail.csv", csvRows(10, 2)),
                csv(21L, "detail.part-01.csv", csvRows(4, 2)),
                csv(22L, "detail.part-02.csv", csvRows(7, 2))));

        assertThat(text).contains("sum_data=11 main_data=10 MISMATCH delta=+1");
    }

    @Test
    @DisplayName("缺片：present 只计存在的编号，missing 列出缺口编号")
    void listsMissingParts() {
        String text = ReviewMechanicalCheck.build(List.of(
                csv(31L, "data-part-01.csv", csvRows(2, 2)),
                csv(33L, "data-part-03.csv", csvRows(2, 2))));

        assertThat(text).contains("family name=data-part-01.csv parts=2 present=2 missing=02");
    }

    @Test
    @DisplayName("分片列数不一致 ⇒ 族结论 UNAVAILABLE reason=part_cols_mismatch")
    void partColumnMismatchUnavailable() {
        String text = ReviewMechanicalCheck.build(List.of(
                csv(41L, "x.part-01.csv", csvRows(2, 2)),
                csv(42L, "x.part-02.csv", csvRows(2, 3))));

        assertThat(text).contains("status=UNAVAILABLE reason=part_cols_mismatch");
    }

    @Test
    @DisplayName("v1 不输出 claim 行（声称值来源在 LLM 调用之后）")
    void neverEmitsClaimRow() {
        String text = ReviewMechanicalCheck.build(List.of(csv(50L, "a.csv", csvRows(3, 2))));
        assertThat(text).doesNotContain("claim=");
    }

    @Test
    @DisplayName("measureOf：指定 id 命中；null id 仅在恰好一行时生效；两行时返回 null")
    void measureOfLocatesSingleSource() {
        String one = ReviewMechanicalCheck.build(List.of(csv(60L, "a.csv", csvRows(3, 2))));
        String two = ReviewMechanicalCheck.build(List.of(
                csv(61L, "a.csv", csvRows(3, 2)),
                csv(62L, "b.csv", csvRows(5, 2))));

        assertThat(ReviewMechanicalCheck.measureOf(one, null).data()).isEqualTo(3);
        assertThat(ReviewMechanicalCheck.measureOf(one, 60L).lines()).isEqualTo(4);
        assertThat(ReviewMechanicalCheck.measureOf(two, null)).isNull();
        assertThat(ReviewMechanicalCheck.measureOf(two, 62L).data()).isEqualTo(5);
        assertThat(ReviewMechanicalCheck.measureOf(two, 99L)).isNull();
    }

    @Test
    @DisplayName("1200 字符上限：超限按优先级截断并标 omitted")
    void capsOutputAndMarksOmitted() {
        List<ReviewMechanicalCheck.Source> sources = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            sources.add(csv(70L + i, String.format("f%02d.csv", i), csvRows(1, 2)));
        }
        String text = ReviewMechanicalCheck.build(sources);

        assertThat(text.length()).isLessThanOrEqualTo(ReviewMechanicalCheck.MAX_OUTPUT_CHARS);
        assertThat(text).contains("omitted=");
    }

    @Test
    @DisplayName("UNAVAILABLE 行优先保留（即使输出被裁剪，缺陷文件仍可见）")
    void unavailableRowsSurviveTruncation() {
        List<ReviewMechanicalCheck.Source> sources = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            sources.add(csv(80L + i, String.format("f%02d.csv", i), csvRows(1, 2)));
        }
        sources.add(csv(199L, "broken.csv", "a,b\n1,2\n3\n"));

        String text = ReviewMechanicalCheck.build(sources);

        assertThat(text).contains("name=broken.csv status=UNAVAILABLE reason=ragged_rows");
    }

    @Test
    @DisplayName("G-034 大批量：族行（聚合结论）必须比单文件明细先落地——35 附件批实测回归")
    void familyLineSurvivesTruncationInLargeBatch() {
        // 复现真机形态：25 片 + 主件 + 若干非表格件。片各 1 行数据，但片数足够把 1,200 字符吃满。
        List<ReviewMechanicalCheck.Source> sources = new ArrayList<>();
        long id = 1000L;
        for (int i = 1; i <= 25; i++) {
            sources.add(csv(id++, String.format("detail.part-%02d.csv", i), csvRows(2, 5)));
        }
        sources.add(csv(id, "detail.csv", csvRows(50, 5)));
        for (int i = 1; i <= 15; i++) {
            sources.add(csv(id + i, String.format("filler%02d.csv", i), csvRows(3, 5)));
        }

        String text = ReviewMechanicalCheck.build(sources);

        assertThat(text.length()).isLessThanOrEqualTo(ReviewMechanicalCheck.MAX_OUTPUT_CHARS);
        // 核心判据：族行必须在（否则「25 片齐全 / 合计一致」这条聚合事实整条丢失）
        assertThat(text).contains("family name=detail.csv parts=25 present=25 missing=-");
        // 且族行排在 file 明细之前（换序前是被明细挤掉的）
        assertThat(text.indexOf("family name=detail.csv"))
                .isLessThan(text.indexOf("name=detail.part-01.csv"));
    }

    @Test
    @DisplayName("isTabular：扩展名或 MIME 命中即表格；txt 不误判")
    void tabularByExtensionOrMime() {
        assertThat(ReviewMechanicalCheck.isTabular("a.csv", null)).isTrue();
        assertThat(ReviewMechanicalCheck.isTabular("a.tsv", null)).isTrue();
        assertThat(ReviewMechanicalCheck.isTabular("notes.txt", "text/csv")).isTrue();
        assertThat(ReviewMechanicalCheck.isTabular("notes.txt", "text/plain")).isFalse();
    }
}
