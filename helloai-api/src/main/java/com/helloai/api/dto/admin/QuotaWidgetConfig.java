package com.helloai.api.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 额度浮球运行期配置（G-026 UX-1，对应 {@code GET /api/admin/quota/widget-config}）。
 *
 * <p>{@code enabled=false} 时前端不渲染浮球也不发请求；{@code refreshIntervalSeconds=0}
 * 表示仅手动刷新，正数（60~3600）表示自动刷新间隔。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaWidgetConfig {

    private boolean enabled;

    private int refreshIntervalSeconds;
}
