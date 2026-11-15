package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AttachmentFamilies}（`RV-9` / `RV-10` 共用族识别）单测。
 *
 * <p>冻结三种真实分片形态归入同一族，防止注入预算与机械校验各写一份前缀规则后分叉。</p>
 */
@DisplayName("AttachmentFamilies（RV-9/10 族识别）")
class AttachmentFamiliesTest {

    @Test
    @DisplayName("三种分片形态（X.part-01.csv / X.csv.part-01 / X-part-01.csv）归入同一族")
    void recognizesThreePartForms() {
        AttachmentFamilies.Membership beforeExt = AttachmentFamilies.parse("detail.part-01.csv");
        AttachmentFamilies.Membership afterExt = AttachmentFamilies.parse("detail.csv.part-01");
        AttachmentFamilies.Membership dash = AttachmentFamilies.parse("detail-part-01.csv");

        assertThat(beforeExt.familyKey()).isEqualTo("detail");
        assertThat(afterExt.familyKey()).isEqualTo("detail");
        assertThat(dash.familyKey()).isEqualTo("detail");
        assertThat(beforeExt.partIndex()).isEqualTo(1);
        assertThat(afterExt.partIndex()).isEqualTo(1);
        assertThat(dash.partIndex()).isEqualTo(1);
        assertThat(beforeExt.main()).isFalse();
    }

    @Test
    @DisplayName("主件（无分片标记）⇒ PART_NONE + main=true，族键去扩展名小写")
    void recognizesMainFile() {
        AttachmentFamilies.Membership main = AttachmentFamilies.parse("Detail.CSV");
        assertThat(main.familyKey()).isEqualTo("detail");
        assertThat(main.partIndex()).isEqualTo(AttachmentFamilies.PART_NONE);
        assertThat(main.main()).isTrue();
    }

    @Test
    @DisplayName("分片序号支持无连字符（part2）与多位编号（part-07）")
    void parsesPartIndexVariants() {
        assertThat(AttachmentFamilies.parse("data.part2.csv").partIndex()).isEqualTo(2);
        assertThat(AttachmentFamilies.parse("data-part-07.csv").partIndex()).isEqualTo(7);
        assertThat(AttachmentFamilies.parse("data.csv.part12").partIndex()).isEqualTo(12);
    }

    @Test
    @DisplayName("带路径的文件名取最后一段；路径分隔符 / 与 \\ 都识别")
    void takesBaseName() {
        assertThat(AttachmentFamilies.familyKeyOf("a/b/report.part-03.csv")).isEqualTo("report");
        assertThat(AttachmentFamilies.familyKeyOf("a\\b\\report.csv")).isEqualTo("report");
    }

    @Test
    @DisplayName("空 / null 文件名 ⇒ 空族键且非主件（不抛异常）")
    void toleratesBlankName() {
        AttachmentFamilies.Membership nullName = AttachmentFamilies.parse(null);
        AttachmentFamilies.Membership blank = AttachmentFamilies.parse("   ");

        assertThat(nullName.familyKey()).isEmpty();
        assertThat(nullName.main()).isFalse();
        assertThat(nullName.partIndex()).isEqualTo(AttachmentFamilies.PART_NONE);
        assertThat(blank.familyKey()).isEmpty();
    }

    @Test
    @DisplayName("非分片普通文件彼此族键互不相同（避免误并族）")
    void distinctPlainFilesStaySeparate() {
        assertThat(AttachmentFamilies.familyKeyOf("a.csv")).isEqualTo("a");
        assertThat(AttachmentFamilies.familyKeyOf("b.md")).isEqualTo("b");
        assertThat(AttachmentFamilies.familyKeyOf("a.csv")).isNotEqualTo(AttachmentFamilies.familyKeyOf("b.md"));
    }
}
