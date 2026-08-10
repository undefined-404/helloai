package com.helloai.core.task.spec;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executor 回填协议解析器——从 executor 原始输出中提取 {@code EXECUTION_RECORD} 结构化块。
 *
 * <p>协议格式（executor Prompt 中要求输出）：</p>
 * <pre>
 * ---
 * ## EXECUTION_RECORD
 * SUMMARY: 实现了登录接口，包括参数校验、JWT生成、错误处理
 * KEY_DECISIONS:
 * - JWT有效期设为24小时
 * DOWNSTREAM_NOTES:
 * - 登录接口路径: POST /api/auth/login
 * DELIVERABLES:
 * - src/main/java/.../AuthController.java
 * VERIFICATION:
 * - 命令: mvn -pl helloai-core -am compile
 * - 输出: BUILD SUCCESS
 * - 结论: 通过
 * ---
 * </pre>
 *
 * <p>解析失败（未找到块/格式不完整）返回 null，不阻断主链路。
 * VERIFICATION 段为围栏协议证据字段：缺失时记录为空串并打 debug 日志，仅检测不拦截。</p>
 */
@Slf4j
public final class ExecutionRecordParser {

    private ExecutionRecordParser() {
    }

    /** EXECUTION_RECORD 块分隔符。 */
    private static final String BLOCK_START = "## EXECUTION_RECORD";
    private static final String BLOCK_END = "---";

    /** VERIFICATION 段标记（围栏协议证据段）。 */
    private static final String VERIFICATION_MARKER = "VERIFICATION:";

    /** 各段标题匹配。 */
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("^SUMMARY:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern KEY_DECISIONS_PATTERN = Pattern.compile(
            "KEY_DECISIONS:\\s*\\n((?:\\s*-\\s*.+\\n?)*)", Pattern.MULTILINE);
    private static final Pattern DOWNSTREAM_NOTES_PATTERN = Pattern.compile(
            "DOWNSTREAM_NOTES:\\s*\\n((?:\\s*-\\s*.+\\n?)*)", Pattern.MULTILINE);
    private static final Pattern DELIVERABLES_PATTERN = Pattern.compile(
            "DELIVERABLES:\\s*\\n((?:\\s*-\\s*.+\\n?)*)", Pattern.MULTILINE);
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*-\\s*(.+)$", Pattern.MULTILINE);

    /**
     * 从 executor 原始输出中解析 EXECUTION_RECORD 块。
     *
     * @param rawOutput executor 的完整原始输出文本，可为 null
     * @param subTaskId 当前子任务 ID
     * @param title     子任务标题
     * @param agentId   执行 Agent ID
     * @return 解析成功返回 ExecutionRecord；未找到块或格式不完整返回 null
     */
    public static ExecutionRecord parse(String rawOutput, Long subTaskId, String title, Long agentId) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        int startIdx = rawOutput.indexOf(BLOCK_START);
        if (startIdx < 0) {
            return null;
        }
        int endIdx = rawOutput.indexOf(BLOCK_END, startIdx + BLOCK_START.length());
        if (endIdx < 0) {
            endIdx = rawOutput.length();
        }
        String block = rawOutput.substring(startIdx, endIdx);

        // SUMMARY（必需）
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(block);
        if (!summaryMatcher.find()) {
            log.debug("EXECUTION_RECORD 块缺少 SUMMARY: subTaskId={}", subTaskId);
            return null;
        }
        String summary = summaryMatcher.group(1).trim();
        if (summary.isEmpty()) {
            return null;
        }

        ExecutionRecord.Builder builder = ExecutionRecord.builder()
                .subTaskId(subTaskId)
                .title(title)
                .agentId(agentId)
                .summary(summary);

        // KEY_DECISIONS（可选）
        Matcher kdMatcher = KEY_DECISIONS_PATTERN.matcher(block);
        if (kdMatcher.find()) {
            parseListItems(kdMatcher.group(1), builder::addKeyDecision);
        }

        // DOWNSTREAM_NOTES（可选）
        Matcher dnMatcher = DOWNSTREAM_NOTES_PATTERN.matcher(block);
        if (dnMatcher.find()) {
            parseListItems(dnMatcher.group(1), builder::addDownstreamNote);
        }

        // DELIVERABLES（可选）
        Matcher dlMatcher = DELIVERABLES_PATTERN.matcher(block);
        if (dlMatcher.find()) {
            parseListItems(dlMatcher.group(1), builder::addDeliverable);
        }

        // VERIFICATION（可选，围栏协议）：协议约定为块内最后一段，直接截取到块尾
        int vfIdx = block.indexOf(VERIFICATION_MARKER);
        if (vfIdx >= 0) {
            String verification = block.substring(vfIdx + VERIFICATION_MARKER.length()).trim();
            builder.verification(verification);
        } else {
            log.debug("EXECUTION_RECORD 块缺少 VERIFICATION（围栏证据信号）: subTaskId={}", subTaskId);
        }

        return builder.build();
    }

    /**
     * 解析列表项（每行 "- xxx"），回调 consumer。
     */
    private static void parseListItems(String text, java.util.function.Consumer<String> consumer) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher m = LIST_ITEM.matcher(text);
        while (m.find()) {
            String item = m.group(1).trim();
            if (!item.isEmpty()) {
                consumer.accept(item);
            }
        }
    }
}
