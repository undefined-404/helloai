package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeliverableDraft} 单测（2026-10-25，`LOG-20261025-009` / `-010`）。
 *
 * <p>这三条判据决定「要不要用沙箱文件顶替对话正文」「要不要追加一次收尾成文轮」，
 * 判错任一方向都出事故：判宽 ⇒ 假成功继续进评审（3 轮驳回 → 死信）；判窄 ⇒ 合格短交付
 * 被沙箱草稿顶掉 / 每次短答白烧一轮 LLM。故逐条锁死。</p>
 */
@DisplayName("DeliverableDraft 可交付正文判据")
class DeliverableDraftTest {

    /** 实测形态：MiniMax 假成功交付物（`review_record` 第 1/2 轮驳回原文）。 */
    private static final String PROCESS_NARRATIVE_WITH_FENCE =
            "433, need 33+ more. Let me cut the intro's second clause entirely.\n\n"
                    + "VERIFICATION:\n- 核实说明：草稿正文不含 OA 接入 AI 应先做 RAG 的实质说明\n"
                    + "STATUS: BLOCKED\nNEXT_STEPS: 继续删减";

    @Test
    @DisplayName("★ 围栏里的「- 列表项」不算正文骨架（不剥围栏会把假成功判成可交付）")
    void fenceBulletsMustNotCountAsBodySkeleton() {
        // 前提校验：不剥围栏时 looksLikeDraftBody 会因「- 核实说明」返回 true
        assertThat(DeliverableDraft.looksLikeDraftBody(PROCESS_NARRATIVE_WITH_FENCE)).isTrue();
        // 结论：isDeliverable 必须判 false（剥围栏后正文只是两行英文过程自述）
        assertThat(DeliverableDraft.isDeliverable(PROCESS_NARRATIVE_WITH_FENCE)).isFalse();
        assertThat(DeliverableDraft.body(PROCESS_NARRATIVE_WITH_FENCE))
                .doesNotContain("VERIFICATION").doesNotContain("STATUS");
    }

    @Test
    @DisplayName("正文里正常引用 VERIFICATION 字样（末段不含 STATUS）⇒ 不当围栏剥离")
    void plainMentionOfFenceMarkerIsNotStripped() {
        String body = "# 围栏协议说明\n\nVERIFICATION: 是本平台的提交前自审标记，正文里可以正常提到它。";
        assertThat(DeliverableDraft.body(body)).isEqualTo(body);
        assertThat(DeliverableDraft.isDeliverable(body)).isTrue();
    }

    @Test
    @DisplayName("骨架 / 篇幅 / 空 三态")
    void skeletonOrLengthDecidesDeliverable() {
        // 有骨架：极短也算正文（2026-10-16 防护：完整草稿被收尾轮顶替的回归锁）
        assertThat(DeliverableDraft.isDeliverable("# 手册 - 最终整合报告\n\n## 执行摘要")).isTrue();
        // 无骨架但篇幅达标
        assertThat(DeliverableDraft.isDeliverable("说明".repeat(DeliverableDraft.MIN_BODY_CHARS / 2 + 1))).isTrue();
        // 无骨架且不足：正文不在草稿区
        assertThat(DeliverableDraft.isDeliverable("我先看一下任务要求，然后开始写。")).isFalse();
        // 空白 / null
        assertThat(DeliverableDraft.isDeliverable(null)).isFalse();
        assertThat(DeliverableDraft.isDeliverable("   \n\t ")).isFalse();
        // 只有围栏、正文空
        assertThat(DeliverableDraft.isDeliverable("VERIFICATION:\n- 核过了\nSTATUS: DONE")).isFalse();
    }

    @Test
    @DisplayName("bodyChars 只计正文（围栏字符不顶字数）")
    void bodyCharsExcludesFence() {
        String withFence = "# 标题\n\n正文一句。\n\nVERIFICATION:\n- 核实说明\nSTATUS: DONE";
        assertThat(DeliverableDraft.bodyChars(withFence))
                .isEqualTo(DeliverableDraft.compact("# 标题\n\n正文一句。").length());
        assertThat(DeliverableDraft.bodyChars(null)).isZero();
    }
}
