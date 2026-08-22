package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.planner.service.RequirementClarifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图词二次确认卡协议：structured 卡片的构造与点选/文本通道解析。
 *
 * <p>从 {@link com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl} 拆分
 * （CODE_STYLE §7.8 类规模红线）：确认卡协议（题面文案、选项、payload 序列化、
 * selections 快照判定、payload JSON 解析）独立成组件后，意图状态机主类只保留
 * 状态流转决策，协议细节可独立单测。</p>
 *
 * <p>协议类型（{@link RequirementClarifyService.ClarifySelection}）为
 * {@link RequirementClarifyService} 接口嵌套类，本类非实现类，一律用限定名引用。</p>
 */
@Slf4j
@Component
public class ConfirmCardProtocol {

    /** 意图确认卡（structured 形态的二次确认弹窗）：问题 id 与题面文案。 */
    public static final String CONFIRM_QUESTION_ID = "confirm-switch";
    public static final String CONFIRM_QUESTION_TEXT = "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？";

    /** 意图确认卡选项：仅确认/取消两项，均不带推荐标记。 */
    public static final String CONFIRM_OPTION_ACCEPT = "确认";
    public static final String CONFIRM_OPTION_CANCEL = "取消";

    private final ObjectMapper objectMapper;

    /**
     * 显式构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑，与主类口径一致）。
     */
    public ConfirmCardProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 意图词二次确认的 structured payload：1 题 2 选项（确认/取消），
     * 均不带 recommended → 前端不渲染"推荐"按钮；allowCustom=false → 隐藏自定义补充输入框。
     *
     * <p>为什么用 structured 卡片替代纯文本确认：用户点选后经 selections 快照通道判定
     * （点「确认」走切换分支；点「取消」走继续对话分支，见 {@link #isAcceptSelected}），
     * 手写确认词仍兼容 {@code CONFIRM_PHRASE_PATTERN}（IntentDetectionService），
     * 后端状态机零改动，交互形态与方案细则确认卡片一致。</p>
     *
     * @return payload JSON；序列化失败降级 null（回退纯文本确认，不阻断主流程）
     */
    public String buildAskPayload() {
        RequirementClarifyService.ClarifyOption accept = new RequirementClarifyService.ClarifyOption();
        accept.setLabel(CONFIRM_OPTION_ACCEPT);
        accept.setValue(CONFIRM_OPTION_ACCEPT);
        RequirementClarifyService.ClarifyOption cancel = new RequirementClarifyService.ClarifyOption();
        cancel.setLabel(CONFIRM_OPTION_CANCEL);
        cancel.setValue(CONFIRM_OPTION_CANCEL);

        RequirementClarifyService.ClarifyQuestion question = new RequirementClarifyService.ClarifyQuestion();
        question.setId(CONFIRM_QUESTION_ID);
        question.setText(CONFIRM_QUESTION_TEXT);
        question.setMultiple(false);
        question.setAllowCustom(false);
        question.setOptions(List.of(accept, cancel));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", ClarifyReplyParser.MODE_STRUCTURED);
        payload.put("questions", List.of(question));
        try {
            // 跳过 null 字段（weight/recommended 不序列化）：保证卡片确实不带推荐标记
            return objectMapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("意图确认卡 payload 序列化失败，降级纯文本确认", e);
            return null;
        }
    }

    /**
     * 确认卡点选判定（selections 快照）：包含 confirm-switch 题且选中「确认」视为确认。
     * 卡片提交文本形如「问题：确认」不命中确认词开头锚定，故点选确认须走快照通道；
     * 点「取消」返回 false → 走清标记继续对话分支。
     */
    public boolean isAcceptSelected(List<RequirementClarifyService.ClarifySelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return false;
        }
        for (RequirementClarifyService.ClarifySelection selection : selections) {
            if (CONFIRM_QUESTION_ID.equals(selection.getQuestionId())
                    && selection.getValues() != null
                    && selection.getValues().contains(CONFIRM_OPTION_ACCEPT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 user payload（{@code {"selections":[...]}}）解析确认卡选择：
     * 返回选中值（确认/取消）；无确认卡选择/解析失败返回 null（回退文本判定）。
     */
    public String acceptValueOf(String userPayload) {
        if (userPayload == null || userPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(userPayload);
            JsonNode selections = root.get("selections");
            if (selections == null || !selections.isArray()) {
                return null;
            }
            for (JsonNode selection : selections) {
                if (!CONFIRM_QUESTION_ID.equals(selection.path("questionId").asText(null))) {
                    continue;
                }
                JsonNode values = selection.get("values");
                if (values != null && values.isArray() && !values.isEmpty()) {
                    return values.get(0).asText(null);
                }
            }
        } catch (Exception e) {
            log.warn("确认卡选择解析失败，回退文本判定: {}", e.getMessage());
        }
        return null;
    }

    /** 是否为「确认」选中值（null 安全，供文本/快照双通道统一判定）。 */
    public boolean isAcceptValue(String value) {
        return CONFIRM_OPTION_ACCEPT.equals(value);
    }

    /** 消息是否以确认卡题面文案开头（无检索语义判定用，null 安全）。 */
    public boolean isQuestionPrefix(String text) {
        return text != null && text.startsWith(CONFIRM_QUESTION_TEXT);
    }
}
