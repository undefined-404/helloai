package com.helloai.core.agent.output;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import com.helloai.core.system.storage.ArtifactStorage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行产出解析器（方案2 纯文本单文件 + 方案3 LLM manifest 多文件协议）。
 *
 * <p>解析规则（先结构化后降级）：非空产出先尝试解析 manifest JSON（```json 围栏包裹的
 * {@code {"summary","files"}} 对象），命中且 files 非空时按多文件物化，displayText 取
 * summary + 文件概览 + JSON 之后的尾部文本（EXECUTION_RECORD 回填块）；未命中、files 空、
 * JSON 非法或任何异常一律降级为纯文本单 .md（现状兜底），调用方无感。</p>
 */
@Component
public class ExecutionOutputParser {

    static final String MARKDOWN_MIME = "text/markdown";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
        ParsedOutput manifest = tryParseManifest(output);
        if (manifest != null) {
            return manifest;
        }
        String fileName = buildFileName(subTaskTitle);
        return new ParsedOutput(List.of(new ArtifactFile(fileName, MARKDOWN_MIME, output)), output);
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

    /**
     * 尝试解析 manifest 多文件协议。
     *
     * @return 结构化解析结果；未命中/降级时返回 null，由调用方回退纯文本
     */
    private ParsedOutput tryParseManifest(String raw) {
        try {
            String json = stripJsonObject(raw);
            if (json == null || !json.startsWith("{")) {
                return null;
            }
            json = LlmJsonSanitizer.fixInvalidEscapes(json);
            Manifest manifest = objectMapper.readValue(json, Manifest.class);
            if (manifest == null || manifest.files() == null || manifest.files().isEmpty()) {
                return null;
            }
            List<ArtifactFile> files = new ArrayList<>(manifest.files().size());
            int index = 0;
            for (ManifestFile file : manifest.files()) {
                String name = ArtifactStorage.sanitizeFileName(file.name());
                if ("output.md".equals(name)) {
                    // sanitize 兜底产物：原名为空/纯点号等，改按序号兜底避免多文件同名覆盖
                    name = "output-" + (index + 1) + ".txt";
                }
                String mime = resolveMimeType(file.type(), name);
                String content = file.content() != null ? file.content() : "";
                files.add(new ArtifactFile(name, mime, content));
                index++;
            }
            return new ParsedOutput(files, buildDisplayText(manifest.summary(), files, extractTail(raw)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * displayText = manifest.summary + 文件概览 + JSON 之后的尾部文本（EXECUTION_RECORD 回填块等），
     * 保证对话流不刷文件正文、回填块不丢。
     */
    private String buildDisplayText(String summary, List<ArtifactFile> files, String tail) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append(summary.trim()).append("\n\n");
        }
        sb.append("## 产出文件概览\n");
        for (ArtifactFile file : files) {
            sb.append("- ").append(file.fileName()).append("\n");
        }
        if (tail != null && !tail.isBlank()) {
            sb.append("\n").append(tail.trim());
        }
        return sb.toString().trim();
    }

    /** 剥离 markdown 代码块围栏，兜底截取首尾花括号之间的 JSON 对象（与核验侧 stripToJsonObject 同款）。 */
    private String stripJsonObject(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("{")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    /** 提取 JSON 代码块/对象之后的尾部文本（EXECUTION_RECORD 等非 JSON 内容）。 */
    private String extractTail(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd > 0) {
                return trimmed.substring(fenceEnd + 3).trim();
            }
        }
        int end = trimmed.lastIndexOf('}');
        if (end >= 0 && end < trimmed.length() - 1) {
            return trimmed.substring(end + 1).trim();
        }
        return "";
    }

    /** MIME 类型：manifest 显式声明优先，缺失时按文件名后缀推断，未知回退 octet-stream。 */
    private String resolveMimeType(String declaredType, String name) {
        if (declaredType != null && !declaredType.isBlank()) {
            return declaredType.trim();
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return MARKDOWN_MIME;
        }
        if (lower.endsWith(".py")) {
            return "text/x-python";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".java")) {
            return "text/x-java-source";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "text/yaml";
        }
        if (lower.endsWith(".sql")) {
            return "application/sql";
        }
        if (lower.endsWith(".sh")) {
            return "text/x-shellscript";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript";
        }
        if (lower.endsWith(".ts")) {
            return "text/typescript";
        }
        return "application/octet-stream";
    }
}
