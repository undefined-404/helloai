package com.helloai.common.constant;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 接入类型。
 *
 * <p>对应 v2.4 路线图 N1：CLI_CLIENT / API_KEY_LLM / WEB_BROWSER 三类 Agent。
 * 每个 accessType 有默认 capabilities，注册时允许 Agent 实例独立覆盖。</p>
 */
public enum AgentAccessType {

    /** A 类：Qoder / Trae / Codex / Claude Code，MCP-over-SSE */
    CLI_CLIENT,

    /** B 类：OpenAI / Claude / DeepSeek API，平台侧 @Async 触发 */
    API_KEY_LLM,

    /** C 类：网页版 AI（DeepSeek / Kimi / Minimax），Playwright 桥接 */
    WEB_BROWSER;

    /**
     * 返回 accessType 对应的默认 capabilities（注册时可独立覆盖）。
     *
     * @return 不可变 Map，键为能力名，值为默认布尔/数值
     */
    public Map<String, Object> defaultCapabilities() {
        Map<String, Object> defaults = new HashMap<>();
        switch (this) {
            case CLI_CLIENT -> {
                defaults.put("supportsPull", true);
                defaults.put("supportsSSE", true);
                defaults.put("supportsMCP", true);
                defaults.put("supportsArtifactUpload", true);
                defaults.put("maxConcurrentTasks", 3);
                defaults.put("isSlow", false);
            }
            case API_KEY_LLM -> {
                defaults.put("supportsPull", false);
                defaults.put("supportsSSE", false);
                defaults.put("supportsMCP", false);
                defaults.put("supportsArtifactUpload", false);
                defaults.put("maxConcurrentTasks", 5);
                defaults.put("isSlow", false);
            }
            case WEB_BROWSER -> {
                defaults.put("supportsPull", false);
                defaults.put("supportsSSE", false);
                defaults.put("supportsMCP", false);
                defaults.put("supportsArtifactUpload", true);
                defaults.put("maxConcurrentTasks", 1);
                defaults.put("isSlow", true);
            }
        }
        return defaults;
    }
}
