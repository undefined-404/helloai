package com.helloai.core.agent;

import com.helloai.core.system.port.ModelCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型能力谓词 → 标准技能 的封闭映射（`M-9` 的<b>单一判据 / 单一实现点</b>）。
 *
 * <p>取代 `V52` 退役的两列（「模型能力锁定技能」/「可扩展技能白名单」）人工白名单：
 * 「技能是否越出模型实际能力」由<b>可判定的谓词关系</b>给出，而非两列手填值。</p>
 *
 * <p><b>映射（4.1 契约）</b>：{@link #REQUIRED} 为标准技能 → 所需谓词集；</p>
 * <ul>
 *   <li>{@code thinking} ← {@code reasoning == true}（efforts 驱动）；</li>
 *   <li>{@code web-search} ← {@code webSearch == true}（工具可用性）；</li>
 *   <li>{@code shell} / {@code docker} / {@code sql} / {@code code-review} / {@code python} / {@code java}
 *       → 所需谓词为空集（<b>恒真</b>；作业类型声明，非模型原生能力）。</li>
 * </ul>
 *
 * <p><b>判定式</b>：{@code satisfies(skill, caps) = REQUIRED(skill) 非空 ? REQUIRED ⊆ PROVIDES(caps) : true}。</p>
 *
 * <p><b>未识别模型（{@code caps == null}）</b>：与现状逐字一致 —— 不构成越界（降级放行），
 * 派生列表退化为 {@code lockedSkills=[]}、{@code optionalSkills=["shell","code-review"]}
 * （对齐 {@code AdminLlmProviderController} 的 {@code degraded} 分支）。</p>
 *
 * <p>{@code toolCalling} 谓词<b>已备但暂不消费</b>（实测 false 仅 8 行，纳入会新增拒绝面，
 * 超出「无损重放」范围）——仅在 {@link #PROVIDES} 口径内登记。</p>
 *
 * <p><b>★本类承担两个正交关系，不可合并为一张表</b>（`G-022`，2026-10-15）：</p>
 * <ul>
 *   <li><b>标准技能 ←→ 谓词</b>（{@link #REQUIRED} / {@link #satisfies}）：粗粒度，
 *       8 项标准词表是<b>模型能力标签</b>，落{@code agent.skills}；</li>
 *   <li><b>技能包 → 谓词</b>（{@link #satisfiesPackage} / frontmatter
 *       {@code requiredBaseCapabilities}）：细粒度，{@code eng-*} 是<b>技能条目</b>，
 *       落 {@code sub_task.required_skills}，经<b>引用边</b>指向谓词。</li>
 * </ul>
 * <p>第二个关系此前<b>完全缺失</b> ⇒ 选人处两套命名空间 AND 匹配恒失败
 * （{@code G-022}：有需求、无人被选上⇒ 永久 PENDING）。补的是<b>引用边</b>，
 * <b>不是统一词表</b>——统一词表会迫使技能包点名模型（DSH判据：技能不点名模型，
 * 非技能不提需求）。</p>
 */
public final class SkillCapabilityRequirements {

    private SkillCapabilityRequirements() {}

    /** 谓词名：推理（efforts）。 */
    private static final String PREDICATE_REASONING = "reasoning";
    /** 谓词名：联网检索（工具可用性）。 */
    private static final String PREDICATE_WEB_SEARCH = "webSearch";
    /** 谓词名：工具调用（已备、暂不消费）。 */
    private static final String PREDICATE_TOOL_CALLING = "toolCalling";

    /** 能力锁定技能：reasoning=true ⇒ 追加且不可取消。 */
    private static final String SKILL_THINKING = "thinking";
    /** 可扩展白名单的恒定段（对齐 V52 实测：差集只有 web-search）。 */
    private static final List<String> OPTIONAL_BASE = List.of("shell", "code-review");

    /**
     * 标准技能 → 所需谓词集（空集 = 恒真）。
     *
     * <p>键集<b>必须严格等于</b>{@link AgentSkillDeriver#STANDARD_SKILLS}（8 项，双向包含），
     * 由 {@code scripts/shell/verify-skill-capability-parity.sh}（T9-S1）与单测双向兜底。</p>
     */
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "thinking", Set.of(PREDICATE_REASONING),
            "web-search", Set.of(PREDICATE_WEB_SEARCH),
            "shell", Set.of(),
            "docker", Set.of(),
            "sql", Set.of(),
            "code-review", Set.of(),
            "python", Set.of(),
            "java", Set.of());

    /** 能力锁定技能（{@code reasoning=true ⇒ ["thinking"]}；未识别/无推理 ⇒ 空）。 */
    public static List<String> lockedSkills(ModelCapabilities caps) {
        if (caps == null || !caps.reasoning()) {
            return List.of();
        }
        return List.of(SKILL_THINKING);
    }

    /** 可扩展技能白名单（{@code ["shell","code-review"]} ++ {@code webSearch ? ["web-search"] : []}）。 */
    public static List<String> optionalSkills(ModelCapabilities caps) {
        List<String> skills = new ArrayList<>(OPTIONAL_BASE);
        if (caps != null && caps.webSearch()) {
            skills.add("web-search");
        }
        return skills;
    }

    /**
     * 判定 {@code skill} 是否被模型能力满足（越界判定）。
     *
     * @param skill 技能标签（非 {@link #REQUIRED} 键 = 自定义技能 ⇒ 恒真）
     * @param caps  模型能力谓词；{@code null}（未识别）⇒ 恒真（降级放行）
     */
    public static boolean satisfies(String skill, ModelCapabilities caps) {
        Set<String> required = REQUIRED.get(skill);
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (caps == null) {
            // 未识别模型：不构成越界（降级放行，与 validateAgentSkills 的 profile==null 早返回同口径）
            return true;
        }
        return provides(caps).containsAll(required);
    }

    /**
     * 模型当前提供的谓词集（{@link #REQUIRED} 的判定依据）。
     *
     * <p>模态（{@code input}/{@code output}）<b>不映射</b>到技能词表（不新增 vision/image 标签）；
     * 模态的落点是 {@code agent.capabilities} 的模态三键收敛（T8）。</p>
     */
    public static Set<String> provides(ModelCapabilities caps) {
        Set<String> provided = new LinkedHashSet<>();
        if (caps == null) {
            return provided;
        }
        if (caps.reasoning()) {
            provided.add(PREDICATE_REASONING);
        }
        if (caps.toolCalling()) {
            provided.add(PREDICATE_TOOL_CALLING);
        }
        if (caps.webSearch()) {
            provided.add(PREDICATE_WEB_SEARCH);
        }
        return provided;
    }

    /** 暴露所需谓词映射（只读，供守卫 / 测试核对键集）。 */
    public static Map<String, Set<String>> required() {
        return REQUIRED;
    }

    // ================================================================
    // 技能包级判定（`G-022` 引用边）：细粒度技能 → 粗粒度模型能力
    // ================================================================

    /**
     * 谓词词表（= {@link #provides} 的输出词表全集），供技能包 frontmatter
     * {@code requiredBaseCapabilities} 取值校验与守卫使用。
     *
     * <p>取值域<b>封闭</b>——技能包声明未登记的谓词即判 corrupt，避免拼写错误静默
     * 变成「永远满足」（空提供集的包含判定恒真 ⇒拼错的谓词会<b>放宽</b>而非收紧匹配，
     * 是<b>放宽型</b>失效方向，必须 fail-closed）。</p>
     */
    public static Set<String> predicateVocabulary() {
        return Set.of(PREDICATE_REASONING, PREDICATE_WEB_SEARCH, PREDICATE_TOOL_CALLING);
    }

    /**
     * 单个技能包是否可由该模型承接（引用边判定）。
     *
     * <p>判定式：{@code requiredBaseCapabilities 非空 ? requires ⊆ PROVIDES(caps) : true}。
     * 与 {@link #satisfies} 同构（空要求 = 恒真），只是判定的对象从
     * 「标准技能条目」换成「技能包的引用边」——两个关系<b>正交</b>，不可合并为一张表。</p>
     *
     * <p><b>未识别模型（{@code caps == null}）</b>：降级放行（恒真），与
     * {@link #satisfies} 同口径——否则未识别模型会把所有技能包全部拒绝，
     * 而拒绝面应在「声明是否越界」处，不在「能否降级运行」处。</p>
     *
     * @param requires 技能包的 {@code requiredBaseCapabilities}（不可为 null，空 = 无要求）
     * @param caps     模型能力谓词；{@code null}（未识别）⇒ 放行
     */
    public static boolean satisfiesPackage(List<String> requires, ModelCapabilities caps) {
        if (requires == null || requires.isEmpty()) {
            return true;
        }
        if (caps == null) {
            return true;
        }
        return provides(caps).containsAll(requires);
    }

    /**
     * 批量判定：返回<b>不满足</b>引用边的技能包名列表（空 = 全部可派；不可为 null）。
     *
     * <p>与 {@link #satisfies} 的批量版同形（返回「违规项」而非布尔），
     * 供注册校验拼错误消息。</p>
     */
    public static List<String> unsatisfiedPackages(Map<String, List<String>> packageRequires,
                                                   ModelCapabilities caps) {
        if (packageRequires == null || packageRequires.isEmpty()) {
            return List.of();
        }
        List<String> unsatisfied = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : packageRequires.entrySet()) {
            if (!satisfiesPackage(entry.getValue(), caps)) {
                unsatisfied.add(entry.getKey());
            }
        }
        return unsatisfied;
    }
}
