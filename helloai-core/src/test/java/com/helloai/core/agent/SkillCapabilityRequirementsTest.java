package com.helloai.core.agent;

import com.helloai.core.system.port.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillCapabilityRequirements} 谓词映射单测（`M-9`）。
 *
 * <p>覆盖：{@code REQUIRED} 键集 == {@code STANDARD_SKILLS}（双向包含）/
 * {@code satisfies} 判定（thinking←reasoning、web-search←webSearch、作业类型恒真）/
 * {@code lockedSkills}/{@code optionalSkills} 派生 / 未识别模型降级 /
 * <b>穷举还原测</b>（以 158 行实测谓词集为输入，断言派生结果 == V52 两列原值）。</p>
 */
class SkillCapabilityRequirementsTest {

    private static ModelCapabilities caps(boolean reasoning, boolean webSearch) {
        return caps(reasoning, true, webSearch);
    }

    private static ModelCapabilities caps(boolean reasoning, boolean toolCalling, boolean webSearch) {
        return new ModelCapabilities(List.of("text"), List.of("text"), reasoning, toolCalling, webSearch);
    }

    /** psql `-tA` 的 boolean 输出为 {@code t}/{@code f}；亦兼容 {@code true}/{@code false}。 */
    private static boolean truthy(String value) {
        return "t".equalsIgnoreCase(value) || Boolean.parseBoolean(value);
    }

    @Test
    @DisplayName("REQUIRED 键集 == STANDARD_SKILLS（8 项，双向包含）")
    void requiredKeySetEqualsStandardSkills() {
        Set<String> required = SkillCapabilityRequirements.required().keySet();
        assertThat(required).hasSize(8);
        assertThat(required).containsExactlyInAnyOrderElementsOf(AgentSkillDeriver.STANDARD_SKILLS);
        // 双向包含（防任一侧单边漂移）
        assertThat(AgentSkillDeriver.STANDARD_SKILLS).containsExactlyInAnyOrderElementsOf(required);
    }

    @Test
    @DisplayName("thinking 由 reasoning 驱动：reasoning=false ⇒ satisfies 为 false")
    void thinkingRequiresReasoning() {
        assertThat(SkillCapabilityRequirements.satisfies("thinking", caps(true, false))).isTrue();
        assertThat(SkillCapabilityRequirements.satisfies("thinking", caps(false, false))).isFalse();
        assertThat(SkillCapabilityRequirements.satisfies("thinking", caps(false, true))).isFalse();
    }

    @Test
    @DisplayName("web-search 由 webSearch 驱动：webSearch=false ⇒ satisfies 为 false")
    void webSearchRequiresWebSearch() {
        assertThat(SkillCapabilityRequirements.satisfies("web-search", caps(false, true))).isTrue();
        assertThat(SkillCapabilityRequirements.satisfies("web-search", caps(true, false))).isFalse();
    }

    @Test
    @DisplayName("作业类型技能恒真：python/docker/sql/java/shell/code-review 在最弱模型下仍 satisfies")
    void jobTypeSkillsAlwaysSatisfied() {
        ModelCapabilities weakest = caps(false, false);
        for (String skill : List.of("shell", "docker", "sql", "code-review", "python", "java")) {
            assertThat(SkillCapabilityRequirements.satisfies(skill, weakest))
                    .as("作业类型技能 %s 应恒真", skill).isTrue();
        }
    }

    @Test
    @DisplayName("自定义技能（非标准词表）恒真")
    void customSkillAlwaysSatisfied() {
        assertThat(SkillCapabilityRequirements.satisfies("kubernetes", caps(false, false))).isTrue();
    }

    @Test
    @DisplayName("未识别模型（caps == null）：不构成越界（降级放行）")
    void unrecognizedModelNotGated() {
        assertThat(SkillCapabilityRequirements.satisfies("thinking", null)).isTrue();
        assertThat(SkillCapabilityRequirements.satisfies("web-search", null)).isTrue();
    }

    @Test
    @DisplayName("lockedSkills：reasoning=true ⇒ [\"thinking\"]；否则空；caps=null 亦空")
    void lockedSkillsDerivation() {
        assertThat(SkillCapabilityRequirements.lockedSkills(caps(true, false))).containsExactly("thinking");
        assertThat(SkillCapabilityRequirements.lockedSkills(caps(false, true))).isEmpty();
        assertThat(SkillCapabilityRequirements.lockedSkills(null)).isEmpty();
    }

