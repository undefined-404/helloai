package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台内 Agent 执行链配置。
 *
 * <p>T4/T5 默认启用 mock 模式，保证本地无需外部 LLM Key 也能稳定验证最小闭环。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.execution")
public class AgentExecutionProperties {

    /**
     * 执行命令消费载体模式。
     *
     * <p><b>T5 起重塑语义（与差距表 N6 + 架构参考 §5.1 阶段一拍板对齐）</b>：
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

    /** 是否启用稳定 mock 模式。默认 true。 */
    private boolean mockMode = true;

    /** real 模式是否强制要求 vault 已绑定凭证。默认 false（先兼容全局 Provider 配置）。 */
    private boolean requireVault = false;

    /** mock provider 名称。 */
    private String provider = "mock";

    /** mock model 名称。 */
    private String model = "helloai-mock-executor";

    /** mock 前缀，便于联调时快速识别结果来源。 */
    private String mockResponsePrefix = "[mock-executor]";

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
     * v2.6 §4.1 新增（2026-07-20）：PENDING 孤儿阈值（分钟）。
     *
     * <p>子任务 status=PENDING 且 create_time 距今超过本阈值、且尚未创建
     * {@code agent_execution_record} 记录的，视为“dispatch-mode=EVENT 主路径丢失”
     * 的孤儿，由 {@code SubTaskPendingOrphanTask} 周期重派。默认值 30 分钟。</p>
     *
     * <p>为什么 30 分钟比 ExecutionCompensationTask 的 5 分钟更大？因为
     * ExecutionCommandPoller 实际上可以租 60 秒间隔扫描已建 record 的孤儿 PENDING；
     * PENDING 但无 record 的孤儿是真正的“主路径丢失”，需要更宽阈值容许重试。</p>
     */
    private int pendingOrphanThresholdMinutes = 30;

    /**
     * v2.6 §4.1 新增（2026-07-20）：PENDING 孤儿巡检周期（毫秒）。默认 60000ms=1 分钟。
     */
    private long pendingOrphanScanIntervalMs = 60000L;

    /**
     * v2.6 §4.1 新增（2026-07-20）：PENDING 孤儿巡检单批上限。默认 50 条，防止调
     * 度线程被批量重派阻塞。
     */
    private int pendingOrphanBatchSize = 50;

    /**
     * v2.6 §4.1 新增（2026-07-20）：PENDING 孤儿巡检是否启用。默认 true。
     */
    private boolean pendingOrphanEnabled = true;

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
     * <p>T5 起重塑语义：POLLER/BOTH 模式都对应"MQ Consumer 作为主消费路径"，
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
