package com.helloai.core.agent.output;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 执行产出解析器（方案2 当前形态：纯文本 → 单 Markdown 文件）。
 *
 * <p>lastExecution.output 目前是执行 Agent 返回的完整 Markdown 文本，
 * 解析规则为：非空产出整体作为一个 {@code .md} 文件，文件名取子任务标题
 * 清洗后拼接；空白产出返回空结果、不物化。</p>
 *
 * <p>方案3（LLM manifest 多文件协议）落地后，本类将扩展为
 * "先尝试解析 manifest JSON，失败回退纯文本单文件"，调用方无感。</p>
 */
@Component
public class ExecutionOutputParser {

    static final String MARKDOWN_MIME = "text/markdown";

    /**
     * 解析执行产出为待物化文件列表。
     *
     * @param subTaskTitle 子任务标题（用于生成文件名，可空）
     * @param output       lastExecution.output 原文（可空）
     */
    public ParsedOutput parse(String subTaskTitle, String output) {
        if (output == null || output.isBlank()) {
            return ParsedOutput.empty();
        }
        String fileName = buildFileName(subTaskTitle);
        return new ParsedOutput(List.of(new ArtifactFile(fileName, MARKDOWN_MIME, output)));
    }

    /** 标题清洗为文件名：去文件系统保留字符，空白兜底 output，限长 60 后加 .md。 */
    static String buildFileName(String subTaskTitle) {
        String base = subTaskTitle != null ? subTaskTitle.trim() : "";
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (base.isBlank()) {
            base = "output";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + ".md";
    }
}
