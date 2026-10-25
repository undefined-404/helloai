package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.planner.entity.RequirementMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 澄清侧 stall 守卫（`TD-8`，判据经用户拍板 = 「用户信息量判据」）。
 *
 * <p><b>解决的问题</b>：澄清会话可能因用户持续给「无新信息」的回答（空选择 / 敷衍词）
 * 而无限追问（原设计对轮次只有 {@code MAX_ROUNDS=20} 的硬上限，且纯靠 LLM 自评
 * {@code progress} 引导——计划明确 {@code progress} 不参与门控）。本守卫用<b>客观量</b>
 * 判定 stall：连续 N 轮用户回答不携带新信息 ⇒ 收敛出终稿（复用 finalize 提示词），
 * 不再追问。</p>
 *
 * <p><b>判据（客观量，非模型自评）</b>：</p>
 * <ul>
 *   <li><b>selections payload</b>（结构化回答）：所有 {@code ClarifySelection} 的
 *       {@code values} 与 {@code customText} 都为空 / 空白 ⇒ 无新信息（用户没选任何选项、
 *       也没补充自定义文本）；任一有内容 ⇒ 有信息。</li>
 *   <li><b>纯文本回答</b>：{@code content} 为空 / 空白 / 纯敷衍词（继续 / 好的 / 随便 /
 *       嗯 / ok 等）⇒ 无信息。</li>
 * </ul>
 *
 * <p><b>首轮保护</b>：会话首条用户消息（建会话时的需求描述，{@code seq=1}）不算——
 * 它是需求陈述，永远视为有信息，不计入 stall。</p>
 *
 * <p><b>收敛判定</b>：从最新用户消息回看连续无信息轮数 ≥ 阈值（首期 2，可配置）
 * ⇒ 需要收敛。计数中断（出现有信息回答）即清零。</p>
 *
 * <p>纯函数无状态：不触发任何状态变更，只做「用户消息 → 是否携带新信息」的客观判定
 * 与连续计数；收敛动作由 {@code ClarifyRoundOrchestratorServiceImpl} 负责。</p>
 */
@Slf4j
@Component
public class ClarifyStallGuard {

    /** 用户消息角色标识（与 {@code ClarifyCommandSearchServiceImpl.ROLE_USER} 同值，package 隔离故本地声明）。 */
    private static final String ROLE_USER = "user";

    /** 纯敷衍回答特征（用户没给任何实质信息）。首轮需求描述不计，此处仅用于追问轮后的回答。 */
    private static final java.util.regex.Pattern VAGUE_REPLY = java.util.regex.Pattern.compile(
            "^(继续|好的|好|嗯|嗯嗯|哦|知道了|随便|都行|你定|可以|行|ok|OK|好的吧|就这样|先这样|没有|无)$");

    private final ObjectMapper objectMapper;

    public ClarifyStallGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 判定单条用户消息是否携带新信息（TD-8 客观判据）。
     *
     * @param message 用户消息（须为 ROLE_USER；非用户消息视为有信息——防御式放行，
     *                计数只在调用方对用户消息逐条执行时才有意义）
     */
    public boolean hasInformativeAnswer(RequirementMessage message) {
        if (message == null) {
            return false;
        }
        // 首轮保护：需求描述不计入 stall（seq=1 是建会话的首条用户消息）
        Integer seq = message.getSeq();
        if (seq != null && seq <= 1) {
            return true;
        }
        // selections payload：结构化回答
        String payload = message.getPayload();
        if (payload != null && !payload.isBlank()) {
            Boolean selectionsInformative = selectionsCarryInfo(payload);
            if (selectionsInformative != null) {
                return selectionsInformative;
            }
        }
        // 纯文本：非空且非敷衍词
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return false;
        }
        return !VAGUE_REPLY.matcher(content.trim()).matches();
    }

    /**
     * 回溯最近用户消息，数「连续无新信息」轮数。
     *
     * @param messages 按 seq 升序的会话消息全量（含 assistant/user）
     * @return 连续无信息用户消息数（0 = 最近一轮用户回答有信息 / 无用户消息）
     */
    public int consecutiveUninformativeRounds(List<RequirementMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int count = 0;
        // 从最新消息往前（seq 降序），只数用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            RequirementMessage msg = messages.get(i);
            if (msg == null || !ROLE_USER.equals(msg.getRole())) {
                continue;
            }
            if (hasInformativeAnswer(msg)) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 是否达到收敛阈值（连续无信息轮数 ≥ 阈值）。
     *
     * @param messages      会话消息全量（按 seq 升序）
     * @param thresholdRounds 收敛阈值（连续无信息轮数达到即收敛，首期 2）
     */
    public boolean shouldConverge(List<RequirementMessage> messages, int thresholdRounds) {
        if (thresholdRounds <= 0) {
            return false;
        }
        return consecutiveUninformativeRounds(messages) >= thresholdRounds;
    }

    /**
     * selections payload 是否携带信息；payload 不是 selections 形态返回 null（交给纯文本判定）。
     */
    private Boolean selectionsCarryInfo(String payload) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(payload,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object selections = parsed.get("selections");
            if (!(selections instanceof List<?> list)) {
                return null;
            }
            if (list.isEmpty()) {
                return false;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> sel)) {
                    continue;
                }
                // values 有内容 ⇒ 有信息
                Object values = sel.get("values");
                if (values instanceof List<?> valueList && !valueList.isEmpty()) {
                    return true;
                }
                // customText 有内容 ⇒ 有信息
                Object customText = sel.get("customText");
                if (customText instanceof String text && !text.isBlank()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // payload 非法 JSON：交给纯文本判定（content 仍可能带信息）
            log.warn("澄清用户 selections payload 解析失败，降级纯文本判定: err={}", e.getMessage());
            return null;
        }
    }
}
