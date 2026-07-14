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
     * <p>定义"主消费"从哪条路径走，与架构设计参考 §5.2 阶段二「DB Poller 主线化」对齐。</p>
     */
    public enum ConsumerMode {
        /**
         * 事件消费：
         * {@code @Async + @TransactionalEventListener} 作为实时主路径，
         * DB Poller 仅扫描孤儿 PENDING 作为兜底恢复。
         */
        EVENT,
        /**
         * Poller 主消费（默认）：
         * 命令创建后不发布本地事件，主消费完全由 DB Poller 周期扫描所有 PENDING 记录推进；
         * 消费载体不再依赖 Spring 事务事件，跨进程/跨实例可独立扩容。
         */
        POLLER,
        /**
         * 双消费：事件主消费与 Poller 同时运行；
         * Poller 扫描所有 PENDING，由 Consumer 内部 CAS markRunning 保证幂等，
         * 可作为 EVENT→POLLER 的过渡阶段。
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

    /** 是否为 Poller 主消费模式（POLLER 或 BOTH 都算）。 */
    public boolean isPollerMain() {
        return consumerMode == ConsumerMode.POLLER || consumerMode == ConsumerMode.BOTH;
    }

    /** 是否为事件主消费模式（EVENT 或 BOTH 都算）。 */
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
