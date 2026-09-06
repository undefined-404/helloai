package com.helloai.api.dto.browser;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Browser 会话响应（N-003，C3 配套展示）。
 */
@Data
public class BrowserSessionResponse {

    private Long id;
    private Long agentId;
    private Long taskId;
    /** BEGIN / ACTIVE / CLOSED / FAILED。 */
    private String status;
    private String currentUrl;
    private String lastScreenshotRef;
    private OffsetDateTime beginTime;
    private OffsetDateTime closeTime;
    private OffsetDateTime createTime;
}
