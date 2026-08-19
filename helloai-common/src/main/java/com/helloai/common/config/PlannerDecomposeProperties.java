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
}
