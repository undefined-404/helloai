package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Planner 输入优化（PromptEnhancer）配置。
 *
 * <p>输入优化是 Planner Chat 的辅助能力：把用户当前输入改写为更清晰、结构化、
 * 适合后续 Planner / Coding Agent 理解的表达，不改变业务语义、不触发任何执行链路
 * （CODE_STYLE V2 §32.1）。功能开关与采样温度外置，便于运行期调整。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.planner.prompt-enhance")
public class PromptEnhancerProperties {

    /** 功能总开关；关闭后接口直接返回"功能未开启"提示。 */
    private boolean enabled = true;

    /**
     * 采样温度。输入优化是"保持语义 + 结构化改写"而非创作，
     * 取低值保证稳定：建议 0.1~0.3，默认 0.2。
     */
    private Double temperature = 0.2;
}
