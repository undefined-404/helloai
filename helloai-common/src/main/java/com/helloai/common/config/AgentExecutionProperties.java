package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台内 Agent 执行链配置。
 *
 * <p>默认真实模式（2026-09-30 起，审计 §11.4 风险 3 修正）：mock 必须由显式配置
 * {@code helloai.execution.mock-mode=true} 开启（如 application-it.yml 测试 profile）；
 * 避免新增 profile / 属性绑定异常时静默走 mock（"看着执行了，其实没调 LLM"）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.execution")
public class AgentExecutionProperties {

    /**
     * 执行命令消费载体模式。
     *
     * <p><b>重塑语义（与差距表 N6 + 架构参考 §5.1 阶段一拍板对齐）</b>：
     * 三种模式都对应"Poller 仅作孤儿/超时/补偿兜底"，区别在于<b>主消费路径</b>由谁承担。
     * 主消费路径失效时（如 MQ Consumer Bean 未注册、@Async 线程池卡死、JVM 异常退出），
     * Poller 通过 {@code listOrphanPending(threshold)} 兜底扫描重新触发消费。</p>
     *
     * <ul>
     *     <li>{@code EVENT}：{@code @Async + @TransactionalEventListener} 作为本地事务事件主消费，
     *         Poller 仅扫孤儿（兼容模式，与 §5.1 ②a 之前的旧行为等价）；</li>
     *     <li>{@code POLLER}：MQ 主消费路径（前提 {@code dispatch-mode ∈ {MQ,BOTH}} +
     *         {@code helloai.mq.execution-command.consumer-enabled=true} + {@code producer-enabled=true} +
     *         {@code outbox.relay.enabled=true}，启动期 {@code ExecutionDispatchValidator} 会 fail-fast），
     *         Poller 仅扫孤儿；</li>
     *     <li>{@code BOTH}：本地事务事件 + MQ 双主消费，由 Consumer 内部 CAS {@code markRunning}
     *         保证幂等（与 EVENT→MQ 灰度切换过渡形态），Poller 仅扫孤儿。</li>
     * </ul>
     *
     * <p><b>本轮明确不做</b>：保留 POLLER 旧语义"扫全量 PENDING 作主路径"作为兼容模式——
     * MQ 投递已通过 ②a/②b 完成可靠性收口，Poller 降级为兜底是必然演进方向，
     * 但仍允许通过不修改代码直接切回旧 POLLER 语义（虽然 {@code ExecutionDispatchValidator}
     * 会在默认配置下阻断这种部署）。</p>
     */
    public enum ConsumerMode {
        /**
         * 事件消费（兼容模式）：
         * {@code @Async + @TransactionalEventListener} 作为本地事务事件主消费，
         * Poller 仅扫孤儿 PENDING 作为兜底恢复。
         */
        EVENT,
        /**
         * MQ 主消费（默认）：
         * MQ Consumer Bean（{@code MqExecutionCommandConsumer}）作为主消费路径，
         * 消费端读 MQ 消息 → 委托 {@code LocalExecutionCommandConsumer} 推进；
         * Poller 仅扫孤儿 PENDING 作为 MQ 主链异常时的兜底恢复。
         */
        POLLER,
        /**
         * 双消费：本地事务事件与 MQ Consumer 同时作为主消费路径并行运行，
         * 由 Consumer 内部 CAS {@code markRunning} 保证幂等；
         * Poller 仅扫孤儿。适合作为 EVENT→POLLER 灰度切换过渡阶段。
         */
        BOTH
    }

    /** 是否启用平台内执行链。 */
    private boolean enabled = true;

    /** 是否启用稳定 mock 模式。默认 false（真实模式优先，防静默 mock 陷阱）；显式配置 mock-mode=true 才走 mock。 */
    private boolean mockMode = false;

    /** real 模式是否强制要求 vault 已绑定凭证。默认 false（先兼容全局 Provider 配置）。 */
    private boolean requireVault = false;

    /** mock provider 名称。 */
    private String provider = "mock";

    /** mock model 名称。 */
    private String model = "helloai-mock-executor";

