package com.helloai.core.planner.clarify;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 意图识别组件：CHAT → CLARIFY 意图词、意图确认词、无检索语义判定。
 *
 * <p>从 {@link com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl} 拆分
 * （CODE_STYLE §7.8 类规模红线）：意图正则族与搜索查询词语义守卫独立成组件后，
 * 意图状态机主类只保留状态流转决策，判定细节可独立单测。</p>
 *
 * <p>确认卡题面/选项判定委托 {@link ConfirmCardProtocol}（卡片协议与意图判定各自独立演进）。</p>
 */
@Component
public class IntentDetectionService {

    /**
     * CHAT → CLARIFY 意图词：用户表达"把讨论整理成可落地方案"的常见说法，正则命中即进入二次确认。
     * 追加口语化话术（整理方案/出个方案/写方案/做个方案等），覆盖"帮我整理方案吧"这类表达；
     * 追加"动作词 + 可选量词 + 计划/任务/方案"组合模式（新建个计划/给一个方案/帮我总结等），
     * 组合匹配避免"任务""计划"裸词子串误触；误触有二次确认弹窗把关（点取消即继续自由对话），
     * 故放宽匹配不设额外代价。
     */
    private static final Pattern INTENT_TO_CLARIFY_PATTERN = Pattern.compile(
            "整理成方案|做成方案|生成方案|转为方案|变成方案|整理成任务|做成任务|落地实施|"
                    + "出一份方案|写个方案|方案化|整理方案|出个方案|出方案|写方案|做个方案|做方案|方案整理|"
                    + "(新建|创建|建立|建)(一?个|一?份)?(计划|任务|方案)|"
                    + "(帮我|给我|给)(一?个|一?份)?(计划|方案)|"
                    + "生成(一?个|一?份)?(计划|任务|方案)|"
                    + "做(一?个|一?份)(计划|任务)|"
                    + "来(一?个|一?份)(计划|任务|方案)|"
                    + "出(一?个|一?份)(计划|任务)|"
                    + "帮我总结|"
                    + "总结(成|为|个|一下)(方案|计划|任务)");

    /**
     * 意图词二次确认的确认词：仅会话处于待确认状态时生效；
     * 开头命中且后随标点/空白/结尾，避免"好的，但我还想先聊聊"这类误判。
     */
    private static final Pattern CONFIRM_PHRASE_PATTERN = Pattern.compile(
            "^(确认|确定|好的|可以|开始吧|开始|是的|没错|没问题|行|嗯|OK|ok|Yes|yes)([。！？!?,.;；\\s]|$)");

    /**
     * 搜索查询词语义守卫：纯意图话术（不含主题）的长度上限。
     * 长度 ≤ 该值且命中意图词的消息视为无检索主题（如「帮我生成计划」），
     * 长句携带主题内容（如「我想 60 天备考架构师考试，帮我整理成方案」）仍可作查询词。
     */
    private static final int INTENT_ONLY_QUERY_LIMIT = 20;

    private final ConfirmCardProtocol confirmCardProtocol;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与主类口径一致）。
     */
    public IntentDetectionService(ConfirmCardProtocol confirmCardProtocol) {
        this.confirmCardProtocol = confirmCardProtocol;
    }

    /** 是否命中 CHAT → CLARIFY 意图词（"整理成方案"等常见说法，正则子串命中即切换）。 */
    public boolean isIntentToClarify(String message) {
        return message != null && INTENT_TO_CLARIFY_PATTERN.matcher(message).find();
    }

    /** 确认词判定：开头命中确认词且后随标点/空白/结尾（待确认状态专用，普通对话不受影响）。 */
    public boolean isConfirmPhrase(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return CONFIRM_PHRASE_PATTERN.matcher(message.trim()).find();
    }

    /**
     * 无检索语义判定：确认词 / 确认卡提交文本（题面前缀或卡选快照）/ 纯意图短句。
     * userPayload 仅当前轮可传（历史消息回退扫描传 null）。
     */
    public boolean lacksSearchSemantics(String message, String userPayload) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String trimmed = message.trim();
        if (isConfirmPhrase(trimmed)) {
            return true;
        }
        if (confirmCardProtocol.isQuestionPrefix(trimmed)) {
            return true;
        }
        if (userPayload != null && confirmCardProtocol.isAcceptValue(confirmCardProtocol.acceptValueOf(userPayload))) {
            return true;
        }
        return isIntentToClarify(trimmed) && trimmed.length() <= INTENT_ONLY_QUERY_LIMIT;
    }
}
