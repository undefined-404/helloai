package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentCreateRequest {
    private String name;
    private String role;

    /**
     * 接入类型（`G-040`）：`CLI_CLIENT` / `API_KEY_LLM` / `WEB_BROWSER`。
     *
     * <p>留空则由服务端推导——有 {@code modelType} 判 {@code API_KEY_LLM}，否则
     * {@code CLI_CLIENT}。**留空 ≠ 取 {@code CLI_CLIENT}**：建 LLM 执行器时不传本字段
     * 也会被推导成 {@code API_KEY_LLM}，避免「CLI 客户端绑着平台托管模型」的非法组合。</p>
     */
    private String accessType;

    private String modelType;
    private Map<String, Object> modelConfig;
    private String remark;

    /** 技能标签列表：按模型能力驱动落库，不传时按 accessType + 模型能力推导。 */
    private List<String> skills;
}
