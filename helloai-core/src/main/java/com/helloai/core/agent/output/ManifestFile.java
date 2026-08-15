package com.helloai.core.agent.output;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * manifest 单文件项（方案3）：name 为文件名（可带后缀，解析器按后缀推断 mimeType），
 * type 为可选 MIME 类型声明，content 为文件正文。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestFile(String name, String type, String content) {
}
