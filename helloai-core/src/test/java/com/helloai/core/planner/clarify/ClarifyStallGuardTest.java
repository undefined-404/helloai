package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.planner.entity.RequirementMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClarifyStallGuard 纯函数单测（TD-8 用户信息量判据）。
 *
 * <p>覆盖判据本身（不依赖编排层）：selections payload 有/无信息、纯文本敷衍词、
 * 首轮保护、连续无信息计数与阈值判定。</p>
 */
@DisplayName("ClarifyStallGuard（TD-8 用户信息量判据）")
class ClarifyStallGuardTest {

    private ClarifyStallGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ClarifyStallGuard(new ObjectMapper());
    }

    private RequirementMessage userMsg(String content, int seq) {
        RequirementMessage msg = new RequirementMessage();
        msg.setRole("user");
        msg.setContent(content);
        msg.setSeq(seq);
        return msg;
    }

    private RequirementMessage userMsgWithPayload(String content, int seq, String payload) {
        RequirementMessage msg = userMsg(content, seq);
        msg.setPayload(payload);
        return msg;
    }

    private RequirementMessage assistantMsg(String content, int seq) {
        RequirementMessage msg = new RequirementMessage();
        msg.setRole("assistant");
        msg.setContent(content);
        msg.setSeq(seq);
        return msg;
    }

    // ═══════════════════════════════════════════════════════════
    //  hasInformativeAnswer（单条消息判定）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("首轮保护：seq<=1 的需求描述恒视为有信息（不计入 stall）")
    void firstRoundNeverCountsAsStall() {
        // 即使内容是空/敷衍，首轮也不计
        assertThat(guard.hasInformativeAnswer(userMsg(null, 1))).isTrue();
        assertThat(guard.hasInformativeAnswer(userMsg("", 1))).isTrue();
        assertThat(guard.hasInformativeAnswer(userMsg("好的", 1))).isTrue();
    }

    @Test
    @DisplayName("纯文本：非空且非敷衍词 = 有信息")
    void plainTextWithSubstanceIsInformative() {
        assertThat(guard.hasInformativeAnswer(userMsg("本期先做核心报表", 3))).isTrue();
    }

    @Test
    @DisplayName("纯文本敷衍词（继续/好的/随便/嗯/ok 等）= 无信息")
    void vagueRepliesAreUninformative() {
        for (String vague : List.of("继续", "好的", "好", "嗯", "嗯嗯", "哦", "知道了", "随便",
                "都行", "你定", "可以", "行", "ok", "OK", "好的吧", "就这样", "先这样", "没有", "无")) {
            assertThat(guard.hasInformativeAnswer(userMsg(vague, 3)))
                    .as("敷衍词[%s]应判无信息", vague)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("selections payload：values 有内容 = 有信息（即使文本是敷衍词）")
    void selectionsWithValuesAreInformative() {
        String payload = "{\"selections\":[{\"values\":[\"核心报表\"]}]}";
        assertThat(guard.hasInformativeAnswer(userMsgWithPayload("随便", 3, payload))).isTrue();
    }

    @Test
    @DisplayName("selections payload：values 空且 customText 空白 = 无信息")
    void emptySelectionsAreUninformative() {
        String payload = "{\"selections\":[{\"values\":[],\"customText\":\"\"}]}";
        assertThat(guard.hasInformativeAnswer(userMsgWithPayload("随便", 3, payload))).isFalse();
    }

    @Test
    @DisplayName("selections payload：customText 有内容 = 有信息（values 空也行）")
    void customTextCarriesInfo() {
        String payload = "{\"selections\":[{\"values\":[],\"customText\":\"数据源用 MySQL\"}]}";
        assertThat(guard.hasInformativeAnswer(userMsgWithPayload("", 3, payload))).isTrue();
    }

    @Test
    @DisplayName("payload 非法 JSON：降级纯文本判定（content 有内容即信息）")
    void malformedPayloadFallsBackToPlainText() {
        String payload = "not-json";
        assertThat(guard.hasInformativeAnswer(userMsgWithPayload("要支持导出", 3, payload))).isTrue();
        assertThat(guard.hasInformativeAnswer(userMsgWithPayload("随便", 3, payload))).isFalse();
    }

    @Test
    @DisplayName("空内容（null/blank）= 无信息")
    void blankContentIsUninformative() {
        assertThat(guard.hasInformativeAnswer(userMsg(null, 3))).isFalse();
        assertThat(guard.hasInformativeAnswer(userMsg("  ", 3))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    //  consecutiveUninformativeRounds / shouldConverge（连续计数）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("连续两轮无信息 → shouldConverge=true（阈值 2），从最新往前数")
    void convergesAfterTwoConsecutiveUninformative() {
        List<RequirementMessage> messages = List.of(
                userMsg("做一个报表", 1),        // 首轮保护
                assistantMsg("追问：范围多大？", 2),
                userMsg("好的", 3),             // 无信息
                assistantMsg("追问：验收标准？", 4),
                userMsg("随便", 5));            // 无信息 → 连续 2
        assertThat(guard.consecutiveUninformativeRounds(messages)).isEqualTo(2);
        assertThat(guard.shouldConverge(messages, 2)).isTrue();
    }

    @Test
    @DisplayName("出现有信息回答即打断计数（只数连续尾部）")
    void informativeAnswerBreaksConsecutiveCount() {
        List<RequirementMessage> messages = List.of(
                userMsg("做一个报表", 1),
                assistantMsg("追问：范围多大？", 2),
                userMsg("好的", 3),                    // 无信息
                assistantMsg("追问：验收标准？", 4),
                userMsg("用 MySQL 数据源", 5));        // 有信息 → 打断
        assertThat(guard.consecutiveUninformativeRounds(messages)).isZero();
        assertThat(guard.shouldConverge(messages, 2)).isFalse();
    }

    @Test
    @DisplayName("历史里无信息但中间隔了有信息回答 → 不计数（非连续不计）")
    void nonConsecutiveVagueRepliesDoNotCount() {
        List<RequirementMessage> messages = List.of(
                userMsg("做一个报表", 1),
                assistantMsg("追问：范围多大？", 2),
                userMsg("好的", 3),                    // 无信息
                assistantMsg("追问：验收标准？", 4),
                userMsg("数据源用 MySQL", 5),          // 有信息
                assistantMsg("追问：周期？", 6),
                userMsg("每周", 7));                   // 有信息 → 连续计数 0
        assertThat(guard.consecutiveUninformativeRounds(messages)).isZero();
        assertThat(guard.shouldConverge(messages, 2)).isFalse();
    }

    @Test
    @DisplayName("只数用户消息：assistant 消息不参与计数")
    void assistantMessagesAreIgnoredInCount() {
        // 尾部是 assistant 消息（新一轮尚未发出）：从最新 user 往前数
        List<RequirementMessage> messages = List.of(
                userMsg("做一个报表", 1),
                assistantMsg("追问：范围多大？", 2),
                userMsg("好的", 3),
                assistantMsg("追问：验收标准？", 4));
        // 最近 user 消息 seq=3 无信息 → 连续 1（<2 不收敛）
        assertThat(guard.consecutiveUninformativeRounds(messages)).isEqualTo(1);
        assertThat(guard.shouldConverge(messages, 2)).isFalse();
    }

    @Test
    @DisplayName("空消息列表 / 阈值非法：不收敛（fail-safe 放行）")
    void emptyListOrInvalidThresholdDoesNotConverge() {
        assertThat(guard.shouldConverge(List.of(), 2)).isFalse();
        assertThat(guard.shouldConverge(null, 2)).isFalse();
        // 阈值 <= 0 视为未配置 → 永不收敛
        List<RequirementMessage> messages = List.of(
                userMsg("做一个报表", 1),
                userMsg("好的", 3),
                userMsg("随便", 5));
        assertThat(guard.shouldConverge(messages, 0)).isFalse();
    }
}
