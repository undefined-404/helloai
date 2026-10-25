package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.runtime.cancel.LoopCancellation;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.shared.util.VerificationFence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * REF-8.4 ② 收尾轮（内部 LLM 自我审计）：{@link FinalReportFenceHook} 经 {@link ChatModelToolLoop}
 * 终止分发的有界无工具重跑。
 *
 * <p>语义：内部 LLM 提交前先自审（VERIFICATION/STATUS/NEXT_STEPS）；缺/不合格 ⇒ 恰好一次收尾轮
 * 自纠；外部 Agent 不参与；二次仍不合格不循环（交出口分类 fail-close）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REF-8.4 ② 收尾轮 FinalReportFenceHook")
class FinalReportFenceHookTest {

    @Mock
    private ToolExecutor toolExecutor;

    private final AgentLoop loop =
            new ChatModelToolLoop(ContextCompactor.noop(), List.of(new FinalReportFenceHook()));

    @Test
    @DisplayName("内部 LLM 缺围栏 ⇒ 恰好一次无工具收尾轮，结果含围栏，iterations+1")
    void internalMissingFence_runsOnceFinalizeAndSucceeds() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("已完成步骤，但没写围栏"))
                .enqueue(text(withFence("已完成步骤")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);     // 草稿 1 + 收尾 1
        assertThat(model.callCount()).isEqualTo(2);       // 有界恒一次
        verifyNoInteractions(toolExecutor);               // 收尾轮不重入工具
    }

    @Test
    @DisplayName("内部 LLM 围栏已合格 ⇒ 零额外调用（自审已完成）")
    void internalAdequateFence_zeroExtraCall() {
        StubChatModel model = new StubChatModel().enqueue(text(withFence("done")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);        // 无收尾轮
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("非内部（外部 Agent）缺围栏 ⇒ 不触发收尾轮（其自审走自身工作空间 + reviewer）")
    void externalMissingFence_notGated() {
        StubChatModel model = new StubChatModel().enqueue(text("外部 agent 的自由报告，无围栏"));

        AgentLoopResult result = loop.run(input(model, null));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);        // 无收尾轮
        assertThat(result.text()).isEqualTo("外部 agent 的自由报告，无围栏");
    }

    @Test
    @DisplayName("收尾轮重跑抛异常 ⇒ 沿用草稿（交出口分类 fail-close，不阻断）")
    void finalizeThrows_keepsDraft() {
        StubChatModel model = new StubChatModel();
        model.enqueue(text("无围栏草稿"));
        model.throwOnCall(2, new RuntimeException("finalize boom"));   // 第 1 次草稿，第 2 次收尾抛

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("无围栏草稿");       // 沿用草稿
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("收尾轮后仍缺围栏 ⇒ 返回修订结果但不循环（有界恒一次）")
    void stillMissingAfterFinalize_doesNotLoop() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("第一次没围栏"))
                .enqueue(text("第二次还是没围栏"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(2);        // 有界：草稿 + 恰一次收尾，不第三次
        assertThat(result.iterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("② 收尾轮空产出 ⇒ 沿用草稿（防止交付物被清空）")
    void finalizeEmptyOutput_keepsDraft() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("真实交付正文，无围栏"))
                .enqueue(text(""));   // 收尾轮返回空正文（推理模型只回 reasoning，无 getResults）

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("真实交付正文，无围栏");   // 沿用草稿，未被清空
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 收尾轮只回围栏、丢弃草稿正文 ⇒ 回退「草稿 + 围栏段」，交付物不被顶替（2026-10-16 实测事故）")
    void finalizeDropsDraftBody_keepsDraftAndFence() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("# 手册 - 最终整合报告\n\n## 执行摘要\n主线论点若干…"))       // 草稿：真报告，无围栏
                .enqueue(text("VERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无"));  // 收尾轮只回围栏

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("## 执行摘要");                          // 交付物正文保住
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();    // 围栏同时具备
        assertThat(result.iterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 近空白草稿 + 收尾轮完整产出 ⇒ 采信收尾轮（2026-10-25 死信事故回归：3 子任务同构复现）")
    void nearBlankDraft_finalizeProducesFullDeliverable_adopted() {
        // 事故现场：首轮执行者 MAX_ITERATIONS 硬终止，草稿只有一句开场白（65 字符）；
        // 收尾轮产出 5120 字符完整报告（含全部验收证据、STATUS: DONE），此前被「未保留草稿」回退丢弃
        // → 近空白空壳 + BLOCKED 围栏 → 审查判空 → 返工 3 次 → 死信。
        StubChatModel model = new StubChatModel()
                .enqueue(text("I'll start by checking the task details and the available skills."))   // 近空白草稿
                .enqueue(text("## 5. 企业级 RAG 知识库方案\n\n### 5.1 知识接入\n..."
                        + "\n\nVERIFICATION:\n- 证据1：RAG 独立标题为 5.1~5.8\n- 证据2：MCP 独立条目为 6.1~6.9\n"
                        + "STATUS: DONE\nNEXT_STEPS:\n- 无"));                                         // 收尾轮完整产出

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        // 采信收尾轮完整交付物（不再回退成近空白开场白 + BLOCKED 围栏）
        assertThat(result.text()).contains("5.1 知识接入");
        assertThat(result.text()).doesNotContain("I'll start by checking");
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 近空白草稿 + 收尾轮产出但围栏不合格 ⇒ 仍回退「草稿 + 围栏段」（防采信垃圾）")
    void nearBlankDraft_finalizeFenceInadequate_stillFallback() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("开场白而已"))
                .enqueue(text("VERIFICATION:\n- 无 STATUS 的残缺围栏"));   // 围栏不合格（无 STATUS）

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        // 采信条件不满足（围栏不 adequate）⇒ 走原回退：近空白草稿 + 围栏段
        assertThat(result.text()).contains("开场白而已");
        assertThat(result.text()).contains("VERIFICATION:");
        assertThat(VerificationFence.parse(result.text()).adequate()).isFalse();
    }

    @Test
    @DisplayName("★ 近空白草稿 + 收尾轮产出且围栏合格 ⇒ 采信（不重复拼开场白）")
    void nearBlankDraft_finalizeAdequate_adoptedAsIs() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("准备开始撰写方案"))
                .enqueue(text("完整报告正文……\n\nVERIFICATION:\n- 已核实\nSTATUS: DONE\nNEXT_STEPS: 无"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("完整报告正文……\n\nVERIFICATION:\n- 已核实\nSTATUS: DONE\nNEXT_STEPS: 无");
        assertThat(result.text()).doesNotContain("准备开始撰写方案");
    }

    @Test
    @DisplayName("★ 短但含 Markdown 标题的草稿被收尾顶替 ⇒ 仍回退（2026-10-16 反例：防误判近空白）")
    void shortButStructuredDraft_droppedByFinalize_stillFallback() {
        // 反例：草稿剔除空白后 < 200 字符，但含 "#" 标题骨架（真实交付物），
        // 不能被近空白例外误判采信——否则完整草稿被收尾轮顶替（2026-10-16 事故回归）。
        StubChatModel model = new StubChatModel()
                .enqueue(text("# 手册 - 最终整合报告\n\n## 执行摘要\n主线论点若干…"))       // 短但有标题骨架
                .enqueue(text("VERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无"));  // 收尾轮只回围栏

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("## 执行摘要");                          // 交付物正文保住（回退）
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();    // 围栏同时具备
    }

    @Test
    @DisplayName("收尾轮原样保留草稿正文（仅换行差异）⇒ 直接采用收尾产物，不重复追加")
    void finalizePreservesDraftBody_adoptedAsIs() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿：正文A"))
                .enqueue(text(withFence("\n无围栏草稿：正文A\n")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.text()).isEqualTo(withFence("\n无围栏草稿：正文A\n"));    // 未被二次拼接
    }

    @Test
    @DisplayName("③ 收尾轮 prompt 含系统提示 + 原任务要求（哨兵守卫，堵「断言命中常量」假绿）")
    void finalizePromptIncludesTaskRequirement() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))
                .enqueue(text(withFence("done")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("VERIFICATION:");
        String joined = model.lastPrompt().stream()
                .map(Message::getText).filter(Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        // 哨兵只可能来自被测来源（system / userPrompt），杜绝「断言命中指令常量自身」的同类假绿
        assertThat(joined).contains(SENTINEL_SYSTEM);   // 兑现「系统提示」在收尾 prompt
        assertThat(joined).contains(SENTINEL_TASK);     // 只可能来自 taskText，真守卫
    }

    @Test
    @DisplayName("③a 收尾轮：收尾那一轮的 token 计入累计")
    void finalizeAccumulatesTokens() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))                       // 收尾前无 token
                .enqueue(textWithUsage(withFence("done"), 15));   // 收尾轮 15 token

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
        assertThat(result.tokenUsage()).isEqualTo(15);            // 收尾轮 token 已计入
    }

    @Test
    @DisplayName("③c 收尾轮：iterations 与 maxIterations 取 min，不超配置上限")
    void finalizeIterationsCappedByMaxIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))                       // 草稿（iterations=1）
                .enqueue(text(withFence("done")));                 // 收尾轮

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 1));

        assertThat(result.success()).isTrue();
        // maxIterations=1 ⇒ 收尾轮计 iterations 被截断到 1，而不是 2（2 = 草稿 1 + 收尾 1）
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 业务面 scene=planner_decompose 缺围栏 ⇒ 不触发收尾轮（JSON 结构化产出，TD-9 事故回归）")
    void businessScene_plannerDecompose_skipsFinalizeRound() {
        // 事故现场：拆解 LLM 正常输出 JSON 数组（无 VERIFICATION 围栏），此前被收尾轮改写成
        // "VERIFICATION:..." 前缀文本 → parseDraftItems JSON 解析失败 → 拆解回退 PENDING。
        StubChatModel model = new StubChatModel().enqueue(text(
                "[{\"title\":\"子任务A\",\"content\":\"内容\",\"deliverable\":\"交付物\","
                        + "\"acceptance\":\"验收标准\",\"acceptanceKind\":\"AUTOMATED\","
                        + "\"priority\":\"HIGH\",\"dependsOn\":[],\"contract\":false,"
                        + "\"requiredSkills\":[],\"constraints\":\"\",\"uncertainties\":[]}]"));

        AgentLoopResult result = loop.run(inputWithScene(model, AgentAccessType.API_KEY_LLM, "planner_decompose"));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);                  // 零额外调用，不收尾轮
        assertThat(result.text()).startsWith("[{\"title\"");        // JSON 数组原样保留
        verifyNoInteractions(toolExecutor);
    }

    @Test
    @DisplayName("★ 业务面 scene=subtask_review 缺围栏 ⇒ 不触发收尾轮（审查判定 JSON，同源保护）")
    void businessScene_subtaskReview_skipsFinalizeRound() {
        StubChatModel model = new StubChatModel().enqueue(text(
                "{\"pass\":true,\"score\":90,\"analysis\":\"逐条核验通过\"}"));

        AgentLoopResult result = loop.run(inputWithScene(model, AgentAccessType.API_KEY_LLM, "subtask_review"));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(result.text()).startsWith("{\"pass\":true");     // JSON 判定原样保留
    }

    @Test
    @DisplayName("★ 执行链 scene=null 缺围栏 ⇒ 仍触发收尾轮（既有契约不回归）")
    void executionChain_nullScene_stillRunsFinalize() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("已完成步骤，但没写围栏"))
                .enqueue(text(withFence("已完成步骤")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(2);                 // 草稿 + 收尾（执行链契约保持）
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
    }

    private static String withFence(String body) {
        return body + "\nVERIFICATION:\n- 已验证\nSTATUS: DONE\nNEXT_STEPS: 无";
    }

    // ==============================================================
    // 2026-10-25 MiniMax 轮数耗尽事故：耗尽 + 有调研 + 草稿近空白 ⇒ 追加一次收尾成文
    // ==============================================================

    @Test
    @DisplayName("★ 轮数耗尽 + 有工具调用 + 草稿近空白 ⇒ 追加收尾轮把正文写出来（MiniMax 事故回归）")
    void exhaustedWithToolCallsAndBlankDraft_triggersFinalize() {
        StubChatModel model = new StubChatModel()
                .enqueue(toolCall("c1"))
                .enqueue(toolCall("c2"))
                .enqueue(toolCall("c3"))
                .enqueue(text(withFence("## C07 业务场景卡集\n\nSC-01 知识问答……")));
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("t", "{}"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 3));

        // 事故现场：3 轮全用于工具、正文一字未出 ⇒ MAX_ITERATIONS 失败、交付物为空
        // 修复口径：耗尽后仍有一次收尾成文（第 4 次调用），终态救回 STOP + 正文
        assertThat(model.callCount()).isEqualTo(4);
        assertThat(result.success()).isTrue();
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.text()).contains("业务场景卡集");
        assertThat(result.toolCallCount()).isEqualTo(3);   // 收尾轮不重入工具
    }

    @Test
    @DisplayName("轮数耗尽但草稿已有实质正文 ⇒ 不追加收尾轮（沿用硬终态口径，不再多烧一次调用）")
    void exhaustedWithRealDraft_noFinalize() {
        String body = "## C01 企业现状与挑战\n\n" + "现状描述与挑战分析。".repeat(40);
        StubChatModel model = new StubChatModel()
                .enqueue(toolCallWithText("c1", body))
                .enqueue(toolCallWithText("c2", body))
                .enqueue(toolCallWithText("c3", body));
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("t", "{}"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 3));

        assertThat(model.callCount()).isEqualTo(3);        // 不追加收尾
        assertThat(result.success()).isFalse();
        assertThat(result.finishReason()).isEqualTo("MAX_ITERATIONS");
    }

    @Test
    @DisplayName("兜底判据三态：无工具调用 / 非 MAX_ITERATIONS / 有正文 ⇒ 均不救")
    void isExhaustedDraftWorthFinalizing_predicate() {
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(null)).isFalse();
        // 无工具调用 ⇒ 纯空转，喂空草稿也写不出东西
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations("", 5, 0, null))).isFalse();
        // 非 MAX_ITERATIONS（自然 STOP）⇒ 走既有收尾轮分支，不由本判据决定
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.stop("正文", null, 5, 3, null))).isFalse();
        // 草稿已含标题骨架 ⇒ 有内容可丢，不救
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations("## 标题\n正文", 5, 3, null))).isFalse();
        // 三重条件全满足 ⇒ 救
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations("", 5, 3, null))).isTrue();
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations("I'll start by checking…", 5, 17, null))).isTrue();
    }

    @Test
    @DisplayName("★7443 事故回归锁：草稿是「逐轮进度叙述」而非正文 ⇒ 必须救（正文在沙箱，平台零收获）")
    void isExhaustedDraftWorthFinalizing_shouldRescueProgressNarrativeDraft() {
        // 实测形态：模型 20 轮都在沙箱里削字数，对话里只剩进度叙述（几百字符、无 `#` 骨架）
        String narrative = "已完成本轮压缩，当前正文去空白字数（不含 mermaid 代码块）为 3022 字。"
                + "分章统计：C01 企业现状与挑战 680 字、C02 目标及原则 801 字、C03 总体蓝图 1541 字，合计 3022 字。"
                + "验收上限为 2300 字，因此还需要继续删减约 722 字。下一轮计划优先处理 C03 的关键链路段落，"
                + "把七条链路由逐条展开改为合并表述，预计可压缩 400 字左右，然后再处理 C02 的原则部分。"
                + "另外 mermaid 代码块单独统计为 1025 字，按契约不应计入正文字数，但仍需确认评审口径。"
                + "沙箱内文件路径为 /workspace/part1.md，每轮修改后都用 python 脚本重新统计，确保没有引入结构问题。";
        assertThat(narrative.replaceAll("\\s", "").length()).isGreaterThan(200);   // 旧门槛会漏判
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations(narrative, 20, 21, null))).isTrue();
        // 反向锁：草稿已有标题骨架 ⇒ 仍按 2026-10-16 防护不救
        assertThat(FinalReportFenceHook.isExhaustedDraftWorthFinalizing(
                AgentLoopResult.maxIterations("# C01 现状\n" + narrative, 20, 21, null))).isFalse();
    }

    @Test
    @DisplayName("★STOP 假成功（#009）：STOP + 沙箱调用≥3 + 无交付正文 ⇒ 必须救；三条件缺一不救")
    void isSuspiciousStopWithoutDeliverable_predicate() {
        // 实测形态（review_record 第 1/2 轮驳回原文）：几行过程自述 + 围栏，正文零内容；
        // 注意围栏里的「- 核实说明」是列表符，**不剥围栏**就会被判成「有骨架 ⇒ 可交付」。
        String narrative = "Only saved 1 char. Let me make real deletions: remove the intro's second clause.\n\n"
                + "VERIFICATION:\n- 核实说明：草稿正文不含 OA 接入 AI 的实质说明\n"
                + "STATUS: BLOCKED\nNEXT_STEPS: 继续删减到 300~400 字";
        AgentLoopResult stopDraft = AgentLoopResult.stop(narrative, null, 20, 23, null);

        // ② 沙箱调用 0 次 ⇒ 不是「正文写在沙箱里」这条病理，不得误伤短交付类验收
        assertThat(FinalReportFenceHook.isSuspiciousStopWithoutDeliverable(stopDraft, List.of())).isFalse();
        assertThat(FinalReportFenceHook.sandboxToolCallCount(null)).isZero();
        assertThat(FinalReportFenceHook.sandboxToolCallCount(List.of())).isZero();
        // 三条件全满足 ⇒ 救
        assertThat(FinalReportFenceHook.sandboxToolCallCount(sandboxMessages(3))).isEqualTo(3);
        assertThat(FinalReportFenceHook.isSuspiciousStopWithoutDeliverable(stopDraft, sandboxMessages(3))).isTrue();
        // ③ 已交出正文（有骨架）⇒ 不救
        String realBody = "# OA 接入 AI 的落地顺序说明\n\n## 一、先 RAG\n" + "正文".repeat(200);
        assertThat(FinalReportFenceHook.isSuspiciousStopWithoutDeliverable(
                AgentLoopResult.stop(realBody, null, 20, 23, null), sandboxMessages(3))).isFalse();
        // ① 非 STOP（轮数耗尽）⇒ 由 isExhaustedDraftWorthFinalizing 负责，不在本判据
        assertThat(FinalReportFenceHook.isSuspiciousStopWithoutDeliverable(
                AgentLoopResult.maxIterations(narrative, 20, 23, null), sandboxMessages(3))).isFalse();
    }

    /** 造 n 条「沙箱工具已执行」的返回消息（{@code internal_run_in_sandbox}）。 */
    private static List<Message> sandboxMessages(int sandboxCalls) {
        List<ToolResponseMessage.ToolResponse> responses = new java.util.ArrayList<>();
        for (int i = 0; i < sandboxCalls; i++) {
            responses.add(new ToolResponseMessage.ToolResponse("c" + i, "internal_run_in_sandbox", "written 3008 chars"));
        }
        return List.of(ToolResponseMessage.builder().responses(responses).build());
    }

    @Test
    @DisplayName("★ 空草稿收尾：改用「从头写正文」指令并注入调研摘要（不是审计空草稿）")
    void blankDraftFinalize_usesWriteInstructionAndResearchDigest() {
        StubChatModel model = new StubChatModel()
                .enqueue(toolCall("c1"))
                .enqueue(toolCall("c2"))
                .enqueue(toolCall("c3"))
                .enqueue(text(withFence("# C07 业务场景卡集\n\nSC-01 知识问答……")));
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("t", "DIGEST-SENTINEL-XYZ"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 3));

        String finalizePrompt = model.lastPrompt().stream()
                .map(Message::getText).filter(Objects::nonNull)
                .reduce((a, b) -> b).orElse("");
        // 关键：不能再走「审计《报告草稿》」（空草稿下只会换来 BLOCKED 自述）
        assertThat(finalizePrompt).contains("当前为空");
        assertThat(finalizePrompt).doesNotContain("做一次自我审计");
        // 关键：调研结果摘要必须喂进去（空草稿的唯一素材来源）
        assertThat(finalizePrompt).contains("DIGEST-SENTINEL-XYZ");
        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("业务场景卡集");
    }

    @Test
    @DisplayName("★ 空草稿 + 收尾只回围栏 ⇒ 不采信为成功（防「假成功」）")
    void blankDraftFinalize_onlyFence_keepsFailure() {
        StubChatModel model = new StubChatModel()
                .enqueue(toolCall("c1"))
                .enqueue(toolCall("c2"))
                .enqueue(toolCall("c3"))
                .enqueue(text(withFence("")));          // 只回围栏、无正文（实测形态）
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("t", "{}"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 3));

        // 假成功（success=true 但零交付物）比失败更糟：评审必驳回且掩盖真因
        assertThat(result.success()).isFalse();
        assertThat(result.finishReason()).isEqualTo("MAX_ITERATIONS");
    }

    @Test
    @DisplayName("researchDigest：只取工具返回、单条截断、总量封顶")
    void researchDigest_extractsToolResponsesOnly() {
        String longData = "x".repeat(FinalReportFenceHook.RESEARCH_DIGEST_PER_TOOL_CHARS + 500);
        List<Message> messages = List.of(
                new AssistantMessage("模型自述不应进入摘要"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("id1", "internal_get_sub_task_detail",
                                "SUBTASK-DATA"),
                        new ToolResponseMessage.ToolResponse("id2", "internal_get_deps_summary",
                                longData))).build());

        String digest = FinalReportFenceHook.researchDigest(messages);

        assertThat(digest).contains("internal_get_sub_task_detail").contains("SUBTASK-DATA");
        assertThat(digest).doesNotContain("模型自述不应进入摘要");
        // 单条截断：超长的那条不得整段进摘要
        assertThat(digest.length())
                .isLessThanOrEqualTo(FinalReportFenceHook.RESEARCH_DIGEST_MAX_CHARS + 200);
        assertThat(FinalReportFenceHook.researchDigest(null)).isEmpty();
        assertThat(FinalReportFenceHook.researchDigest(List.of())).isEmpty();
    }

    /** 只有工具调用、无正文（MiniMax 耗尽轮的实测形态）。 */
    private static ChatResponse toolCall(String id) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "t", "{}")))
                .build())), null);
    }

    /** 工具调用 + 实质正文（耗尽但已有交付物的形态）。 */
    private static ChatResponse toolCallWithText(String id, String body) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(body)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "t", "{}")))
                .build())), null);
    }

    /** 唯一哨兵（system / userPrompt），供收尾自审依据断言——只可能来自被测来源。 */
    private static final String SENTINEL_SYSTEM = "SYS-SENTINEL-7";
    private static final String SENTINEL_TASK = "TASK-SENTINEL-9";

    private AgentLoopInput input(StubChatModel model, AgentAccessType accessType) {
        return input(model, accessType, AgentLoopInput.DEFAULT_MAX_ITERATIONS);
    }

    private AgentLoopInput input(StubChatModel model, AgentAccessType accessType, int maxIterations) {
        return new AgentLoopInput(model, SENTINEL_SYSTEM, SENTINEL_TASK, toolExecutor, List.of(),
                maxIterations, "run-1-1", 1L, 10L, 1, 3L, null, null, accessType);
    }

    /** 携带业务面 scene 的输入（TD-9 事故修复回归用；走全量 16 参构造）。 */
    private AgentLoopInput inputWithScene(StubChatModel model, AgentAccessType accessType, String scene) {
        return new AgentLoopInput(model, "", SENTINEL_TASK, toolExecutor, List.of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, null, 1, 3L,
                null, null, accessType, LoopCancellation.none(), scene);
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(content).build())), null);
    }

    private static ChatResponse textWithUsage(String content, int totalTokens) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(content).build())),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(1, 1, totalTokens))
                        .build());
    }

    /** 确定性 ChatModel stub：可编程响应队列 + 调用计数 + 失败注入。 */
    private static final class StubChatModel implements ChatModel {
        private final Queue<ChatResponse> responses = new LinkedList<>();
        private final java.util.Map<Integer, RuntimeException> throwPlan = new java.util.HashMap<>();
        private int calls = 0;
        private List<Message> lastPrompt = java.util.Collections.emptyList();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        /** 指定第 N 次调用抛异常（N 从 1 起）；用于模拟「收尾轮重跑失败」。 */
        StubChatModel throwOnCall(int n, RuntimeException ex) {
            throwPlan.put(n, ex);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastPrompt = prompt.getInstructions();
            RuntimeException ex = throwPlan.remove(calls);
            if (ex != null) {
                throw ex;
            }
            return responses.poll();
        }

        int callCount() {
            return calls;
        }

        /** 最近一次调用的 prompt 指令（收尾 prompt 自审依据断言用）。 */
        List<Message> lastPrompt() {
            return lastPrompt;
        }
    }
}