    /**
     * 内部 LLM 执行者（API_KEY_LLM）的 AgentLoop 最大循环轮数（REF-8.2 分档）。
     *
     * <p>内部执行者要多步调工具、读附件、写产物，故独立分档默认高于外部一次性。
     * 默认 20（2026-10-25 二次调高）：10 轮在「web_search 资料调研 + 沙箱验证」上
     * 耗尽迭代、正文没来得及写（草稿仅开场白）的实测事故表明，文档写作类任务
     * 「调研 + 成文」合计需要 12~14 轮；但同日 MiniMax 实测出现更强形态——
     * 14 轮被「沙箱写文件 + 反复跑校验脚本」全部吃掉（toolCalls=17、outputTokens=24563，
     * 正文只存在于沙箱文件里），仍以 MAX_ITERATIONS 失败。故再留 6 轮余量；
     * 同时 turnDeadlineSeconds（默认 1800s）仍兜住异常长跑，且提示词侧已明确
     * 「自查最多 1 次、正文优先」（{@code AgentRuntimeContextAssembler} 输出契约）。
     * 单元测试可直接观测该档位；配置项语义与 {@link #maxExternalIterations()} 正交。</p>
     */
    private int internalMaxIterations = 20;

    /**
     * 内部 LLM 执行者之外的 AgentLoop 最大循环轮数（REF-8.2 分档的默认档）。
     *
     * <p>外部 Agent（如一次 pullTasks + submitResult 的短链）默认维持 5 轮硬上限，
     * 与 {@code AgentLoopInput.DEFAULT_MAX_ITERATIONS} 一致；配置项为空白时回落该默认。</p>
     */
    private int externalMaxIterations = 5;

    /**
     * 内部 LLM 系统提示词的**部署级 persona 前缀**（REF-8.1.2，对齐 DSH `deployment:persona-prefix`）。
     *
     * <p>空 ⇒ 只用平台内置身份行；非空 ⇒ 紧跟内置身份行之后注入。</p>
     * <p><b>遮蔽纪律</b>（照抄 DSH `dsh-persona`）：`agent.labels.personaPrefix` 优先于本配置——
     * 部署给默认，单个 Agent 可遮蔽。</p>
     */
    private String personaPrefix = "";

    // ────────────────────────────────────────────────────────────
    //  REF-8.6 运行时合同 · 取消 / 超时树（对齐 DSH 一等公民能力）
    // ────────────────────────────────────────────────────────────

    /**
     * 单次 Agent Turn（一次子任务执行尝试 / 一次业务面调用）的**取消截止时间**（秒）。
     *
     * <p><b>为什么需要</b>：REF-8.6 前，循环内任何一轮模型调用或工具调用卡住，都没有任何
     * 主动中断机制（只能事后靠外部超时回收）。本项是**自驱动的取消源**——到期后循环在下一个
     * 动作边界停止（不再发起新的模型 / 工具调用），并在结果上带 {@code finishReason=CANCELLED}。</p>
     *
     * <p><b>默认 1800（30 分钟）</b>：单 Turn 至多 {@code internalMaxIterations}（默认 10）轮模型
     * 调用，30 分钟是「正常绝不会触发、异常必被拦住」的量级。{@code <= 0} = 不限（回到改造前行为）。</p>
     */
    private long turnDeadlineSeconds = 1800L;

    /**
     * **单次工具调用**超时（毫秒）。超时**不整链失败** —— 以失败 observation 回喂模型，
     * 交模型决策（换工具 / 换参数 / 收尾）；见 {@code CancellableToolExecutor}。
     *
     * <p><b>默认 300000（5 分钟）</b>：外部检索类工具的合理上限；{@code <= 0} = 不限。</p>
     */
    private long toolCallTimeoutMillis = 300000L;

    // ────────────────────────────────────────────────────────────
    //  REF-8.5 业务面循环覆盖面（覆盖面收敛轴，判据见 plan §10.6）
    // ────────────────────────────────────────────────────────────
    /**
     * 业务 LLM 面是否走工具循环（REF-8.5 覆盖面收敛）。
     *
     * <p><b>背景</b>：REF-8.5 实测确认——内部 LLM 的工具循环此前<b>只</b>存在于子任务执行链
     * （{@code RuntimeTurnExecutor}），业务面（拆解 / 子任务核验 / 终报告整合 / 终报告审查）
     * 全部走 {@code executeSync} 的<b>裸单次</b> {@code ChatModel} 调用（无工具 / 无循环 / 无 hook）。
     * 本开关把那 4 个已裁定面接进同一份 {@code AgentLoop}。</p>
     *
     * <p><b>回退语义</b>：置 {@code false} ⇒ 全部业务面立即回到裸单次（无需改代码 / 重启热配置）。
     * 循环本身异常（抛异常 / {@code finishReason=ERROR}）时，业务面亦自动回退单次，不阻断业务。</p>
     *
     * <p><b>默认 true</b>：覆盖面清单（plan §10.6）已由用户逐项裁定「需要做」——
     * 默认关闭等于裁定不生效；且 mock 模式下 {@code MockChatModel} 不产工具调用 ⇒
     * 循环一轮即终态，与单次行为等价，故默认开启的观测风险可控。</p>
     */
    private boolean businessLoopEnabled = true;

