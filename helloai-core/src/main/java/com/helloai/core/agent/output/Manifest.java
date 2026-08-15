package com.helloai.core.agent.output;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * LLM manifest 多文件产出协议 DTO（方案3）。
 *
 * <p>执行 Agent 可在返回文本开头选择输出 ```json 代码块包裹的 manifest：
 * {@code {"summary": "...", "files": [{"name": "README.md", "type": "text/markdown", "content": "..."}]}}；
 * 解析器命中且 files 非空时按多文件物化，否则整体降级为纯文本单 .md。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(String summary, List<ManifestFile> files) {
}
