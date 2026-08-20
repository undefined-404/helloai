package com.helloai.common.constant;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 接入类型。
 *
 * <p>对应 CLI_CLIENT / API_KEY_LLM / WEB_BROWSER 三类 Agent。
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
     * 当前 accessType 是否仍使用 `agent.api_key` 作为对外鉴权工牌。
     *
     * <p>当前仅 CLI_CLIENT 直接通过 MCP / HTTP 持工牌接入平台。</p>
     */
    public boolean usesConsumerTokenAuth() {
        return this == CLI_CLIENT;
    }

    /**
     * 当前 accessType 是否要求平台从 `credential_vault` 读取托管凭证。
     *
     * <p>/约束：只有 API_KEY_LLM 使用托管凭证；
     * `agent.api_key` 对它只保留工牌语义，不再承载真实 LLM Secret。</p>
     */
    public boolean usesCredentialVault() {
        return this == API_KEY_LLM;
    }

    /**
     * 当前 accessType 是否依赖运行时连接存活（心跳/在线态）来参与调度。
     *
     * <p>当前只有 CLI_CLIENT 需要由心跳驱动 online_status；
     * API_KEY_LLM / WEB_BROWSER 走平台侧触发，不应因默认 OFFLINE 而被调度器误判为不可分配。</p>
     */
    public boolean requiresRuntimeLiveness() {
        return this == CLI_CLIENT;
    }

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
        // 模态理解能力取决于底层模型而非接入通道，三类统一默认 false，注册时可覆盖
        defaults.put(CAP_SUPPORTS_IMAGE_UNDERSTANDING, false);
        defaults.put(CAP_SUPPORTS_AUDIO_UNDERSTANDING, false);
        defaults.put(CAP_SUPPORTS_VIDEO_UNDERSTANDING, false);
        return defaults;
    }

    /** 能力键：底层模型具备图片理解（多模态）。 */
    public static final String CAP_SUPPORTS_IMAGE_UNDERSTANDING = "supportsImageUnderstanding";
    /** 能力键：底层模型具备音频理解（多模态）。 */
    public static final String CAP_SUPPORTS_AUDIO_UNDERSTANDING = "supportsAudioUnderstanding";
    /** 能力键：底层模型具备视频理解（多模态）。 */
    public static final String CAP_SUPPORTS_VIDEO_UNDERSTANDING = "supportsVideoUnderstanding";
}
