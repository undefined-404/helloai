package com.helloai.core.agent.output;

import java.util.List;

/**
 * 执行产出解析结果：files 为待物化文件列表（可能为空，表示无可物化内容）。
 */
public record ParsedOutput(List<ArtifactFile> files) {

    public static ParsedOutput empty() {
        return new ParsedOutput(List.of());
    }

    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}
