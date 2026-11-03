package com.helloai.core.planner.assistant;

import com.helloai.core.planner.clarify.SkillDirective;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 助理对话的「本轮技能」指令（PA-11 v2）。
 *
 * <p>需求澄清链路把技能编码进消息前缀（{@code [skill:name]}）由
 * {@link SkillDirective} 解析；助理链路不跑技能轮（不建任务、不进沙箱），
 * 只需要把「用户在本轮选了什么技能」变成一段追加到 system prompt 的约束。
 * 两者共用同一份技能包正文（{@code pkg:<name>} 走 {@link SkillDirective#renderInstruction}），
 * 保证同一技能在两条链路里的规范一致。</p>
 *
 * <p><b>不编造</b>：技能标识不认识时返回空段（本轮退回普通助理问答），
 * 不猜「大概是这个意思」而注入平台并不支持的规范。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantSkillDirective {

    /** 产出型技能包前缀（{@code pkg:archify}）。 */
    public static final String PACKAGE_PREFIX = "pkg:";

    /** 「方案整理」：逐轮问清边界与取舍，再产出可执行方案。 */
    static final String PLANNER_INSTRUCTION = """

            ## 本轮技能：方案整理（用户已在界面上选定）

            本轮回答必须按「方案整理」的形态产出：先复述你理解的目标与关键约束，
            再列出尚未明确、需要用户拍板的边界与取舍（不要替用户假设），
            最后给出可执行方案（目标 / 实施步骤 / 验收标准 / 风险点）。
            信息足够时直接给完整方案；信息不足时最多提出 3 个最关键的问题，不要长篇追问。
            """;

    /** 「直接拆解」：跳过澄清，按当前描述直接产出方案并派任务。 */
    static final String TASK_INSTRUCTION = """

            ## 本轮技能：直接拆解（用户已在界面上选定）

            本轮回答必须按「直接拆解」的形态产出：跳过澄清，直接给出可执行的子任务清单，
            每个子任务标明负责角色、产出物与验收标准，并给出整体推进顺序。
            不要反问用户细节；确实缺失的前提用「假设」显式标注后继续。
            本轮只产出拆解结果，不要声称已建任务或已派发（建任务由用户显式触发）。
            """;

    private final SkillDirective skillDirective;

    /**
     * 渲染本轮技能注入段。
     *
     * @param skill 技能标识：{@code planner} / {@code task} / {@code pkg:<技能包名>}；空 → 空串
     * @return 追加到 system prompt 末尾的约束段；无技能或技能不可用时返回空串
     */
    public String render(String skill) {
        if (skill == null || skill.isBlank()) {
            return "";
        }
        String kind = skill.trim();
        if ("planner".equals(kind)) {
            return PLANNER_INSTRUCTION;
        }
        if ("task".equals(kind)) {
            return TASK_INSTRUCTION;
        }
        if (kind.startsWith(PACKAGE_PREFIX)) {
            String packageName = kind.substring(PACKAGE_PREFIX.length()).trim();
            if (packageName.isEmpty()) {
                return "";
            }
            // 技能包正文与需求澄清链路同源（同一份目录、同一份渲染）
            return skillDirective.renderInstruction(packageName);
        }
        log.warn("助理技能标识不认识，本轮不加技能约束: skill={}", kind);
        return "";
    }
}
