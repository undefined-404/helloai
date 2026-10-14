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
}
