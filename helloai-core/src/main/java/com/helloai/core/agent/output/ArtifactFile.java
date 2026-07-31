package com.helloai.core.agent.output;

/**
 * 执行产出解析出的单个待物化文件（内容为文本，方案2 当前只产出文本产物）。
 */
public record ArtifactFile(String fileName, String mimeType, String content) {
}
