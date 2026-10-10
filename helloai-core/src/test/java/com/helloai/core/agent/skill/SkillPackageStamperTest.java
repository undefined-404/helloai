package com.helloai.core.agent.skill;

import com.helloai.common.base.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REF-1.4 验收：来源打戳 + 下游改写可识别。
 *
 * <p>本器当前<b>未接线</b>（无拷贝路径，见 {@link SkillPackageStamper} 类注释），
 * 故验收只能落在单测上——这正是 REF-1.4 原文写明的验收方式
 * （「单测：带标技能被下游改写时能被识别」）。</p>
 */
@DisplayName("技能包来源打戳（REF-1.4）")
class SkillPackageStamperTest {

    private static final String CONTENT = """
            ---
            name: eng-code-review
            version: 1.0.0
            description: 代码审查规范
            ---

            ## 执行速览

            1. 接口契约必须文档化。
            """;

    private final SkillPackageStamper stamper = new SkillPackageStamper();

    @Test
    @DisplayName("SNAPSHOT：打戳后内容完好可校验，戳在正文尾部且不进 frontmatter")
    void snapshotStampsIntact() {
        SkillPackageStamper.Stamped stamped =
                stamper.stamp(CONTENT, "eng-code-review@1.0.0", SkillPackageStamper.CopyPolicy.SNAPSHOT);

        assertThat(stamped.text()).startsWith("---");           // 戳没被塞到最前面
        assertThat(stamped.text()).contains("locked=\"0\"");
        assertThat(stamped.text()).contains("origin=\"eng-code-review@1.0.0\"");

        SkillPackageStamper.Verification v = stamper.verify(stamped.text());
        assertThat(v.stamped()).isTrue();
        assertThat(v.intact()).isTrue();
        assertThat(v.originRef()).isEqualTo("eng-code-review@1.0.0");
    }

    @Test
    @DisplayName("LOCK：锁定位置 1（禁止下游改写）")
    void lockMarksLocked() {
        SkillPackageStamper.Stamped stamped =
                stamper.stamp(CONTENT, "pkg@2.0.0", SkillPackageStamper.CopyPolicy.LOCK);

        assertThat(stamped.policy()).isEqualTo(SkillPackageStamper.CopyPolicy.LOCK);
        assertThat(stamped.text()).contains("locked=\"1\"");
        assertThat(stamper.verify(stamped.text()).intact()).isTrue();
    }

    @Test
    @DisplayName("DENY：直接拒绝拷贝")
    void denyRejects() {
        assertThatThrownBy(() -> stamper.stamp(CONTENT, "pkg@1.0.0", SkillPackageStamper.CopyPolicy.DENY))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DENY");
    }

    @Test
    @DisplayName("★下游改写被识别：改一个字符即 intact=false")
    void tamperedContentIsDetected() {
        String stamped = stamper.stamp(CONTENT, "pkg@1.0.0", SkillPackageStamper.CopyPolicy.LOCK).text();
        assertThat(stamper.verify(stamped).intact()).isTrue();

        // 下游把「必须文档化」改成「不必文档化」——摘要必须对不上
        String tampered = stamped.replace("必须文档化", "不必文档化");
        SkillPackageStamper.Verification v = stamper.verify(tampered);
        assertThat(v.stamped()).isTrue();
        assertThat(v.intact()).isFalse();
    }

    @Test
    @DisplayName("戳被截断 / 无戳文本：不误判为完好")
    void truncatedOrUnstamped() {
        assertThat(stamper.verify("纯正文，没有任何戳").stamped()).isFalse();

        String stamped = stamper.stamp(CONTENT, "pkg@1.0.0", SkillPackageStamper.CopyPolicy.SNAPSHOT).text();
        String truncated = stamped.substring(0, stamped.indexOf("<!--") + 12);
        assertThat(stamper.verify(truncated).intact()).isFalse();
    }

    @Test
    @DisplayName("originRef 含引号/换行被清洗，不破坏戳的可解析性")
    void originRefIsSanitized() {
        SkillPackageStamper.Stamped stamped =
                stamper.stamp(CONTENT, "a\"b\nc", SkillPackageStamper.CopyPolicy.SNAPSHOT);

        assertThat(stamped.text()).doesNotContain("origin=\"a\"b");
        assertThat(stamper.verify(stamped.text()).intact()).isTrue();
    }
}