    @Test
    @DisplayName("optionalSkills：恒定 [\"shell\",\"code-review\"]，webSearch=true 追加 web-search")
    void optionalSkillsDerivation() {
        assertThat(SkillCapabilityRequirements.optionalSkills(caps(true, false)))
                .containsExactly("shell", "code-review");
        assertThat(SkillCapabilityRequirements.optionalSkills(caps(true, true)))
                .containsExactly("shell", "code-review", "web-search");
        assertThat(SkillCapabilityRequirements.optionalSkills(null))
                .containsExactly("shell", "code-review");
    }

    @Test
    @DisplayName("provides：随谓词给出 reasoning / toolCalling / webSearch")
    void providesReflectsPredicates() {
        assertThat(SkillCapabilityRequirements.provides(caps(true, true, true)))
                .containsExactlyInAnyOrder("reasoning", "toolCalling", "webSearch");
        assertThat(SkillCapabilityRequirements.provides(caps(false, true, false)))
                .containsExactly("toolCalling");
        assertThat(SkillCapabilityRequirements.provides(caps(false, false, false))).isEmpty();
        assertThat(SkillCapabilityRequirements.provides(null)).isEmpty();
    }

    // ════════════════════════════════════════════════════════════
    //  穷举还原测：以 158 行实测谓词集为输入，断言派生结果 == V52 两列原值
    //  资源 m9-v52-reproduction.tsv 由「V112 执行前」的本地库唯读导出（read-only SELECT）。
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("穷举还原：optionalSkills(caps) == V52 两列-可选集（158/158 行，0 失配）")
    void v52Reproduction_optionalSkillsMatchAllRows() throws Exception {
        List<String[]> rows = loadReproductionRows();
        assertThat(rows).as("实测行数（V110 起 158 行）").hasSize(158);

        int mismatch = 0;
        for (String[] r : rows) {
            ModelCapabilities c = caps(truthy(r[2]), truthy(r[3]));
            if (!new LinkedHashSet<>(SkillCapabilityRequirements.optionalSkills(c))
                    .equals(new LinkedHashSet<>(parseJsonArray(r[5])))) {
                mismatch++;
            }
        }
        assertThat(mismatch).as("optionalSkills 与 V52 两列-可选集 的失配行数").isZero();
    }

    @Test
    @DisplayName("穷举还原：reasoning=true ⇒ lockedSkills == V52 两列-锁定集（==[\"thinking\"]，110 行）")
    void v52Reproduction_lockedSkillsMatchReasoningRows() throws Exception {
        List<String[]> rows = loadReproductionRows();
        int checked = 0;
        for (String[] r : rows) {
            if (!truthy(r[2])) {
                continue;   // reasoning=false 的 48 行属裁定 ① 的有意差异，单列断言
            }
            checked++;
            assertThat(SkillCapabilityRequirements.lockedSkills(caps(true, truthy(r[3]))))
                    .as("reasoning=true 行 %s/%s", r[0], r[1])
                    .containsExactlyInAnyOrderElementsOf(parseJsonArray(r[4]));
        }
        assertThat(checked).as("reasoning=true 行数").isEqualTo(110);
    }

    @Test
    @DisplayName("★裁定①差异单列断言：reasoning=false 的 48 行，V52 锁 thinking 而 M-9 派生为空")
    void v52Reproduction_thinkingDifferenceOnReasoningFalseRows() throws Exception {
        List<String[]> rows = loadReproductionRows();
        int diff = 0;
        for (String[] r : rows) {
            if (truthy(r[2])) {
                continue;
            }
            // V52 列恒 ["thinking"]；M-9 派生为空 —— 差异行数必须恰为 48
            assertThat(parseJsonArray(r[4])).containsExactly("thinking");
            assertThat(SkillCapabilityRequirements.lockedSkills(caps(false, truthy(r[3])))).isEmpty();
            diff++;
        }
        assertThat(diff).as("thinking 由 [\"thinking\"] 变 [] 的行数（裁定①）").isEqualTo(48);
    }

    private static List<String[]> loadReproductionRows() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = SkillCapabilityRequirementsTest.class
                .getResourceAsStream("/m9-v52-reproduction.tsv")) {
            assertThat(in).as("测试资源 m9-v52-reproduction.tsv 必须存在").isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    /** 解析 TSV 中的 JSON 数组文本（如 {@code ["shell", "code-review"]}）为有序列表。 */
    private static List<String> parseJsonArray(String text) {
        String inner = text.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        List<String> tokens = new ArrayList<>();
        for (String part : Arrays.asList(inner.split(","))) {
            String t = part.trim().replace("\"", "").trim();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }
}
