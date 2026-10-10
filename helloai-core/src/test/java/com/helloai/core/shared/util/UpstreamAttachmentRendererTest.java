package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpstreamAttachmentRenderer 单测（P-1 防御层 R2，2026-09-30 审计 §15.4）：
 * 多附件按预算渲染——主附件保底 + 次要最低配额 + 逐附件行边界截断 +
 * {@code [TRUNCATED] file=} 机读标注 + 输出总长 ≤ 预算的硬上界。
 */
@DisplayName("UpstreamAttachmentRenderer 上游附件预算渲染")
class UpstreamAttachmentRendererTest {

    private static UpstreamAttachmentRenderer.LoadedAttachment file(String name, String content) {
        return new UpstreamAttachmentRenderer.LoadedAttachment(name, content);
    }

    @Test
    @DisplayName("空列表 / 预算非正 → 空串")
    void shouldReturnEmptyForEmptyInput() {
        assertThat(UpstreamAttachmentRenderer.render(null, 4000)).isEmpty();
        assertThat(UpstreamAttachmentRenderer.render(List.of(), 4000)).isEmpty();
        assertThat(UpstreamAttachmentRenderer.render(List.of(file("a.md", "x")), 0)).isEmpty();
        assertThat(UpstreamAttachmentRenderer.render(List.of(file("a.md", "x")), -1)).isEmpty();
    }

    @Test
    @DisplayName("单附件未超预算 → 标题行 + 原文，无截断标注")
    void shouldRenderSingleFileIntact() {
        String out = UpstreamAttachmentRenderer.render(List.of(file("main.md", "主文件正文")), 4000);
        assertThat(out).isEqualTo("【文件：main.md】\n主文件正文");
        assertThat(out).doesNotContain("[TRUNCATED]");
    }

    @Test
    @DisplayName("单附件超预算 → 独享预算、行边界回退截断 + [TRUNCATED] file= 标注")
    void shouldTruncateSingleFileAtLineBoundary() {
        // 换行在 3500（budget=3902 的回退窗口 [3390, 3902] 内）→ 截点回退到 3500
        String body = "x".repeat(3500) + "\n" + "y".repeat(2000);
        String out = UpstreamAttachmentRenderer.render(List.of(file("main.md", body)), 4000);

        assertThat(out)
                .contains("[TRUNCATED] file=main.md shown=3500 total=5501 reason=dep_content_limit")
                .doesNotContain("y".repeat(100));
        assertThat(out.length()).isLessThanOrEqualTo(4000);
    }

    @Test
    @DisplayName("主附件超限 + 次附件超限 → 次附件仍可见（R2 核心：不再被单点截断整体吃掉）")
    void shouldKeepMinorAttachmentVisibleWhenMainOversized() {
        // 近似 tku-e2e-01 真实规模：主 7809 + 附录 19294，拼接 27,103 字符
        // 修复前：拼接后单点截断 4000 → appendix 整体落在截断线外、永远不可见
        String out = UpstreamAttachmentRenderer.render(List.of(
                file("main.md", "M".repeat(7809)),
                file("appendix.md", "A".repeat(19294))), 4000);

        assertThat(out)
                .contains("【文件：main.md】")
                .contains("【文件：appendix.md】")
                .contains("A".repeat(500))
                .doesNotContain("A".repeat(501))
                // 主附件保底（3248）+ 次附件最低配额（500）均为行边界硬切（无换行可回退）
                // （3248 = 原 3296 − 48：2026-10-10 每附件开销预留 84→108（标注行新增 id= 字段），
                //   2 个附件各让出 24 字符；次附件已在下限 500，不受影响 —— 保底语义不变）
                .contains("[TRUNCATED] file=main.md shown=3248 total=7809 reason=dep_content_limit")
                .contains("[TRUNCATED] file=appendix.md shown=500 total=19294 reason=dep_content_limit");
        assertThat(out.length()).isLessThanOrEqualTo(4000);
    }

    @Test
    @DisplayName("多附件（5 个）全超限 → 全部标题可见、每附件至少最低配额、总长 ≤ 预算")
    void shouldRespectTotalBudgetAcrossManyFiles() {
        List<UpstreamAttachmentRenderer.LoadedAttachment> files = List.of(
                file("f1.md", "x".repeat(5000)),
                file("f2.md", "x".repeat(5000)),
                file("f3.md", "x".repeat(5000)),
                file("f4.md", "x".repeat(5000)),
                file("f5.md", "x".repeat(5000)));

        String out = UpstreamAttachmentRenderer.render(files, 4000);

        for (String name : new String[] {"f1.md", "f2.md", "f3.md", "f4.md", "f5.md"}) {
            assertThat(out)
                    .contains("【文件：" + name + "】")
                    .contains("[TRUNCATED] file=" + name);
        }
        // 主附件 1530 / 次要均分 500：最末附件也保有最低配额（不整体消失）
        assertThat(out).contains("[TRUNCATED] file=f5.md shown=500 total=5000 reason=dep_content_limit");
        assertThat(out.length()).isLessThanOrEqualTo(4000);
    }

    @Test
    @DisplayName("附件名为空 → 兜底 attachment-N 占位名，标题行正常渲染")
    void shouldFallbackNameWhenBlank() {
        String out = UpstreamAttachmentRenderer.render(List.of(
                new UpstreamAttachmentRenderer.LoadedAttachment(null, "甲"),
                new UpstreamAttachmentRenderer.LoadedAttachment("   ", "乙")), 4000);

        assertThat(out)
                .contains("【文件：attachment-1】\n甲")
                .contains("【文件：attachment-2】\n乙");
    }

    @Test
    @DisplayName("截断标注带可寻址 ref：有 attachmentId 时输出 id=，消费侧据此回取全文（2026-10-10）")
    void shouldIncludeAttachmentIdInTruncatedMarker() {
        String out = UpstreamAttachmentRenderer.render(List.of(
                new UpstreamAttachmentRenderer.LoadedAttachment(
                        "big.md", "M".repeat(9000), 2105188736737857538L)), 2000);

        assertThat(out).contains("[TRUNCATED] file=big.md id=2105188736737857538 shown=");
        // 未被截断的附件不产出标注行（自然也不会有 id）
        String small = UpstreamAttachmentRenderer.render(List.of(
                new UpstreamAttachmentRenderer.LoadedAttachment("s.md", "短", 123L)), 2000);
        assertThat(small).doesNotContain("[TRUNCATED]");
    }

    @Test
    @DisplayName("非附件引用路径（无 attachmentId）→ 标注行省略 id 字段（不输出 id=null）")
    void shouldOmitIdWhenAbsent() {
        String out = UpstreamAttachmentRenderer.render(List.of(
                new UpstreamAttachmentRenderer.LoadedAttachment("text.md", "T".repeat(9000))), 2000);

        assertThat(out).contains("[TRUNCATED] file=text.md shown=")
                .doesNotContain("id=");
    }
}
