package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 质量画像与反馈回路统一配置（反馈回路第 1 层）。
 *
 * <p>与 {@link AgentDispatchProperties#qualityWeight} 分工：
 * 本类承载质量回灌链路自身的独立参数（LLM 语义对比通道与超时），
 * 调度侧权重仍由 helloai.dispatch.quality-weight 单一来源控制。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.quality")
public class AgentQualityProperties {

    /**
     * executorDoneIssues LLM 语义对比的独立超时（秒）。
     *
     * <p>评估在结果回报事务提交后的异步链路执行，不阻塞执行/核验主链路；
     * 超时或失败一律降级跳过回填（best-effort）。默认 30 秒。</p>
     */
    private int executorDoneIssuesTimeoutSeconds = 30;

    /**
     * executorDoneIssues 语义对比指定使用的平台 Provider（可选）。
     *
     * <p>空 = 自动选择第一个"已启用且配置了平台级凭证"的 Provider；
     * 指定后仅使用该 Provider（无凭证则跳过，不回退其它）。</p>
     */
    private String executorDoneIssuesProvider;
}
