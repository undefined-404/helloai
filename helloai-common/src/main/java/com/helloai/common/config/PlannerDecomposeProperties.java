package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Planner 任务拆解异步线程池配置。
 *
 * <p>拆解走"提交即返回 + 异步执行"后，LLM 调用运行在专用线程池
 * {@code plannerDecomposeExecutor} 上（Bean 装配见 start 模块
 * {@code PlannerDecomposeExecutorConfig}），本类提供线程池参数外置配置
 * （CODE_STYLE §15.4 线程池参数化）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.planner.decompose")
public class PlannerDecomposeProperties {

    /** 核心线程数。拆解为 LLM IO 密集型，小核心池即可。 */
    private int corePoolSize = 2;

    /** 最大线程数。与核心线程数一起控制同时进行的拆解上限。 */
    private int maxPoolSize = 4;

    /** 等待队列容量。队列满后按 AbortPolicy 拒绝，前端收到"排队已满"友好提示。 */
    private int queueCapacity = 20;

    /**
     * PLANNING 超时回收阈值（分钟）。
     *
     * <p>任务进入 PLANNING 后若 update_time 超过本阈值仍无草案产出，
     * 由 PlanningTimeoutTask 回退 PENDING 并记录 timeline。覆盖异步线程
     * 丢失（JVM 重启/异常退出）导致的永久卡 PLANNING 场景。
     * 最坏拆解耗时约 4 分钟（排队 60s + 连接 5s + 读取 180s），默认 10 分钟余量充足。</p>
     */
    private int planningTimeoutMinutes = 10;

    /**
     * 展开深度上限（设计 §7「深度限制按 `task_archetype` 分档，用户已拍板」）。
     *
     * <p><b>为什么分档而不写死一个数</b>：工程任务天然多层（模块 → 接口 → 实现），文档细到
     * 「章节 → 小节」就够，决策类只需拆到「要拍哪几个板」。同一个上限套所有原型，要么把
     * 工程任务拴死，要么让决策任务拆出无意义的三层。</p>
     *
     * <p><b>为什么 {@code defaultDepth} 要显式配置</b>：{@code TaskArchetype.parse} 已经把
     * null / 非法值回落成 {@code BUILD}，若深度档位再用「代码里的魔法默认 2」，
     * 会出现「BUILD 任务因漏配拿到 2 层」的静默降级——降级不报错，只是不让用户拆，
     * 排查时看不出是配置漏了还是真到上限。</p>
     */
    private MaxExpandDepth maxExpandDepth = new MaxExpandDepth();

    /** 展开深度分档（{@code helloai.planner.decompose.max-expand-depth.*}）。 */
    @Data
    public static class MaxExpandDepth {

        /** 未枚举到的原型（现有 {@code DATA} / {@code OPERATE}）与未来新增档位的上限。 */
        private int defaultDepth = 2;

        /** 工程类：与全局上限一致，最深「根 + 2 层」（`AD-6` 由 3 降为 2）。 */
        private int build = 2;

        /** 文档类：最深 {@code 202611080002-001-001}（再细是写作组织，不是任务拆分）。 */
        private int document = 2;

        /** 调研类：同上（再深就该先收敛问题）。 */
        private int research = 2;

        /** 决策类：最深 {@code 202611080002-001}（只需拆到「要拍哪几个板」）。 */
        private int decide = 1;

        /**
         * 取某原型的展开上限。
         *
         * <p>null 按 {@link com.helloai.common.constant.TaskArchetype#DEFAULT()}（= BUILD）处理，
         * 与上游 {@code TaskArchetype.parse} 的回落口径一致——两处口径若不同，
         * 「任务被判成 BUILD」与「深度按 default 算」会分叉。</p>
         */
        public int limitOf(com.helloai.common.constant.TaskArchetype archetype) {
            com.helloai.common.constant.TaskArchetype target =
                    archetype != null ? archetype : com.helloai.common.constant.TaskArchetype.DEFAULT();
            return switch (target) {
                case BUILD -> build;
                case DOCUMENT -> document;
                case RESEARCH -> research;
                case DECIDE -> decide;
                default -> defaultDepth;
            };
        }
    }
}
