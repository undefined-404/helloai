package com.helloai.core.agent.output;

import java.util.List;

/**
 * 执行产出解析结果：files 为待物化文件列表（可能为空，表示无可物化内容）；
 * displayText 为写入 lastExecution.output 与对话流的正文（方案3 起与物化文件分离：
 * 多文件形态下为 manifest summary + 文件概览 + 保留的 EXECUTION_RECORD 尾部，避免对话流刷屏）。
 */
public record ParsedOutput(List<ArtifactFile> files, String displayText) {

    /** 兼容便捷构造器：displayText 置空，表示无可展示正文。 */
    public ParsedOutput(List<ArtifactFile> files) {
        this(files, null);
    }

    public static ParsedOutput empty() {
        return new ParsedOutput(List.of(), "");
    }

    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}