    /**
     * 进循环的 {@code scene} 白名单（REF-8.5 覆盖面收敛）。
     *
     * <p>取值 = {@code AgentTask.context} 的 {@code scene} 键。默认 = plan §10.6 已裁定的
     * <b>4 个进循环面</b>（其余 12 项明确不进，见该表尾注「★本表即 REF-8.5 的验收边界」）。</p>
     *
     * <p><b>变更边界须走新裁定并登记决策</b>：新增 scene 须按同判据（产出是否需查阅 / 校验外部证据）
     * 重新取证，不得以「顺便」为由扩面。</p>
     */
    private List<String> businessLoopScenes = new ArrayList<>(List.of(
            "planner_decompose",
            "subtask_review",
            "task_final_report",
            "task_final_report_review"));

    // ────────────────────────────────────────────────────────────
    //  REF-8.2.3 上下文压缩（对齐 DSH `compaction-basic` + `compaction-tool-result-pruner`）
    //  两道：① 确定性工具结果剪枝（纯文本、可审计）② 仍超预算才做 LLM 摘要（替换而非追加）
    // ────────────────────────────────────────────────────────────

    /** 总开关（对齐 DSH `compaction-basic.auto`）；关掉即回到改造前行为（无剪枝、无摘要）。 */
    private boolean contextCompactionEnabled = true;

    /**
     * 模型输入窗口（token）——**须与部署模型实际窗口一致**（我们的模型注册表暂无该字段，
     * 故以配置承载；DSH 从路由模型信息读取）。
     */
    private int contextWindowTokens = 64000;

    /** 压力触发比例（DSH `thresholdRatio` 默认 0.8）：预算 = 窗口 × 本值。 */
    private double contextCompactionThresholdRatio = 0.8;

    /** 摘要后保留的最近原文比例（DSH `retainRatio` 默认 0.16）：尾部这段**逐字保留**不摘要。 */
    private double contextCompactionRetainRatio = 0.16;

    /**
     * 提供方报超窗时的「压缩后重试本轮」上限（DSH `maxOverflowRetries`）。
     * 成功响应后计数重置（DSH 同口径）。
     */
    private int contextCompactionMaxOverflowRetries = 1;

    /**
     * 单次执行内**摘要次数上限**（REF-8.2.3 防抖动）。
     *
     * <p>压缩的净收益不保证（摘要可能比被压区间还长）⇒ 我们：① 只在**净减**时接受（否则回滚原文）
     * ② 用本上限封顶，避免"每轮都试一次摘要"的反复调用（实测暴露：窗口设得远小于实际内容时，
     * 首次摘要出现 4939 → 6327 的净增）。</p>
     */
    private int contextCompactionMaxSummariesPerRun = 2;

    /** 工具结果剪枝阈值字符数（DSH `thresholdChars` 默认 8192）：超过则中段省略。 */
    private int toolResultPruneThresholdChars = 8192;

    /** 剪枝保留的头部字符数（DSH `headChars` 默认 4096）。 */
    private int toolResultPruneHeadChars = 4096;

    /** 剪枝保留的尾部字符数（DSH `tailChars` 默认 1024）。 */
    private int toolResultPruneTailChars = 1024;

    /**
     * **部署级 persona 后缀**（对齐 DSH `deployment:persona-suffix`）：非空则注入到整个 systemPrompt 末尾。
     * 遮蔽纪律同上（`agent.labels.personaSuffix` 优先）。
     */
    private String personaSuffix = "";

    /** mock 前缀，便于联调时快速识别结果来源。 */
    private String mockResponsePrefix = "[mock-executor]";

    /**
     * 平台内 LLM 调用并发上限（对话并发优化 B 项）。
     *
     * <p>真实 Provider 模式下所有 LLM 调用（对话澄清/拆解/审查/执行）共用同一批
     * API Key，上游有 RPS 限流；信号量限流避免并发打爆上游触发 429。
     * &lt;=0 表示不限流（保持原行为）；mock 模式自动跳过限流。</p>
     */
    private int maxConcurrentLlmCalls = 8;

    /**
     * LLM 并发许可获取超时（秒）：超时抛 BizException 提示稍后重试，
     * 避免请求无限阻塞在信号量上占满 Tomcat 线程。
     */
    private long llmAcquireTimeoutSeconds = 60L;

