package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩（REF-8.2.3）——对齐 DSH {@code compaction-basic} + {@code compaction-tool-result-pruner}。
 *
 * <p><b>两道，顺序与标杆一致</b>：① {@link ToolResultPruner}（确定性剪枝，纯文本、留标记、可审计）
 * ② 仍超预算才做 **LLM 摘要**（替换而非追加）。标杆原文：「任一触发条件满足后，系统都会先执行可选的
 * 工具结果剪枝，再选择摘要」（{@code docs/agent-lifecycle.zh.md}）。</p>
 *
 * <p><b>两条触发路径（对齐标杆两个挂点）</b>：
 * <ul>
 *   <li>{@code pressure}：每轮模型调用前，估算 token ≥ 窗口 × {@code thresholdRatio}（DSH 默认 0.8）——
 *       对应 DSH 的 {@code agent/pre-step} 压力检查；</li>
 *   <li>{@code overflow}：提供方调用抛错时，压缩后**重试本轮**（上限
 *       {@code contextCompactionMaxOverflowRetries}，成功响应后重置）——对应 DSH 的
 *       {@code agent/request-error} 溢出恢复。</li>
 * </ul></p>
 *
 * <p><b>摘要调用口径（对齐标杆）</b>：复用**同一 ChatModel 与同一 options（含工具）**，输入为
 * 「system + 首条 user（任务上下文）+ 被压区间的原文」+ 追加一条压缩指令——标杆的说法是
 * 「复用对话自身的 system prompt / tools / 前导消息，压缩指令是唯一的新增输入」，目的是保住缓存前缀。
 * 摘要**逐字保留最近 {@code retainRatio}（DSH 默认 0.16）比例的尾部**，且**绝不切开
 * assistant(tool_calls)+tool_result 配对**（标杆的 balanced boundary 纪律）。</p>
 *
 * <p><b>失败纪律</b>：压缩任何一步失败 → 只告警、**不阻断循环**（标杆同口径：
 * "step compaction failed … continuing the turn"）。</p>
 *
 * <p><b>与标杆的一处微调（已登记）</b>：剪枝后若已回到预算内，则**不再调用摘要**（标杆剪枝后即选摘要区）。
 * 省一次 LLM 调用、不改变语义与可审计性。</p>
 */
@Slf4j
@Component
public class ContextCompactor {

    /** 压缩指令（摘要调用里唯一新增的输入，其余为被压对话原文）。 */
    static final String COMPACTION_INSTRUCTION = """
            你现在充当本轮执行的「压缩引擎」。请把上面的对话（含工具调用与其结果）压缩成一份结构化检查点，
            使另一个模型能据此无损接续同一子任务。要求：
            1. 只写上面确实出现过的信息，不得推断、补全或编造（缺失就写「未涉及」）；
            2. 忠实保留：已达成的结论与产出、关键决策及其理由、未完成事项与下一步、硬约束与验收要点、
               评审方/上游的显式指令与修正意见；
            3. 工具输出已做过剪枝，保留其结论与关键字段即可，不要复述全文；
            4. 严格输出以下 Markdown 结构，不要输出任何其他文字：

            ## 已完成
            ## 关键决策
            ## 未完成 / 下一步
            ## 关键约束与事实
            ## 待跟进风险
            """;

    private final AgentExecutionProperties properties;
    private final boolean enabled;
    private final TokenCountEstimator tokenEstimator = new JTokkitTokenCountEstimator();

    @Autowired
    public ContextCompactor(AgentExecutionProperties properties) {
        this.properties = properties;
        this.enabled = properties != null && properties.isContextCompactionEnabled();
    }

    /** 无 Spring 上下文的 no-op 实例（单测/兜底构造用）。 */
    private ContextCompactor() {
        this.properties = null;
        this.enabled = false;
    }

    /** no-op 压缩器：{@code compactIfNeeded} 恒返回 {@code null}（行为与改造前逐字一致）。 */
    public static ContextCompactor noop() {
        return new ContextCompactor();
    }

    /**
     * 每轮模型调用前的压力检查（DSH {@code agent/pre-step} 等价物）。
     *
     * @return 压缩结果；未触发或未发生改动返回 {@code null}
     */
    public CompactionOutcome compactIfNeeded(List<Message> messages, ChatModel chatModel, ChatOptions options) {
        return compact(messages, chatModel, options, "pressure", false, Integer.MAX_VALUE);
    }

