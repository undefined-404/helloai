package com.helloai.core.agent.tool;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 启用工具名并集（G-004 增量 A 的**单源实现**，REF-8.5 抽出）。
 *
 * <p><b>语义</b>：{@code 上下文工具（agent 侧授权 / 可注入集）} ∪ {@code 命中技能的 requiredTools}，
 * <b>去重保序</b>——上下文工具在前，技能声明工具按 {@code resolve} 返回序追加；
 * 技能未声明工具时<b>原样返回</b>入参（不复制、不改写，保证无技能链路零开销）。</p>
 *
 * <p><b>为什么抽成单源</b>：同一段逻辑此前在三个地方各写一份——
 * {@code AgentRuntimeContextAssembler}（装配请求集，供会话快照）、
 * {@code RuntimeTurnExecutor}（执行链解析生效形态）、REF-8.5 新增的业务面循环入口。
 * 三份副本正是「口径漂移」的温床（改一处忘两处 ⇒ 会话快照与模型可见面不一致）。
 * 现三处一律委托本类，判据「并集口径」只有一处。</p>
 *
 * <p><b>边界</b>：本类只做名字集合的并集，<b>不</b>做「哪些工具真正可用」的判定——
 * 那是 {@link ToolRegistry#resolve} 的职责（条件可用摘除 + 描述重写）。</p>
 */
public final class EnabledToolNames {

    private EnabledToolNames() {
    }

    /**
     * 并集：上下文工具 ∪ 技能声明工具（去重保序）。
     *
     * @param tools              上下文工具名（可空；null 视为空）
     * @param skillRequiredTools 命中技能的 requiredTools（可空 / 空 ⇒ 原样返回 {@code tools}）
     * @return 并集（恒非 null；保序、去重）
     */
    public static List<String> union(List<String> tools, List<String> skillRequiredTools) {
        if (skillRequiredTools == null || skillRequiredTools.isEmpty()) {
            return tools != null ? tools : List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (tools != null) {
            merged.addAll(tools);
        }
        merged.addAll(skillRequiredTools);
        return List.copyOf(merged);
    }
}