    /** PENDING 执行记录超时分钟数，默认 5。 */
    private int pendingTimeoutMinutes = 5;

    /** RUNNING 执行记录超时分钟数，默认 10。 */
    private int runningTimeoutMinutes = 10;

    /** DB Poller 扫描周期（毫秒）。默认 1000 ms。 */
    private long pollerIntervalMs = 1000L;

    /** DB Poller 扫描孤儿阈值（秒）。超过该时间未被 Poller 触及的 PENDING 行视为孤儿。默认 60 秒。 */
    private int pollerOrphanThresholdSeconds = 60;

    /** DB Poller 单批扫描上限，避免扫到大量孤儿记录时阻塞调度线程。默认 20。 */
    private int pollerBatchSize = 20;

    /** DB Poller 是否启用。默认 true。 */
    private boolean pollerEnabled = true;

    /**
     * §4.1：PENDING 孤儿阈值（分钟）。
     *
     * <p>子任务 status=PENDING 且 create_time 距今超过本阈值、且尚未创建
     * {@code agent_execution_record} 记录的，视为“dispatch-mode=EVENT 主路径丢失”
     * 的孤儿，由 {@code SubTaskPendingOrphanTask} 周期重派。</p>
     *
     * <p>依赖解锁瞬间“无空闲候选”导致自动分发失败
     * （pickPreferred 返回 null）的子任务同样呈现“PENDING 且无 execution_record”，
     * 需巡检快速兜底，避免形成无人兜底窗口。扫描命中后循环内还有 {@code isReady}
     * 依赖守卫——未就绪的合法 PENDING 会跳过不误伤，因此收窄阈值安全，
     * 仅缩短“分发失败”的恢复时间。</p>
     */
    private int pendingOrphanThresholdMinutes = 5;

    /**
     * §4.1：PENDING 孤儿巡检周期（毫秒）。默认 60000ms=1 分钟。
     */
    private long pendingOrphanScanIntervalMs = 60000L;

    /**
     * §4.1：PENDING 孤儿巡检单批上限。默认 50 条，防止调
     * 度线程被批量重派阻塞。
     */
    private int pendingOrphanBatchSize = 50;

    /**
     * §4.1：PENDING 孤儿巡检是否启用。默认 true。
     */
    private boolean pendingOrphanEnabled = true;

    // ────────────────────────────────────────────────────────────
    //  BLOCKED 自动重派巡检（plan §10.7.6 方案 b：巡检接线）
    // ────────────────────────────────────────────────────────────

    /**
     * BLOCKED 子任务自动重派巡检是否启用。默认 true。
     *
     * <p><b>补的是哪条缺口</b>：{@code dispatchBlockedSubTask} 此前<b>只有人工入口</b>
     * （`reassignById` / `redispatchInProgress`），而 BLOCKED 恰恰是「重启撞上在途任务」
     * 与「执行者自报无能力」两条路径的落点 ⇒ 无人干预时下游依赖恒 PENDING，流水线永久停摆。
     * 本开关打开后由 {@code SubTaskBlockedAutoRedispatchTask} 周期扫描并自动重派。</p>
     *
     * <p><b>不变量</b>：只<b>触发</b>既有重派链，不改状态机；重派仍受既有闸门约束
     * （退避窗口 + 熔断，达 {@link AgentDispatchProperties#maxReassignAttempts} 转 DEAD_LETTER）。</p>
     */
    private boolean blockedRedispatchEnabled = true;

    /**
     * BLOCKED 自动重派巡检周期（毫秒）。默认 60000ms=1 分钟。
     */
    private long blockedRedispatchScanIntervalMs = 60000L;

    /**
     * BLOCKED 自动重派阈值（分钟）：{@code update_time} 距今超过本阈值才纳入重派。
     *
     * <p>与 PENDING 孤儿阈值同理由——刚 BLOCKED 的子任务可能正处于人工处置窗口内，
     * 留一个观察窗口避免与人工操作抢跑。默认 5。</p>
     */
    private int blockedRedispatchThresholdMinutes = 5;

    /**
     * BLOCKED 自动重派单批上限。默认 50 条，防止调度线程被批量重派阻塞。
     */
    private int blockedRedispatchBatchSize = 50;

