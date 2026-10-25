package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.shared.util.DeliverableDraft;
import com.helloai.core.shared.util.VerificationFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内部 LLM 提交前自我审计的收尾轮（REF-8.4 ②，{@link LoopHook} 的 {@code beforeTerminate} 首条实现）。
 *
 * <p><b>语义（用户口径）</b>：内部 LLM 在提交产物 / 标记任务完成之前，必须先对自身产出做一次
 * 自审——这条 {@code VERIFICATION/STATUS/NEXT_STEPS} 围栏就是它自证「已核对过自己交付物」的声明。
 * 缺/不合格围栏 ⇒ 有界一次无工具重跑（追加收尾指令）让模型自纠；只对内部 LLM 生效，外部 Agent
 * （有自己工作空间/沙箱，走平台 reviewer 审核）不参与。产出物<b>是否真符合</b>不由此判定——
 * 统一仍归平台 reviewer 角色审核（标准一致）。</p>
 *
 * <p><b>有界保证</b>：恒在「自然 STOP」且围栏不合格时<b>恰好一次</b>收尾轮，收尾轮本身不再重入。
 * 收尾后仍未合格<b>不循环</b>——由 {@code RuntimeTurnExecutor} 出口分类 ④ 收口。口径 A
 * （`D-2026-10-13-6`）下 ④ 用宽松 {@code containsMarker}（有 {@code VERIFICATION:} 痕迹即放行），
 * 故「二次仍缺 STATUS」这类会被放行交 reviewer 大环兜底；只有非空且<b>完全无</b> {@code VERIFICATION:}
 * 痕迹才 fail-close → FAILED。</p>
 *
 * <p><b>自审依据 + 防劣化</b>：收尾 prompt = <b>system（身份/输出契约）+ 原 userPrompt（任务要求）+ 草稿</b>，
 * 使模型能「对照任务要求」核验草稿（而非仅复述草稿）；指令为<b>追加式</b>（原样保留草稿正文、仅末尾追加围栏三行），
 * 堵掉「重写草稿 ⇒ 掉细节但带围栏」被 ④ 宽松放行的劣化空间。不喂整段工具调用史 ⇒ 有界，
 * 规避长上下文溢出；重跑有界重试一次（覆盖瞬时故障）；收尾那一轮 token 计入累计；iterations 与
 * maxIterations 取 min，不超配置上限。</p>
 *
 * <p><b>③ 出口强制（2026-10-16 事故修）</b>：「原样保留草稿正文」只是 prompt 里的<b>指令</b>，模型可能不遵从
 * ——实测 MiniMax-M3 只回围栏三行，整篇报告被 793 字符自检段顶替。故判定权<b>落在出口</b>：
 * 收尾轮产出若不包含草稿正文（{@link #preservesDraftBody} 空白无关比对），一律回退「原草稿 + 收尾轮围栏段」，
 * 既不丢交付物、又照样给出围栏（口径 A 语义不变、不新增拒绝面）。「说了 ≠ 强制」是本钩子的核心教训。</p>
 *
 * <p><b>③b 近空白草稿例外（2026-10-25 死信事故修）</b>：{@link #preservesDraftBody} 的「原样保留」比对
 * 只对<b>有实质内容的草稿</b>有意义——草稿若只有一句开场白（首轮执行者 MAX_ITERATIONS 硬终止，
 * 10 轮迭代全花在工具调用、正文没来得及写，实测草稿 51~98 字符），「收尾轮未包含草稿」判回退
 * 会把<b>唯一合格的完整交付物</b>（收尾轮实测产出 5120~7187 字符、含全部验收证据）一并丢弃，
 * 平台宁可交近空白空壳 + BLOCKED 围栏 → 审查判空 → 返工 3 次 → 死信（3 个子任务同构复现）。
 * 处置：草稿剔除空白后长度低于 {@link #NEAR_BLANK_DRAFT_CHARS}（200 字符，远小于任何文档类
 * 验收的字数下限）时视为「无实质内容可丢」，收尾轮产出<b>只要围栏合格</b>即直接采信
 * （不再要求包含近空白草稿）；2026-10-16 事故的「完整草稿被围栏顶替」不受影响（草稿长，仍回退）。
 * 判定方向保持保守：近空白草稿下「采信收尾轮」只可能让交付物变好（空壳 → 完整报告），不会丢内容。</p>
 *
 * <p><b>红线合规</b>：只改 {@link AgentLoopResult}（循环出口），不碰 {@code SubTaskStatus} / 平台
 * 状态机；不动 {@link AgentLoopTermination#messages()}（精简 prompt，避免对循环消息列表产生副作用）。</p>
 */
@Slf4j
@Component
public class FinalReportFenceHook implements LoopHook {

    /**
     * 收尾指令（追加式）：让模型对照任务要求核验草稿，但<b>原样保留草稿正文</b>、仅在末尾追加围栏三行——
     * 不重写草稿（防丢细节，而 ④ 只查 containsMarker 会上当），收敛输出面、稳上下文。
     */
    private static final String FINALIZE_HEADER_INSTRUCTION =
            "请对照上面的【本轮任务要求】对《报告草稿》做一次自我审计。"
                    + "要求：原样保留草稿正文，不得改写、删减或扩写草稿内容；"
                    + "仅在草稿末尾追加如下围栏三行（不要省略、不要调用任何工具）：\n"
                    + "VERIFICATION:\n- <针对交付物是否满足本轮需求要点的核实说明>\n"
                    + "STATUS: <DONE 或 BLOCKED>\n"
                    + "NEXT_STEPS: <如已全部完成则填 无，否则列出下一步>";

    /** 收尾轮模型调用有界重试次数（覆盖瞬时故障，如网络 / 429 抖动）。 */
    private static final int FINALIZE_MAX_ATTEMPTS = 2;

    /**
     * 空草稿收尾指令（2026-10-25 增补）。
     *
     * <p><b>为什么不能沿用 {@link #FINALIZE_HEADER_INSTRUCTION}</b>：那条指令是「原样保留草稿正文、
     * 只在末尾追加围栏」。草稿为空时它等价于「给一份空文档做审计」——实测 MiniMax 直接回
     * 「草稿为空、无法核实」的 BLOCKED 自述，被 ③b 采信后变成<b>假成功</b>（success=true 但零交付物）。
     * 空草稿场景真正需要的是「依据任务要求 + 已做的调研，从头写出正文」。</p>
     */
    private static final String FINALIZE_WRITE_INSTRUCTION =
            "《报告草稿》当前为空——你此前的轮次已用于工具调研，但**正文从未写出**。"
                    + "现在请**不要调用任何工具**，直接依据上面的【本轮任务要求】与下方的调研结果，"
                    + "写出**完整交付物正文**（Markdown，覆盖全部验收要点；不要写开场白、不要只给自述）。"
                    + "正文末尾附围栏三行：\n"
                    + "VERIFICATION:\n- <针对交付物是否满足本轮需求要点的核实说明>\n"
                    + "STATUS: <DONE 或 BLOCKED>\n"
                    + "NEXT_STEPS: <如已全部完成则填 无，否则列出下一步>";

    /** 空草稿收尾轮注入的调研摘要：单条工具输出截断上限（字符）。 */
    static final int RESEARCH_DIGEST_PER_TOOL_CHARS = 4000;

    /** 空草稿收尾轮注入的调研摘要：总长度上限（字符）。 */
    static final int RESEARCH_DIGEST_MAX_CHARS = 12000;

    /**
     * 「近空白草稿」字符阈值（③b，2026-10-25 死信事故修）。
     *
     * <p>草稿剔除全部空白后长度低于本值 <b>且不含 Markdown 标题标记</b>（{@link #looksLikeDraftBody}）
     * ⇒ 视为<b>无实质内容可丢</b>（首轮执行者 MAX_ITERATIONS 硬终止时实测只写下一句开场白，
     * 51~98 字符；返工轮 MiniMax 也只写「I'll start...」开场白 + 围栏声称，outputTokens 仅 47~74），
     * 收尾轮完整产出直接采信、不再做「原样保留」比对。
     * 200 字符远小于任何文档类验收的字数下限（本事故验收要求 1800~2400 字）。
     * <b>必须叠加「不含 Markdown 标题」</b>：实测「# 手册 - 最终整合报告\n\n## 执行摘要…」剔除空白后
     * 仅 30 余字符，但它是 2026-10-16 事故的「有实质内容草稿」——只按字符数会误判成近空白、
     * 导致完整草稿被收尾轮顶替（回归）。标题标记是「草稿已有交付物骨架」的最廉价信号。</p>
     */
    static final int NEAR_BLANK_DRAFT_CHARS = 200;

    /**
     * 轮数耗尽时「草稿已构成完整正文」的篇幅门槛（字符，去空白）。
     *
     * <p>取 1500 的理由：文档类子任务验收下限普遍 ≥1800 字（本事故 1800~2300），
     * 草稿若已有 1500+ 字符<b>且带标题骨架</b>，说明正文确实写在对话里、不是进度叙述，
     * 此时追加收尾轮只会用缩水版顶替完整草稿（2026-10-16 回归）。
     * 低于此值一律视为「正文不在草稿区」，交由收尾轮救。</p>
     *
     * <p><b>唯一事实源已迁至 {@link DeliverableDraft#MIN_BODY_CHARS}</b>（2026-10-25）：
     * 同一判据现被「turn 末沙箱文件回传」（{@code LocalExecutionCommandConsumer}）共用，
     * 两处各写一份必然漂移（一处调 200、另一处 1500 ⇒ 一边回传、一边又判不值得救）。
     * 此处仅保留常量别名，数值不得独立修改。</p>
     */
    static final int EXHAUSTED_DELIVERABLE_MIN_CHARS = DeliverableDraft.MIN_BODY_CHARS;

    /**
     * 「可疑 STOP」的沙箱调用次数门槛（2026-10-25，`LOG-20261025-009` 实测形态）。
     *
     * <p><b>为什么按「沙箱调用次数」而不是「总工具调用次数」</b>：假成功的病因很具体——
     * <b>正文写进了沙箱文件、没贴回对话</b>（实测 20 轮 / 23 次工具调用，绝大部分是
     * {@code internal_run_in_sandbox} 里的 {@code cat > /workspace/draft.md}）。总调用次数是粗指标：
     * 用它会误伤「调了几次查询工具、然后用 350 字正常作答」的短交付类验收
     * （本事故同批任务验收就是 300~400 字，全被误判成假成功就会白烧一轮 LLM）。
     * 沙箱调用次数是这条病理的<b>签名</b>：真写了文件才会被回传，也才值得救。</p>
     *
     * <p>取 3：一次可能只是「试着跑个命令」，两次可能只是「校验一下」，三次以上基本就是在
     * 反复改写文件正文了。</p>
     */
    static final int SUSPICIOUS_STOP_MIN_SANDBOX_CALLS = 3;

    /** 沙箱工具名片段（{@code internal_run_in_sandbox}；按片段匹配防工具改名后判据静默失效）。 */
    private static final String SANDBOX_TOOL_NAME_FRAGMENT = "sandbox";

    /**
     * 不触发收尾轮的业务面 scene（`TD-9` 事故修复，2026-10-24）。
     *
     * <p>收尾轮语义 = 「子任务交付物提交前自证」（VERIFICATION 围栏），只对<b>子任务执行链</b>
     * 生效（{@code scene == null}）。业务面循环（{@code BusinessSurfaceLoopRunner}）产出的是
     * JSON 结构化结果（拆解草案数组 / 审查判定 / 终报告），不是需自证的交付物——追加围栏会
     * 污染输出导致解析失败（实测：planner_decompose 的 JSON 数组被收尾轮改写成
     * {@code VERIFICATION:...} 前缀文本，`parseDraftItems` 解析失败回退 PENDING）。</p>
     *
     * <p>白名单与 {@code AgentExecutionProperties.businessLoopScenes} 默认值同源；
     * 新增进循环 scene 时必须同步本清单（变更边界走 REF-8.5 新裁定）。</p>
     */
    private static final Set<String> BUSINESS_SCENES_EXCLUDED_FROM_FENCE = Set.of(
            "planner_decompose",
            "planner_expand",
            "planner_regenerate",
            "subtask_review",
            "task_final_report",
            "task_final_report_review");

    @Override
    public AgentLoopResult beforeTerminate(AgentLoopTermination termination) {
        AgentLoopResult draft = termination.draft();
        // 只对内部 LLM 生效（外部 Agent 自审走自身工作空间/沙箱 + 平台 reviewer）
        if (!termination.internalLlm()) {
            return null;
        }
        // `TD-9` 事故修复：业务面循环（JSON 结构化产出）不得追加 VERIFICATION 围栏——
        // 仅子任务执行链（scene == null）触发收尾轮
        if (termination.scene() != null
                && BUSINESS_SCENES_EXCLUDED_FROM_FENCE.contains(termination.scene())) {
            return null;
        }
        // 只在「自然 STOP」的草稿上触发收尾轮（MAX_ITERATIONS / ERROR 属硬终态，不给自纠机会）。
        // ★例外（2026-10-25 MiniMax 轮数耗尽事故）：轮次耗尽**且**已有工具调研**且**草稿近空白时，
        // 追加一次收尾成文——见 {@link #isExhaustedDraftWorthFinalizing} 的条件收紧说明。
        // ★二次例外（同日 #009 实测）：**STOP 也会假成功**——见 {@link #isSuspiciousStopWithoutDeliverable}。
        if (draft == null) {
            return null;
        }
        boolean naturalStop = draft.success() && "STOP".equals(draft.finishReason());
        boolean suspiciousStop = isSuspiciousStopWithoutDeliverable(draft, termination.messages());
        boolean exhaustedRescue = isExhaustedDraftWorthFinalizing(draft);
        if (!naturalStop && !suspiciousStop && !exhaustedRescue) {
            return null;
        }
        // 围栏已合格 ⇒ 零额外 LLM 调用（自审已完成）。
        // ★但「可疑 STOP」不认围栏：实测 MiniMax 的假成功恰好是「604 字节写作过程叙述 + 围栏
        // 声称 DONE」——围栏本身是它自证完成的方式，拿围栏当放行依据正好被它绕过。
        if (!suspiciousStop && VerificationFence.parse(draft.text()).adequate()) {
            return null;
        }
        // 两条救援路径的草稿都「不是可交付正文」（耗尽判据与可疑 STOP 判据都蕴含这一点）⇒
        // 一律走「从头写正文」而非「审计这份草稿」：对过程叙述做审计只会换来更工整的自我表扬。
        return tryFinalizeRound(termination, draft, suspiciousStop || exhaustedRescue);
    }

    /**
     * 「STOP 但没交出正文」的假成功判据（2026-10-25 增补，`LOG-20261025-009`）。
     *
     * <p><b>为什么单靠 {@link #isExhaustedDraftWorthFinalizing} 不够</b>：它只认
     * {@code finishReason == MAX_ITERATIONS}。实测 MiniMax 两次执行都是<b>正常 STOP</b>
     * （模型自己判断「做完了」），20 轮 / 23 次工具调用全花在 {@code internal_run_in_sandbox}
     * 上，正文写在 {@code /workspace/draft.md} 里，对话正文只有 604 字节的
     * <b>写作过程自言自语</b>（「先写第一段…」「已补足到 1800 字」）。这条路径完全绕过兜底：
     * {@code success=true} + 围栏齐全 ⇒ 直接进评审 ⇒ 3 轮全 REJECTED ⇒ 死信。
     * 用户看到的是「执行成功」，实际零交付物——比崩溃更难排查。</p>
     *
     * <p><b>判据 = 三个「且」</b>：① 终态 STOP 且 success；② 本轮<b>沙箱工具调用 ≥
     * {@value #SUSPICIOUS_STOP_MIN_SANDBOX_CALLS} 次</b>（病理签名 = 正文写进沙箱文件，
     * 用总工具调用次数会误伤「调查询工具 + 350 字正常作答」的短交付类验收）；
     * ③ 草稿不构成可交付正文（{@link DeliverableDraft#isDeliverable}，已剔自检围栏——
     * 围栏里的 {@code - 核实说明} 会被误判成正文骨架）。三条同时成立才判可疑。</p>
     *
     * <p><b>处置</b>：与轮数耗尽同等对待（追加一次无工具成文轮），并在收尾轮里改用
     * <b>「从头写正文」指令</b>而非「原样保留草稿」——对一份过程叙述做「审计」只会让它
     * 更工整地重申自己完成了。</p>
     *
     * @param draft    本轮草稿
     * @param messages 本轮消息史（用于识别「本轮是否在沙箱里写过东西」）
     */
    static boolean isSuspiciousStopWithoutDeliverable(AgentLoopResult draft, List<Message> messages) {
        if (draft == null || !draft.success() || !"STOP".equals(draft.finishReason())) {
            return false;
        }
        if (sandboxToolCallCount(messages) < SUSPICIOUS_STOP_MIN_SANDBOX_CALLS) {
            return false;
        }
        return !DeliverableDraft.isDeliverable(draft.text());
    }

    /**
     * 统计本轮「沙箱工具」被调用的次数（{@link ToolResponseMessage} 里名为
     * {@code internal_run_in_sandbox} 的响应条数）。
     *
     * <p>用工具返回消息而不是 assistant 的工具调用意图：只统计<b>真的执行过</b>的调用，
     * 被拒绝/未执行的调用不算（否则「调用失败 10 次」也会被判成写过文件）。</p>
     */
    static int sandboxToolCallCount(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponse)
                    || toolResponse.getResponses() == null) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                if (response != null && response.name() != null
                        && response.name().toLowerCase(java.util.Locale.ROOT)
                        .contains(SANDBOX_TOOL_NAME_FRAGMENT)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 轮数耗尽但「有调研、无正文」的兜底判据（2026-10-25 增补，同日二次修正）。
     *
     * <p><b>为什么放宽既有「MAX_ITERATIONS 不给自纠机会」</b>：MiniMax 实测把 14 轮全部花在
     * 「沙箱写文件 + 反复跑校验脚本」上（`toolCalls=17`、`outputTokens=24563`），
     * 正文只存在于沙箱临时文件里，平台一条都没收到——此形态下草稿**没有任何交付物可丢**，
     * 而工具调研成果就在 messages 里。追加一次「无工具、只喂任务要求 + 草稿」的有界收尾轮，
     * 是把「执行失败」救成「有正文可评审」的唯一低成本手段（一次 LLM 调用，不重入工具）。</p>
     *
     * <p><b>★2026-10-25 二次修正（7443 第 6 次事故）：原判据判错了对象</b>。
     * 原第 3 条要求「草稿近空白（&lt;200 字符且无标题骨架）」，判的是「草稿<b>有没有内容</b>」；
     * 但耗尽场景该判的是「草稿<b>是不是可交付正文</b>」。实测本次 20 轮耗尽时草稿区是逐轮
     * **进度叙述**（「已压缩至 3022 字」之类，超过 200 字符、带 markdown 记号）⇒ 被判「有正文、不必救」，
     * 而 3008 字真正文仍在沙箱 `/workspace/part1.md` 里，平台一个字没收到。**兜底被自己的判据挡在门外。**</p>
     *
     * <p>现判据 =「草稿<b>无标题骨架</b> 且 篇幅不足 {@value #EXHAUSTED_DELIVERABLE_MIN_CHARS} 字符」⇒ 救。
     * 之所以把无骨架一侧的门槛从 200 放宽到 1500：耗尽时草稿区常是逐轮<b>进度叙述</b>
     * （「已压缩至 3022 字」之类，几百字符），旧门槛把它误判成「有正文、不必救」；
     * 而「有标题骨架 ⇒ 不救」这条 2026-10-16 回归防护保持不变。</p>
     *
     * <p><b>成本口径</b>：多一次无工具收尾调用（草稿上限 {@value #RESEARCH_DIGEST_MAX_CHARS} 字符）。
     * 对比一次 20 轮耗尽的执行（实测 122378 tokens / 3 分 51 秒）与随后的重派，这次调用便宜两个数量级。</p>
     */
    static boolean isExhaustedDraftWorthFinalizing(AgentLoopResult draft) {
        if (draft == null || !"MAX_ITERATIONS".equals(draft.finishReason())) {
            return false;
        }
        if (draft.toolCallCount() <= 0) {
            return false;
        }
        String text = draft.text() != null ? draft.text() : "";
        // 判据 =「**无标题骨架** 且 篇幅不足一篇」⇒ 正文不在草稿区，救。
        // 保留「有标题骨架就不会近空白」这条 2026-10-16 回归防护（完整草稿被收尾轮顶替）；
        // 放宽的是**无骨架**一侧的门槛：200 → 1500 字符。本次 20 轮耗尽时草稿区是逐轮进度叙述
        // （「已压缩至 3022 字」之类，几百字符、无 `#` 骨架），旧门槛把它判成「有正文、不必救」。
        // 判据实现已收敛到 DeliverableDraft（同一事实源，turn 末沙箱回传共用）。
        return !DeliverableDraft.isDeliverable(text);
    }

    /**
     * 有界一次无工具重跑：喂「system + 原user(任务要求) + 追加式草稿」，返回修订结果；重跑失败沿用草稿。
     *
     * @param forceWriteFromScratch 草稿「不是可交付正文」（进度叙述 / 开场白 / 空）时置 true：
     *                              改用「从头写正文 + 调研摘要」指令，而不是「审计这份草稿」——
     *                              对过程叙述做审计只会换来更工整的自我表扬（`LOG-20261025-009`）
     */
    private AgentLoopResult tryFinalizeRound(AgentLoopTermination termination, AgentLoopResult draft,
                                             boolean forceWriteFromScratch) {
        String draftText = draft.text() != null ? draft.text() : "";
        List<Message> messages = termination.messages();
        // 精简、有界、且保留自审依据：system(身份/输出契约) + 首个 UserMessage(原任务要求) + 草稿；
        // 不喂中间的工具调用史 —— 既有对照依据、又不因大上下文溢出（③b 治本 + 防劣化）。
        String systemText = firstSystemText(messages);
        String taskText = firstUserText(messages);
        StringBuilder body = new StringBuilder();
        if (StringUtils.hasText(taskText)) {
            body.append("【本轮任务要求】\n").append(taskText).append("\n\n");
        }
        // 空草稿分支（2026-10-25）：改用「从头写正文」指令 + 注入调研摘要——
        // 否则「审计空草稿」只会换来 BLOCKED 自述（实测），被 ③b 采信即假成功。
        // 判据用「真的一字未出」（hasText=false），**不用** NEAR_BLANK 阈值：后者是
        // ③b「有无内容可丢」的判据，对「短但有实质内容」的草稿（如已有骨架的短稿）
        // 会误判成空，导致丢弃既有正文 + 围栏（2026-10-16 / LOG-050 两个反例均依赖旧路径）。
        // ★2026-10-25 扩展：{@code forceWriteFromScratch} 覆盖「非空但不是正文」的情况
        // （STOP 假成功的 604 字节过程叙述），此时草稿必须**不作为**审计对象喂进去。
        boolean blankDraft = !StringUtils.hasText(draftText);
        boolean writeFromScratch = blankDraft || forceWriteFromScratch;
        if (writeFromScratch) {
            body.append(FINALIZE_WRITE_INSTRUCTION);
            String digest = researchDigest(messages);
            if (StringUtils.hasText(digest)) {
                body.append("\n\n【已获得的调研结果（截断摘要，可直接作为撰写依据）】\n").append(digest);
            }
            if (!blankDraft) {
                // 过程叙述保留下来仅作「已知进度」参考，明确标注不是正文——
                // 否则模型会把「我已写到第 3 节」当成已完成的交付物继续复述。
                body.append("\n\n【此前轮次的过程记录（**不是交付物**，仅示意进度，不要照抄、不要复述）】\n")
                        .append(draftText);
            }
        } else {
            body.append(FINALIZE_HEADER_INSTRUCTION).append("\n\n《报告草稿》\n").append(draftText);
        }
        List<Message> lean = new ArrayList<>(2);
        if (StringUtils.hasText(systemText)) {
            lean.add(new SystemMessage(systemText));
        }
        lean.add(new UserMessage(body.toString()));
        ChatModel chatModel = termination.chatModel();
        ChatResponse response = null;
        RuntimeException lastEx = null;
        for (int attempt = 0; attempt < FINALIZE_MAX_ATTEMPTS && response == null; attempt++) {
            try {
                // 不设 toolCallbacks ⇒ 收尾轮只产文本，不重入任何工具（有界、无副作用）
                DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
                options.setInternalToolExecutionEnabled(false);
                response = chatModel.call(new Prompt(lean, options));
            } catch (RuntimeException e) {
                lastEx = e;
            }
        }
        if (response == null) {
            log.warn("FinalReportFenceHook: 收尾轮失败，沿用原草稿（交出口分类按口径 A 宽判 / reviewer 大环兜底）: err={}",
                    lastEx == null ? "unknown" : lastEx.getMessage());
            return draft;
        }
        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response);
        String text = extracted.text();
        // ② 防空产出清空交付物：收尾轮返回空正文（推理模型只回 reasoning_content / getResults 空，
        // extract 返回 "" 而非 null）⇒ 沿用原草稿，不把既有报告抹掉。贴合口径 A：草稿即便只在
        // 围栏不完整（如已含 VERIFICATION 缺 STATUS）也本应放行交 reviewer，绝不能被收尾清成空。
        if (text == null || text.isBlank()) {
            log.warn("FinalReportFenceHook: 收尾轮空产出，沿用原草稿（防交付物被清空）");
            return draft;
        }
        // ③ 防「收尾轮改写丢正文」（2026-10-16 实测事故）：指令虽写明「原样保留草稿正文、
        // 仅在末尾追加围栏三行」，但模型可能只回围栏三行 —— 此时若直接采用收尾轮正文，
        // 整篇交付物被 793 字节的自检段顶替（task 2110984337513603074 的整合报告即此形态）。
        // 「说了」不等于「强制」：判定权必须落在出口，不能交给模型自觉。
        // 处置：改为「原草稿正文 + 收尾轮围栏段」——既保住交付物，又照样给出围栏
        // （不新增拒绝面：围栏仍在，出口分类口径 A 的语义不变）。
        String effectiveText = text;
        // ③d 只回围栏不算产出（2026-10-25 实测）：模型会回「VERIFICATION / STATUS: BLOCKED」
        // 自述充当产出——那不是交付物，采信即「假成功」（success=true 但零交付物，评审必驳回，
        // 等于把失败伪装成成功）。判定 = 本轮走「从头写正文」**且** 收尾轮剔除围栏后一个字都没有。
        // 用「有无正文」而非字符阈值：短但真实的正文（如已有骨架的短稿）必须被采信，
        // 阈值会把它误判成自述（2026-10-16 / LOG-050 两个反例依赖被采信）。
        if (writeFromScratch && !StringUtils.hasText(stripFence(text))) {
            log.warn("FinalReportFenceHook: 要求「从头写正文」但收尾轮只回了围栏（{} 字符，正文近空白），"
                    + "不采信为成功，沿用原草稿", text.length());
            return draft;
        }
        if (writeFromScratch) {
            // ③b' 从头写正文场景（2026-10-25，空草稿 / STOP 假成功）：成功标准只有一个 ——
            // 收尾轮**真的写出了正文**。此处不得比对「是否原样保留草稿」：草稿本就不是正文
            // （空 / 604 字节过程叙述），比对必然失败并回退成「过程叙述 + 围栏」= 假成功复现。
            log.info("FinalReportFenceHook: 从头成文场景，采信收尾轮产出（正文 {} 字符，原草稿 {} 字符且非正文）",
                    compact(stripFence(text)).length(), compact(draftText).length());
        } else if (compact(draftText).length() < NEAR_BLANK_DRAFT_CHARS
                && !looksLikeDraftBody(draftText)
                && VerificationFence.parse(text).adequate()) {
            // ③b 近空白草稿例外（2026-10-25 死信事故修）：草稿无实质内容可丢（仅开场白 / 极短残句，
            // 无 Markdown 标题骨架）⇒ 不判「未保留草稿」回退——收尾轮完整产出即交付物（否则唯一合格
            // 报告被丢弃 → 近空白空壳 + BLOCKED 围栏 → 审查判空 → 返工 3 次死信，3 子任务同构复现）。
            // 收尾产出仍须围栏合格才采信（防采信垃圾）；2026-10-16「完整草稿被围栏顶替」不受影响
            // （草稿长于阈值或含 Markdown 标题，仍走下方 preservesDraftBody 回退）。判错方向保守：
            // 只会空壳变完整，不会丢内容。
            log.info("FinalReportFenceHook: 草稿近空白（{} 字符，无标题骨架），采信收尾轮完整产出（{} 字符）",
                    compact(draftText).length(), text.length());
        } else if (!preservesDraftBody(draftText, text)) {
            String fence = extractFenceBlock(text);
            effectiveText = fence.isBlank() ? draftText : draftText + "\n\n" + fence;
            log.warn("FinalReportFenceHook: 收尾轮未原样保留草稿正文（草稿 {} 字符 → 收尾 {} 字符），"
                            + "回退「草稿 + 围栏段」防交付物被顶替",
                    draftText.length(), text.length());
        }
        String thinking = extracted.thinking() != null && !extracted.thinking().isBlank()
                ? extracted.thinking()
                : draft.thinking();
        // ③a：收尾那一轮的 token 计入累计（复用 delta 累加逻辑）
        Integer tokens = ChatModelToolLoop.accumulateUsage(response, draft.tokenUsage());
        // ③c：收尾轮计为额外一次模型轮，与 maxIterations 取 min，不超配置上限
        int iterations = Math.min(draft.iterations() + 1, termination.maxIterations());
        return AgentLoopResult.stop(effectiveText, thinking, iterations, draft.toolCallCount(), tokens);
    }

    /**
     * 收尾轮正文是否<b>原样保留</b>了草稿正文（③ 出口判定的纯函数）。
     *
     * <p>比对前剔除全部空白字符（含换行 / 制表 / 全角空格外的常规空白），只比对字符序列 ——
     * 使「模型仅调整了换行或缩进」不被误判为丢正文，而「只回围栏三行」必然判为丢正文。
     * 空草稿视为保留（无正文可丢）。判定方向为<b>保守</b>：判错时回退原草稿，只会让围栏缺失，
     * 不会丢掉交付物。</p>
     *
     * @param draftText    首轮草稿正文（{@code null} / 空白 ⇒ true）
     * @param finalizeText 收尾轮正文（{@code null} / 空白 ⇒ false）
     * @return 收尾轮正文是否包含草稿正文的完整字符序列
     */
    static boolean preservesDraftBody(String draftText, String finalizeText) {
        if (draftText == null || draftText.isBlank()) {
            return true;
        }
        if (finalizeText == null || finalizeText.isBlank()) {
            return false;
        }
        return compact(finalizeText).contains(compact(draftText));
    }

    /** 从收尾轮正文中取围栏段（自最后一个 {@code VERIFICATION:} 标记起）；无标记返回空串。 */
    static String extractFenceBlock(String finalizeText) {
        return VerificationFence.fenceBlock(finalizeText);
    }

    /** 剔除围栏段（{@code VERIFICATION:} 起）后剩下的正文本体；无标记返回原文。 */
    static String stripFence(String finalizeText) {
        if (finalizeText == null) {
            return "";
        }
        int idx = finalizeText.lastIndexOf(VerificationFence.MARKER);
        return idx < 0 ? finalizeText : finalizeText.substring(0, idx);
    }

    /**
     * 从本轮 messages 里提取工具调研结果摘要——空草稿收尾轮的<b>唯一素材来源</b>。
     *
     * <p>空草稿意味着「调研做了、正文没写」，此时既不能喂草稿（空），也不能什么都不喂
     * （模型只能回 BLOCKED 自述）。故按「每条截断 + 总量封顶」注入已拿到的工具输出
     * （子任务详情 / 依赖产物正文等）：给足撰写依据，又不把收尾轮本身拖进溢出分支。
     * 只取 {@link ToolResponseMessage}（工具返回），不取 assistant 自述。</p>
     */
    static String researchDigest(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponse)
                    || toolResponse.getResponses() == null) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                if (response == null || !StringUtils.hasText(response.responseData())) {
                    continue;
                }
                if (sb.length() >= RESEARCH_DIGEST_MAX_CHARS) {
                    return sb.append("\n…（调研结果已截断）").toString();
                }
                sb.append("### ").append(response.name()).append('\n');
                String data = response.responseData();
                sb.append(data, 0, Math.min(data.length(), RESEARCH_DIGEST_PER_TOOL_CHARS)).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /** 剔除全部空白字符（空白无关比对用）。实现已收敛到 {@link DeliverableDraft#compact}。 */
    private static String compact(String s) {
        return DeliverableDraft.compact(s);
    }

    /**
     * 草稿是否已具备「交付物骨架」（③b 判据补充）。
     *
     * <p><b>实现已迁至 {@link DeliverableDraft#looksLikeDraftBody}</b>（2026-10-25）：
     * 同一判据现被「turn 末沙箱文件回传」共用，两处各写一份必分叉。此处保留为委托，
     * 使本类既有调用点与单测无需改签名。</p>
     */
    static boolean looksLikeDraftBody(String draftText) {
        return DeliverableDraft.looksLikeDraftBody(draftText);
    }

    /** 取消息历史中的 system 提示（首个 SystemMessage 的文本；缺失返回 null）。 */
    private static String firstSystemText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Message first = messages.get(0);
        if (first instanceof SystemMessage sm) {
            return sm.getText();
        }
        return null;
    }

    /** 取首个 UserMessage 的文本（即原任务要求；缺失返回 null）。 */
    private static String firstUserText(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (Message message : messages) {
            if (message instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }
}
