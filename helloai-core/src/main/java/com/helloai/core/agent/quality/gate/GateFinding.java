package com.helloai.core.agent.quality.gate;

/**
 * 单条闸门发现项：级别 + 规则名 + 可直接指导返工的描述。
 *
 * <p>字段与既有 {@code FinalReportFidelityChecker.Violation}
 * （{@code severity / rule / detail}）<b>逐字对齐</b>，以保证接线后渲染输出零变化。</p>
 */
public record GateFinding(GateSeverity severity, String rule, String detail) {
}
