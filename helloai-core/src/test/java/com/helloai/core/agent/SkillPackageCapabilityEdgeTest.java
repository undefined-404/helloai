package com.helloai.core.agent;

import com.helloai.core.system.port.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G-022`「技能包 → 模型能力」引用边单测（2026-10-15）。
 *
 * <p>覆盖三组：
 * <ol>
 *   <li><b>谓词词表闭合</b>：{@code predicateVocabulary} 与谓词常量声明一致
 *       （守卫 S4 的单测镜像，防词表与常量漂移）；</li>
 *   <li><b>技能包引用边判定</b>：{@code satisfiesPackage} 的含/不含/未识别模型三态，
 *       以及批量 {@code unsatisfiedPackages} 只返回违规项；</li>
 *   <li><b>★反证</b>：引用边判据被停用时，挂载推导必须给出<b>不同</b>结果
 *       ——否则本组断言是自证的。</li>
 * </ol>
 */
class SkillPackageCapabilityEdgeTest {

    private static ModelCapabilities caps(boolean reasoning, boolean toolCalling, boolean webSearch) {
        return new ModelCapabilities(List.of("text"), List.of("text"), reasoning, toolCalling, webSearch);
    }

    // ================================================================
    // 组 1：谓词词表
    // ================================================================

    @Test
    @DisplayName("S4 镜像：谓词词表恰为三具名谓词，且 provides ⊆ 词表")
    void predicateVocabularyIsClosed() {
        Set<String> vocab = SkillCapabilityRequirements.predicateVocabulary();

        assertThat(vocab).containsExactlyInAnyOrder("reasoning", "webSearch", "toolCalling");
        // provides 产出的每个谓词都必须在词表内（否则会出现「provides 了但没人认」的谓词）
        for (String provided : SkillCapabilityRequirements.provides(caps(true, true, true))) {
            assertThat(vocab).contains(provided);
        }
        // 全 false 时 provides 为空
        assertThat(SkillCapabilityRequirements.provides(caps(false, false, false))).isEmpty();
    }

    // ================================================================
    // 组 2：技能包引用边判定
    // ================================================================

    @Test
    @DisplayName("引用边满足：requires ⊆ provides ⇒ 放行")
    void satisfiedWhenProvidesCoversRequires() {
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of("webSearch"), caps(false, false, true))).isTrue();
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of("reasoning", "webSearch"), caps(true, false, true))).isTrue();
    }

    @Test
    @DisplayName("引用边不满足：requires 含 provides 未覆盖的谓词 ⇒ 拒绝")
    void rejectedWhenRequiresNotCovered() {
        // webSearch 缺失（deepseek 系实测 webSearch=false ⇒ eng-web-research 不可派）
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of("webSearch"), caps(true, true, false))).isFalse();
        // 部分覆盖也算不满足（全部 requires 必须被覆盖）
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of("reasoning", "webSearch"), caps(true, true, false))).isFalse();
    }

    @Test
    @DisplayName("空引用边恒真（作业规范类技能包，任何模型可承接）")
    void emptyRequiresAlwaysSatisfied() {
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of(), caps(false, false, false))).isTrue();
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                null, caps(false, false, false))).isTrue();
    }

    @Test
    @DisplayName("未识别模型（caps==null）降级放行 —— 与 satisfies 同口径，不制造新拒绝面")
    void unrecognizedModelPassesThrough() {
        assertThat(SkillCapabilityRequirements.satisfiesPackage(List.of("webSearch"), null)).isTrue();
        assertThat(SkillCapabilityRequirements.satisfies("web-search", null)).isTrue();
    }

    @Test
    @DisplayName("批量判定只返回违规技能包，且保持输入顺序")
    void unsatisfiedPackagesReturnsOnlyOffenders() {
        Map<String, List<String>> requires = Map.of(
                "eng-web-research", List.of("webSearch"),
                "eng-code-review", List.of(),
                "eng-verification", List.of());

        // webSearch=false ⇒ 只有 eng-web-research 违规
        List<String> bad = SkillCapabilityRequirements.unsatisfiedPackages(
                requires, caps(true, true, false));
        assertThat(bad).containsExactly("eng-web-research");

        // webSearch=true ⇒ 全通过
        assertThat(SkillCapabilityRequirements.unsatisfiedPackages(
                requires, caps(true, true, true))).isEmpty();

        // null/空 ⇒ 空结果，不 NPE
        assertThat(SkillCapabilityRequirements.unsatisfiedPackages(null, caps(true, true, true))).isEmpty();
        assertThat(SkillCapabilityRequirements.unsatisfiedPackages(Map.of(), caps(true, true, true))).isEmpty();
    }

    // ================================================================
    // 组 3：★反证（断言非自证）
    // ================================================================

    @Test
    @DisplayName("★反证：两个关系正交 —— 标准技能恒真不等于技能包恒真")
    void twoRelationsAreOrthogonal() {
        // code-review 在标准技能侧是恒真的作业类型标签（M-9 实测口径）
        assertThat(SkillCapabilityRequirements.satisfies("code-review", caps(false, false, false))).isTrue();
        // 但「要求 webSearch 的技能包」在同模型上必须被拒 —— 两者不可混为一张表
        assertThat(SkillCapabilityRequirements.satisfiesPackage(
                List.of("webSearch"), caps(false, false, false))).isFalse();
    }

    @Test
    @DisplayName("★反证：provides 少一谓词 ⇒ 判定必须翻转（证明判定真读了 provides）")
    void verdictFlipsWhenPredicateMissing() {
        List<String> requires = List.of("webSearch");
        boolean withWeb = SkillCapabilityRequirements.satisfiesPackage(requires, caps(false, false, true));
        boolean withoutWeb = SkillCapabilityRequirements.satisfiesPackage(requires, caps(false, false, false));
        // 断言确为「一真一假」，而非两次都恒真/都恒假
        assertThat(List.of(withWeb, withoutWeb)).containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("★反证：unrecognized 降级会翻转结果（证明降级分支是活的，不是恒真死码）")
    void unrecognizedBranchIsReachable() {
        List<String> requires = List.of("webSearch");
        // 同一个 requires：已识别且满足 ⇒ true；未识别 ⇒ true（降级）；已识别且不满足 ⇒ false
        assertThat(SkillCapabilityRequirements.satisfiesPackage(requires, caps(false, false, true))).isTrue();
        assertThat(SkillCapabilityRequirements.satisfiesPackage(requires, null)).isTrue();
        assertThat(SkillCapabilityRequirements.satisfiesPackage(requires, caps(false, false, false))).isFalse();
    }

    // ================================================================
    // 组 4：挂载面（deriveWithCapabilities 的 ⑤）
    // ================================================================

    @Test
    @DisplayName("挂载面职责边界：derive 只做【幂等并入】，谓词过滤在装配层做")
    void mountIsIdempotentMergeOnly() {
        // deriveWithCapabilities 的第 ⑤ 步只负责「把调用方已筛好的标签并进来」，
        // **不自己判谓词**——判据只有SkillCapabilityRequirements 一处，
        // 筛选发生在 AgentSkillPolicyService.mountablePackageLabels（它持有技能包目录）。
        // 本断言锁住这个边界：若derive 开始自行判定，谓词判据就会分叉成两处。
        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "内部执行者", "描述", List.of(), caps(true, true, false),
                List.of("eng-code-review", "eng-doc-standard"));
        assertThat(skills).contains("eng-code-review", "eng-doc-standard");
        assertThat(skills).doesNotContain("eng-web-research"); // 未传入 ⇒ 不凭空新增
    }

    @Test
    @DisplayName("★反证：筛选逻辑真会剔除越界包 —— 模拟装配层筛选后的正确入参")
    void mountReceivesOnlyFilteredLabels() {
        // 装配层筛完：本模型 webSearch=false ⇒ 只把无引用边的包传下来
        List<String> mountable = new ArrayList<>();
        List<String> requires = List.of("webSearch");
        for (String label : List.of("eng-code-review", "eng-web-research")) {
            boolean ok = label.equals("eng-code-review")
                    || SkillCapabilityRequirements.satisfiesPackage(requires, caps(true, true, false));
            if (ok) {
                mountable.add(label);
            }
        }
        assertThat(mountable).containsExactly("eng-code-review");

        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "内部执行者", "描述", List.of(), caps(true, true, false), mountable);
        assertThat(skills).contains("eng-code-review");
        assertThat(skills).doesNotContain("eng-web-research");
    }

    @Test
    @DisplayName("挂载幂等：显式已声明的技能包标签不重复")
    void mountIsIdempotent() {
        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of("eng-code-review"), caps(true, true, true),
                List.of("eng-code-review", "eng-doc-standard"));
        assertThat(skills.stream().filter("eng-code-review"::equals)).hasSize(1);
        assertThat(skills).contains("eng-doc-standard");
    }

    @Test
    @DisplayName("兼容重载：不传技能包面 ⇒ 行为与补入 ⑤ 前逐字一致（无 eng-* 混入）")
    void legacyOverloadStaysIdentical() {
        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of("eng-code-review"), caps(true, true, true));
        // 显式传入的自定义/技能包标签仍保留（③ 分支既有行为），但不会被"自动新增"其他包
        assertThat(skills).containsExactly("thinking", "eng-code-review");
    }

    @Test
    @DisplayName("未识别模型（caps==null）：技能包面全挂载（降级放行），无NPE")
    void unrecognizedModelMountsAllPackages() {
        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of(), null, List.of("eng-web-research", "eng-doc-standard"));
        assertThat(skills).contains("eng-web-research", "eng-doc-standard");
    }

    @Test
    @DisplayName("已识别模型仍净化越界标准技能（挂载不破坏 M-9 净化语义）")
    void mountDoesNotBreakM9Sanitization() {
        // 名称含"搜索"⇒ 关键词会推导出 web-search，但模型 webSearch=false ⇒ 应被净化掉
        List<String> skills = AgentSkillDeriver.deriveWithCapabilities(
                null, "搜索助手", "负责搜索", List.of(), caps(true, true, false), List.of());
        assertThat(skills).doesNotContain("web-search");
    }

    @Test
    @DisplayName("空/ null 技能包面 ⇒ 无挂载、无 NPE")
    void nullPackageLabelsAreSafe() {
        assertThat(AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of(), caps(true, true, true), null)).isNotNull();
        assertThat(AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of(), caps(true, true, true), List.of())).isNotNull();
        List<String> empty = new ArrayList<>();
        assertThat(AgentSkillDeriver.deriveWithCapabilities(
                null, "执行者", "描述", List.of(), caps(true, true, true), empty)).isNotNull();
    }
}