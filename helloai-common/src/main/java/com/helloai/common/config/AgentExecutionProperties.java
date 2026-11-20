package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台内 Agent 执行链配置。
 *
 * <p>默认真实模式（审计 §11.4 风险 3 修正）：mock 必须由显式配置
 * {@code helloai.execution.mock-mode=true} 开启（如 application-it.yml 测试 profile）；
 * 避免新增 profile / 属性绑定异常时静默走 mock（"看着执行了，其实没调 LLM"）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.execution")
public class AgentExecutionProperties {

    /**
     * 执行命令消费载体模式（差距表 N6 / 架构参考 §5.1）。
     *
     * <p>三种模式都对应「Poller 仅作孤儿/超时/补偿兜底」，区别在主消费路径由谁承担；
     * 主路径失效（Consumer Bean 未注册 / @Async 线程池卡死 / JVM 退出）时 Poller 经
     * {@code listOrphanPending(threshold)} 兜底重新触发消费。各模式语义见枚举常量。</p>
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
     * <p>内部执行者要多步调工具、读附件、写产物，故独立分档高于外部一次性（{@link #externalMaxIterations}）。
     * 默认 20：文档写作类「调研 + 成文」需 12~14 轮，沙箱写文件 + 反复跑校验脚本还会再吃数轮，故留余量；
     * 提示词侧已明确「自查最多 1 次、正文优先」。</p>
     *
     * <p><b>异常长跑由谁兜住（`MA-10` 改动 4 后已变更，2026-11-19）</b>：<b>不再是</b>
     * {@link #turnDeadlineSeconds}——子任务链已删除该闸的装配（它在那里从未有机会开火）。
     * 现在是<b>次数主 + 两道兜底</b>：轮次耗尽 ⇒ 尽力交付（`AgentLoopResult#isPartialDelivery`）；
     * 单工具卡住 ⇒ {@link #toolCallTimeoutMillis}；整轮不推进 ⇒ {@link #noProgressTimeoutMinutes}
     * 的「无进展」扫描。业务面保留 {@link #turnDeadlineSeconds}（那里无 execution record）。</p>
     *
     * <p><b>off-by-one</b>：{@code ChatModelToolLoop} 从第 {@code maxIterations - 1} 轮起禁用工具强制收尾
     * ⇒ 实际可用工具轮次 = 本值减 1，调整时按此口径算。</p>
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
     * 子任务级轮次预算（{@code sub_task.context.maxIterationsHint}）的**上限钳制**（`MA-10` 改动 2）。
     *
     * <p><b>为什么必须钳</b>：hint 由 planner 在拆解阶段写入，来源是模型输出——模型完全可能给
     * {@code 500} 这种离谱值。不钳制等于「一次拆解就能把单任务成本放大 25 倍」，
     * 且 {@code ChatModelToolLoop} 会老老实实跑满那么多轮 LLM 调用。</p>
     *
     * <p><b>默认 50</b>：比内部档（{@link #internalMaxIterations} 默认 20）留一倍余量，够「重代码
     * 任务」用；再往上就属于「任务本身该拆得更细」，不是加轮次能解决的。</p>
     *
     * <p><b>下限不用本配置</b>：见 {@code RuntimeTurnExecutor#MIN_MAX_ITERATIONS}——那个下限不是
     * 「运维偏好」而是循环体的结构性约束（收尾轮 off-by-one），不可配置化。</p>
     */
    private int maxIterationsHintCeiling = 50;

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
     * 单次 Agent Turn（一次业务面调用 / 外部路径）的取消截止时间（秒，REF-8.6 取消/超时树）。
     *
     * <p>自驱动取消源：到期后循环在下一个动作边界停止（不再发起新模型/工具调用），结果带
     * {@code finishReason=CANCELLED}，弥补此前「任一轮卡住只能事后靠外部超时回收」的缺口。
     * 默认 1800（30 分钟）是「正常绝不触发、异常必被拦住」的量级；{@code <= 0} = 不限。</p>
     *
     * <p><b>`MA-10` 改动 4 后只作用于业务面（2026-11-19 订正）</b>：子任务执行链
     * （{@code RuntimeTurnExecutor}）已<b>删除</b>本闸的装配——在那里它被
     * {@link #runningTimeoutMinutes} 遮蔽、从未开火，属死代码。业务面
     * （{@code BusinessSurfaceLoopRunner}，需求对话 / 评审）<b>保留</b>，因为那里没有
     * {@code agent_execution_record}、也就没有「无进展」兜底，本闸是它唯一的异常保护。</p>
     *
     * <p><b>不要再把它加回子任务链</b>：次数主 + 时间兜底的正确形态是<b>一道</b>兜底，
     * 留两道就会出现「为什么这个任务 12 分钟死、那个 40 分钟才死」这种解释不了的现象。</p>
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
     * <p>把拆解 / 子任务核验 / 终报告整合 / 终报告审查 4 个业务面从 {@code executeSync} 裸单次
     * {@code ChatModel} 调用接进同一份 {@code AgentLoop}（此前工具循环只存在于子任务执行链 {@code RuntimeTurnExecutor}）。
     * 置 {@code false} 或循环异常（抛异常 / {@code finishReason=ERROR}）时业务面自动回退裸单次，不阻断业务。
     * 默认 true（覆盖面清单 plan §10.6 已逐项裁定；mock 下 {@code MockChatModel} 不产工具调用，循环一轮即终态）。</p>
     */
    private boolean businessLoopEnabled = true;

    /**
     * 进循环的 {@code scene} 白名单（REF-8.5 覆盖面收敛，取值 = {@code AgentTask.context} 的 {@code scene} 键）。
     *
     * <p>默认 = plan §10.6 已裁定的 4 个进循环面（其余明确不进，该表即验收边界）。
     * 新增 scene 须按同判据（产出是否需查阅/校验外部证据）重新取证并登记决策，不得「顺便」扩面。</p>
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
     * 单次执行内摘要次数上限（REF-8.2.3 防抖动）。默认 2。
     *
     * <p>压缩净收益不保证（摘要可能比被压区间还长）：① 只在净减时接受（否则回滚原文）
     * ② 用本上限封顶，避免「每轮都试一次摘要」的反复调用。</p>
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

    /**
     * 「无进展」兜底阈值（分钟，`MA-10` 改动 3）。
     *
     * <p><b>取代 {@link #runningTimeoutMinutes} 的判定口径</b>：RUNNING 扫描从「start_time + 10 分钟」
     * （固定总时长）改为「{@code COALESCE(last_progress_at, start_time)} + 本阈值」（有没有在推进）。
     * 固定总时长把「慢但有效」与「卡死」判成同一件事——20 轮 × 慢工具 5 分钟超时 = 100 分钟，
     * 与 10 分钟毫无关系。</p>
     *
     * <p><b>默认 45 分钟</b>（{@code toolCallTimeoutMillis} 的 9 倍）：蓝图 §4 `MA-10` ⑦ 要求
     * 「必须比单工具超时明显更长（至少 3~4 倍）」，否则「一个正在跑第 2 轮、工具本就慢」的任务
     * 会被判无进展。取 9 倍是留出一轮内「多点工具调用 + 模型思考」的余量：单轮内即使连续三个
     * 工具各耗时接近上限，进展写点仍在工具边界更新，不会被误判。</p>
     *
     * <p><b>与 {@link #runningTimeoutMinutes} 的关系</b>：后者保留但不再参与 RUNNING 判定
     * （仅用于日志文案与外部路径说明），避免改动 4 收敛层级时漏掉读取方。</p>
     */
    private int noProgressTimeoutMinutes = 45;

    /**
     * 「无进展」检测开关（`MA-10` 改动 3 观察期纪律）。
     *
     * <p><b>默认 {@code false}（只 warn、不判超时）</b>：与 `MA-4` 第一段同一条纪律
     * 「先观察不改行为」。开启时 {@code ExecutionCompensationTask} 对命中的记录<b>只记日志与事件</b>，
     * 仍按旧的 {@code runningTimeoutMinutes} 判超时；观察一段拿到真实分布后再置 {@code true}
     * 让它真正接管（届时旧判据停用）。</p>
     *
     * <p>置 {@code true} 后：命中即判超时（旧判据不再生效）。</p>
     */
    private boolean noProgressDetectionEnforced = false;

    /** DB Poller 扫描周期（毫秒）。默认 1000 ms。 */
    private long pollerIntervalMs = 1000L;

    /** DB Poller 扫描孤儿阈值（秒）。超过该时间未被 Poller 触及的 PENDING 行视为孤儿。默认 60 秒。 */
    private int pollerOrphanThresholdSeconds = 60;

    /** DB Poller 单批扫描上限，避免扫到大量孤儿记录时阻塞调度线程。默认 20。 */
    private int pollerBatchSize = 20;

    /** DB Poller 是否启用。默认 true。 */
    private boolean pollerEnabled = true;

    /**
     * §4.1：PENDING 孤儿阈值（分钟）。默认 5。
     *
     * <p>子任务 status=PENDING 且 create_time 距今超过本阈值、且尚未创建 {@code agent_execution_record}
     * 的，视为「dispatch-mode=EVENT 主路径丢失」孤儿，由 {@code SubTaskPendingOrphanTask} 周期重派。
     * 依赖解锁瞬间「无空闲候选」致分发失败（pickPreferred 返回 null）的子任务同样呈现此态，需巡检兜底；
     * 扫描命中后循环内还有 {@code isReady} 依赖守卫跳过未就绪的合法 PENDING，故收窄阈值安全。</p>
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
     * <p>BLOCKED 是「重启撞上在途任务」与「执行者自报无能力」的落点，此前只有人工入口
     * （{@code reassignById}/{@code redispatchInProgress}），无人干预时下游依赖恒 PENDING、流水线停摆；
     * 开启后由 {@code SubTaskBlockedAutoRedispatchTask} 周期扫描自动重派。只触发既有重派链、不改状态机，
     * 仍受退避窗口 + 熔断约束（达 {@link AgentDispatchProperties#maxReassignAttempts} 转 DEAD_LETTER）。</p>
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
     * {@link com.helloai.common.constant.ManualInterventionReasons#EXECUTOR_REPORTED_BLOCKED}）纳入自动重派。默认 true。
     *
     * <p>其余 {@code manualIntervention} 原因码都表示「等人工拍板」，自动链一律不碰；只有自报阻塞表示
     * 「当前执行者做不到」，平台对此有确定处置 = 换执行者（plan §10.7.6「发现放内、处置放外」）。
     * 置 {@code false} ⇒ 自报阻塞一律等人工。</p>
     */
    private boolean blockedRedispatchSelfReportedEnabled = true;

    /**
     * 消费载体模式。默认 POLLER（MQ Consumer 主消费、Poller 仅扫孤儿兜底）。各模式语义见 {@link ConsumerMode}。
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
     * <p>方法名沿用旧「Poller 主消费」语义，实际指「MQ 主消费路径启用」、Poller 仅扫孤儿兜底
     * （保留名仅为兼容 {@code ExecutionCommandPoller} / {@code ExecutionDispatchValidator} 调用方）。</p>
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
