package com.helloai.core.agent.runtime.sandbox;

/**
 * 沙箱命令统一执行结果（S-7，沙箱执行链设计方案 §3.3）。
 *
 * <p>对齐 octop-harness {@code ExecuteResponse(output, exit_code, truncated)} 三字段语义：
 * 超时 / 失败 / SDK 异常都<b>结构化返回</b>（不抛异常），由消费方（内部 LLM 自检 /
 * Reviewer 验证）读取后自行判定 pass/fail——沙箱是「证据提供者」，不是「判定者」。</p>
 *
 * @param output    命令输出（截断后；SDK 异常时 = 错误描述）
 * @param exitCode  命令退出码；<b>124 = 命令超时</b>（对齐 octop-harness），SDK 异常 = -1
 * @param truncated 输出是否因超上限被截断（true 时 output 不完整，消费方不得据残缺输出下结论）
 */
public record ExecuteResponse(String output, int exitCode, boolean truncated) {

    /** 命令超时退出码（对齐 octop-harness command_timeout 语义）。 */
    public static final int EXIT_CODE_TIMEOUT = 124;

    /** SDK 调用异常退出码（结构化失败，非命令真实退出码）。 */
    public static final int EXIT_CODE_ERROR = -1;

    /** 命令执行成功且输出未被截断（可作证据的充分条件；判定仍由消费方负责）。 */
    public boolean succeeded() {
        return exitCode == 0 && !truncated;
    }
}