    /**
     * 压力检查（带本执行内的摘要次数剩余额度）。
     *
     * @param summarizationBudget 剩余可摘要次数；{@code <= 0} 时只做确定性剪枝，不开摘要
     */
    public CompactionOutcome compactIfNeeded(List<Message> messages, ChatModel chatModel, ChatOptions options,
                                             int summarizationBudget) {
        return compact(messages, chatModel, options, "pressure", false, summarizationBudget);
    }

    /**
     * 提供方报错后的溢出恢复（DSH {@code agent/request-error} 等价物）：强制走一遍压缩。
     *
     * @return 压缩结果；不可压缩（无中段可压 / 关闭）返回 {@code null}——调用方据此决定是否重试本轮
     */
    public CompactionOutcome compactOnOverflow(List<Message> messages, ChatModel chatModel, ChatOptions options) {
        return compact(messages, chatModel, options, "overflow", true, Integer.MAX_VALUE);
    }

    /** 溢出恢复（带摘要额度）。 */
    public CompactionOutcome compactOnOverflow(List<Message> messages, ChatModel chatModel, ChatOptions options,
                                               int summarizationBudget) {
        return compact(messages, chatModel, options, "overflow", true, summarizationBudget);
    }

    private CompactionOutcome compact(List<Message> messages, ChatModel chatModel, ChatOptions options,
                                      String trigger, boolean force, int summarizationBudget) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            int budget = compactionBudgetTokens();
            int before = estimateTokens(messages);
            if (!force && before < budget) {
                return null;
            }
            // 第一道：确定性剪枝（DSH compaction-tool-result-pruner）
            int pruned = ToolResultPruner.pruneMessages(messages,
                    properties.getToolResultPruneThresholdChars(),
                    properties.getToolResultPruneHeadChars(),
                    properties.getToolResultPruneTailChars());
            int afterPrune = estimateTokens(messages);
            if (!force && pruned > 0 && afterPrune < budget) {
                // 微调：剪枝已回到预算内 ⇒ 不再花一次 LLM 调用（见类 javadoc）
                return new CompactionOutcome(trigger, pruned, 0, before, afterPrune, false);
            }
            // 第二道：LLM 摘要（替换而非追加；逐字保留最近 retainRatio 尾部）
            // 额度耗尽时不开摘要（防「每轮都试一次」的抖动）
            int shadowed = summarizationBudget > 0 ? summarize(messages, chatModel, options) : 0;
            int afterSummary = estimateTokens(messages);
            if (pruned == 0 && shadowed == 0) {
                return null;
            }
            return new CompactionOutcome(trigger, pruned, shadowed, before, afterSummary, shadowed > 0);
        } catch (Exception e) {
            // 标杆口径：压缩失败仅告警，继续本轮（不阻断）
            log.warn("context compaction failed (trigger={}): {}", trigger, e.getMessage());
            return null;
        }
    }

    /** 单次执行内的摘要次数上限（防抖动；no-op 实例返回 0）。 */
    public int maxSummarizationsPerRun() {
        return properties == null ? 0 : Math.max(0, properties.getContextCompactionMaxSummariesPerRun());
    }

    /** 溢出重试上限（DSH `maxOverflowRetries`）；no-op 实例返回 0（不重试，保持改造前语义）。 */
    public int maxOverflowRetries() {
        return properties == null ? 0 : Math.max(0, properties.getContextCompactionMaxOverflowRetries());
    }

    /** 压力预算：窗口 × thresholdRatio（DSH `thresholdRatio` 默认 0.8）。 */
    private int compactionBudgetTokens() {
        return (int) Math.floor(properties.getContextWindowTokens() * properties.getContextCompactionThresholdRatio());
    }

    /** 尾部逐字保留预算（DSH `retainRatio` 默认 0.16）。 */
    private int retainTokens() {
        return (int) Math.floor(properties.getContextWindowTokens() * properties.getContextCompactionRetainRatio());
    }

    /**
     * 选区 + 摘要 + 替换。返回被取代的消息条数（0 表示未摘要）。
     *
     * <p>选区纪律（对齐 DSH {@code selectCompactableRange}）：**头部固定保留**
     * （system 消息 + 首条 user 任务上下文）、**尾部按 retainTokens 逐字保留**、
     * 只压中间；且以「单元」为单位——{@code assistant(tool_calls) + tool_result} 绝不拆开。</p>
     */
    private int summarize(List<Message> messages, ChatModel chatModel, ChatOptions options) {
        if (chatModel == null) {
            return 0;
        }
        int headCount = fixedHeadCount(messages);
        List<Unit> units = toUnits(messages, headCount);
        if (units.size() < 2) {
            return 0;
        }
        int retain = retainTokens();
        int tailStartUnit = units.size();
        int tailTokens = 0;
        while (tailStartUnit > 0 && tailTokens < retain) {
            tailStartUnit--;
            tailTokens += unitTokens(units.get(tailStartUnit));
        }
        if (tailStartUnit <= 0) {
            return 0;   // 尾部就吃满了全部单元 ⇒ 无可压区间
        }
        List<Unit> middle = units.subList(0, tailStartUnit);
        List<Unit> tail = units.subList(tailStartUnit, units.size());
        List<Message> summaryInput = new ArrayList<>();
        for (int i = 0; i < headCount; i++) {
            summaryInput.add(messages.get(i));
        }
        for (Unit unit : middle) {
            summaryInput.addAll(unit.messages());
        }
        summaryInput.add(new UserMessage(COMPACTION_INSTRUCTION));

        ChatResponse response = chatModel.call(new Prompt(summaryInput, options));
        String summary = response == null ? "" : ChatResponseContentExtractor.extract(response).text();
        if (summary == null || summary.isBlank()) {
            log.warn("context compaction: 摘要为空，放弃压缩（保持原文）");
            return 0;
        }
        int shadowed = 0;
        for (Unit unit : middle) {
            shadowed += unit.messages().size();
        }
        List<Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < headCount; i++) {
            rebuilt.add(messages.get(i));
        }
        rebuilt.add(frameSummary(summary, shadowed));
        for (Unit unit : tail) {
            rebuilt.addAll(unit.messages());
        }
        // 净收益守卫（2026-10-12 实测暴露：首次摘要出现 4939 → 6327 的净增——摘要比被压区间还长）。
        // 无净收益 ⇒ **回滚原文**（宁可保持原样，也不接受"越压越大"）。
        int beforeTokens = estimateTokens(messages);
        List<Message> snapshot = new ArrayList<>(messages);
        messages.clear();
        messages.addAll(rebuilt);
        int afterTokens = estimateTokens(messages);
        if (afterTokens >= beforeTokens) {
            messages.clear();
            messages.addAll(snapshot);
            log.info("context compaction: 摘要无净收益（{} → {} tokens），已回滚原文", beforeTokens, afterTokens);
            return 0;
        }
        return shadowed;
    }

    /** 头部固定保留：连续的 system 消息 + 紧随的首条 user 消息（任务上下文，绝不被压缩）。 */
    private static int fixedHeadCount(List<Message> messages) {
        int i = 0;
        while (i < messages.size() && messages.get(i) instanceof SystemMessage) {
            i++;
        }
        if (i < messages.size() && messages.get(i) instanceof UserMessage) {
            i++;
        }
        return i;
    }

    /** 把 headCount 之后的消息切成单元：assistant(带 tool_calls) + 紧随的 tool_result 合为一个单元。 */
    private static List<Unit> toUnits(List<Message> messages, int headCount) {
        List<Unit> units = new ArrayList<>();
        int i = headCount;
        while (i < messages.size()) {
            Message current = messages.get(i);
            if (current instanceof AssistantMessage assistant && assistant.hasToolCalls()
                    && i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                units.add(new Unit(List.of(current, messages.get(i + 1))));
                i += 2;
            } else {
                units.add(new Unit(List.of(current)));
                i++;
            }
        }
        return units;
    }

    private int unitTokens(Unit unit) {
        return estimateTokens(unit.messages());
    }

    /** 消息列表 token 估算（DSH 用 `ctx.tokenMeter`；我们以 jtokkit 估算，spring-ai 自带依赖）。 */
    int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateMessageTokens(message);
        }
        return total;
    }

    private int estimateMessageTokens(Message message) {
        if (message instanceof ToolResponseMessage toolMessage) {
            int sum = 0;
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                sum += tokenEstimator.estimate(response.responseData() == null ? "" : response.responseData());
            }
            return sum;
        }
        String text = message.getText();
        return text == null ? 0 : tokenEstimator.estimate(text);
    }

    /** 摘要落地形态：标签包裹的用户消息（对齐 DSH `frameSummary` 的 tag 包裹；原文可按需用工具重读）。 */
    private static Message frameSummary(String summary, int shadowedMessages) {
        return new UserMessage("<compacted-history messages=\"" + shadowedMessages + "\">\n"
                + "以下为被压缩的早期执行历史（**取代原文**；如需细节请用工具重新读取对应产出）：\n\n"
                + summary
                + "\n</compacted-history>");
    }

    /** 压缩单元：单个消息，或「assistant(tool_calls) + tool_result」这一对（不可拆）。 */
    private record Unit(List<Message> messages) {
    }
}
