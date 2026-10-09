package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("技能 frontmatter 切分（REF-1.1a）")
class SkillFrontMatterTest {

    @Test
    @DisplayName("无 frontmatter：split 无 yaml，stripBody 原样返回（恒等，保证旧行为不变）")
    void noFrontMatterIsIdentity() {
        String raw = "# 标题\n\n正文\n\n---\n\n详细\n";
        SkillFrontMatter.Split split = SkillFrontMatter.split(raw);
        assertThat(split.hasFrontMatter()).isFalse();
        assertThat(split.broken()).isFalse();
        assertThat(split.body()).isEqualTo(raw);
        assertThat(SkillFrontMatter.stripBody(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("正常围栏：body 为闭合围栏之后的原文（含 h1）")
    void normalFrontMatter() {
        String raw = "---\nname: eng-x\nversion: \"1.0.0\"\n---\n\n# 标题\n\n正文\n";
        SkillFrontMatter.Split split = SkillFrontMatter.split(raw);
        assertThat(split.hasFrontMatter()).isTrue();
        assertThat(split.broken()).isFalse();
        assertThat(split.yaml()).isEqualTo("name: eng-x\nversion: \"1.0.0\"");
        assertThat(split.body()).isEqualTo("\n# 标题\n\n正文\n");
    }

    @Test
    @DisplayName("围栏未闭合 → broken，stripBody 返回空串（降级为「速览为空 ⇒ 跳过」）")
    void unclosedFenceIsBroken() {
        SkillFrontMatter.Split split = SkillFrontMatter.split("---\nname: x\n\n# 正文\n");
        assertThat(split.broken()).isTrue();
        assertThat(split.error()).contains("未闭合");
        assertThat(SkillFrontMatter.stripBody("---\nname: x\n\n# 正文\n")).isEmpty();
    }

    @Test
    @DisplayName("CRLF 文件与等价 LF 文件切分结果一致")
    void crlfEquivalentToLf() {
        String lf = "---\nname: x\n---\n\n# 标题\n\n正文\n";
        String crlf = lf.replace("\n", "\r\n");
        assertThat(SkillFrontMatter.split(crlf).body()).isEqualTo(SkillFrontMatter.split(lf).body());
        assertThat(SkillFrontMatter.split(crlf).yaml()).isEqualTo(SkillFrontMatter.split(lf).yaml());
    }

    @Test
    @DisplayName("UTF-8 BOM 前缀不影响切分")
    void bomIsStripped() {
        String raw = ((char) 0xFEFF) + "---\nname: x\n---\n\n# 标题\n";
        SkillFrontMatter.Split split = SkillFrontMatter.split(raw);
        assertThat(split.hasFrontMatter()).isTrue();
        assertThat(split.broken()).isFalse();
        assertThat(split.body()).isEqualTo("\n# 标题\n");
    }

    @Test
    @DisplayName("★ 正文内的详细规范分隔符不被误当围栏（REF-1.1a 定点回归）")
    void innerDetailSeparatorIsNotAFence() {
        String raw = "---\nname: x\n---\n\n# 标题\n\n## 执行速览\n\n1. 条目\n\n---\n\n## 详细规范\n\n细则\n";
        SkillFrontMatter.Split split = SkillFrontMatter.split(raw);
        assertThat(split.yaml()).isEqualTo("name: x");
        assertThat(split.body()).contains("\n---\n").contains("## 详细规范");
        assertThat(SkillSpeedSummaryRenderer.render(split.body()))
                .contains("## 执行速览")
                .doesNotContain("详细规范");
    }

    @Test
    @DisplayName("frontmatter 之后紧跟 ---（疑似多写了文档分隔符）→ broken")
    void extraFenceAfterFrontMatterIsBroken() {
        SkillFrontMatter.Split split = SkillFrontMatter.split("---\nname: x\n---\n---\n# 标题\n");
        assertThat(split.broken()).isTrue();
    }

    @Test
    @DisplayName("空 frontmatter（--- 紧邻 ---）→ broken")
    void emptyFrontMatterIsBroken() {
        SkillFrontMatter.Split split = SkillFrontMatter.split("---\n---\n\n# 标题\n");
        assertThat(split.broken()).isTrue();
        assertThat(split.error()).contains("为空");
    }

    @Test
    @DisplayName("渲染速览：剔 h1、按分隔符截断、trim")
    void renderStripsH1AndDetail() {
        String body = "\n# eng-x 平台技能规范\n\n> 说明\n\n## 执行速览\n\n1. 条目\n\n---\n\n## 详细规范\n\n细则\n";
        assertThat(SkillSpeedSummaryRenderer.render(body))
                .isEqualTo("> 说明\n\n## 执行速览\n\n1. 条目");
    }
}
