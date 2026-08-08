package com.helloai.api.dto.admin;

import lombok.Data;

/**
 * 平台级 Provider 设置更新请求（baseUrl / defaultModel 均可选；传空表示清除覆盖，回到 yml 默认）。
 */
@Data
public class ProviderSettingsRequest {

    /** Base URL（可选）。 */
    private String baseUrl;

    /** 默认模型（可选）。 */
    private String defaultModel;
}