    /**
     * 是否把「执行者自报阻塞」（{@code manualIntervention.reason} =
     * {@link com.helloai.common.constant.ManualInterventionReasons#EXECUTOR_REPORTED_BLOCKED}）
     * 也纳入自动重派。默认 true。
     *
     * <p><b>为什么这类要单独开关</b>：其余 {@code manualIntervention} 原因码都表示
     * 「等人工拍板」，自动链一律不碰（与 PENDING 孤儿巡检 / N11 回退同口径）；
     * 只有自报阻塞表示「当前执行者做不到」，而平台对此有确定处置动作 = 换执行者
     * （plan §10.7.6「发现放内、处置放外」）。置 {@code false} ⇒ 回到「自报阻塞一律等人工」。</p>
     */
    private boolean blockedRedispatchSelfReportedEnabled = true;

    /**
     * 消费载体模式。默认 POLLER，使 DB Poller 成为执行命令主消费路径。
     *
     * <p>三挡：
     * <ul>
     *     <li>{@code EVENT}：本地事务事件为主，Poller 仅扫描孤儿；</li>
     *     <li>{@code POLLER}：命令服务不发布本地事件，Poller 扫所有 PENDING 作为主路径；</li>
     *     <li>{@code BOTH}：事件与 Poller 都运行，CAS 保证幂等，适合作为 EVENT→POLLER 过渡阶段。</li>
     * </ul>
     * </p>
     */
    private ConsumerMode consumerMode = ConsumerMode.POLLER;

    /**
     * 执行命令派发模式（生产端 / 调度侧的显式配置）。
     *
     * <p>与 {@link ConsumerMode} 语义对称：{@code ConsumerMode} 描述<em>消费端</em>从哪条路径消费执行命令；
     * {@code DispatchMode} 描述<em>生产端</em>把执行命令<em>额外</em>向哪条路径推送。
     * 两者相互独立，共同覆盖调度解耦重构分析中"调度只发命令、执行独立消费"的目标态。</p>
     *
     * <p>四挡：
     * <ul>
     *     <li>{@code NONE}：命令只落库，不发本地事件、不发 MQ，交由 DB Poller 兜底消费——
     *         与项目当前默认事实（consumer-mode=POLLER）配套，保持零行为变化；</li>
     *     <li>{@code EVENT}：只发布本地 Spring 事务事件（旧 EVENT 路径）；</li>
     *     <li>{@code MQ}：只投递 RabbitMQ（前提 producer-enabled=true 且 Publisher Bean 可用，
     *         否则启动期或运行期 fail-fast，不做隐式回退）；</li>
     *     <li>{@code BOTH}：本地事件 + MQ 双发，用于 EVENT→MQ 灰度切换过渡。</li>
     * </ul>
     * </p>
     */
    public enum DispatchMode {
        NONE,
        EVENT,
        MQ,
        BOTH
    }

    /**
     * 派发模式。默认 {@link DispatchMode#NONE}，保持"命令只落库、Poller 兜底"的当前生产事实。
     *
     * <p>本字段仅供生产端 {@code ExecutionCommandService} 读取，与 {@link #consumerMode} 完全解耦。</p>
     */
    private DispatchMode dispatchMode = DispatchMode.NONE;

    /**
     * 是否为 MQ 主消费模式（POLLER 或 BOTH 都算）。
     *
     * <p>重塑语义：POLLER/BOTH 模式都对应"MQ Consumer 作为主消费路径"，
     * Poller 仅扫孤儿作兜底。本方法名保留仅为兼容外部调用方（如
     * {@code ExecutionCommandPoller} 与 {@code ExecutionDispatchValidator}），
     * 实际语义已从"Poller 主消费"更新为"MQ 主消费路径启用"。</p>
     */
    public boolean isPollerMain() {
        return consumerMode == ConsumerMode.POLLER || consumerMode == ConsumerMode.BOTH;
    }

    /**
     * 是否为事件主消费模式（EVENT 或 BOTH 都算）。
     *
     * <p>仅描述"消费侧使用了本地 {@code @TransactionalEventListener} 主路径"，
     * 与 Poller 兜底职责无关。</p>
     */
    public boolean isEventMode() {
        return consumerMode == ConsumerMode.EVENT || consumerMode == ConsumerMode.BOTH;
    }

    /** 是否需要生产端发布本地 Spring 事件（EVENT 或 BOTH）。 */
    public boolean isDispatchEvent() {
        return dispatchMode == DispatchMode.EVENT || dispatchMode == DispatchMode.BOTH;
    }

    /** 是否需要生产端投递 MQ（MQ 或 BOTH）。 */
    public boolean isDispatchMq() {
        return dispatchMode == DispatchMode.MQ || dispatchMode == DispatchMode.BOTH;
    }
}
