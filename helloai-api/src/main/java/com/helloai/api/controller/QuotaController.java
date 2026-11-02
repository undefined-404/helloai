package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.admin.QuotaWidgetConfig;
import com.helloai.common.base.R;
import com.helloai.core.system.quota.BalanceQueryService;
import com.helloai.core.system.quota.BalanceSnapshot;
import com.helloai.core.system.quota.QuotaWidgetSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 额度与用量监控（G-026，Q-7 / UX-1）。
 *
 * <p>端点列表：</p>
 * <ul>
 *   <li>GET /api/admin/quota/overview         聚合 4 家余额快照（读缓存，缓存未命中才实时抓）</li>
 *   <li>GET /api/admin/quota/widget-config    浮球开关 + 自动刷新间隔（运营设置）</li>
 *   <li>POST /api/admin/quota/refresh/{key}   手动实时刷新单家（跳过缓存）</li>
 * </ul>
 *
 * <p>{@code overview?fresh=false} 只读最近一次落库快照，不触网 —— 供「仅手动刷新」
 * 模式下打开页面时展示历史值。</p>
 *
 * <p>语义与前端浮球约定（设计 §8）：</p>
 * <ul>
 *   <li>返回顺序 = 注册顺序，deepseek / moonshot / minimax / bocha；通义无接口不出现（supports=false 直接跳过）</li>
 *   <li>{@code available=false}（source=FAILED）时不回填 0：{@code amount}/{@code quotaPercent} 为 null，
 *       前端据此显示「—」而非 0（设计 §2.3 经验 2）</li>
 *   <li>{@code source} 三态：LIVE（本次抓）/ CACHED（命中缓存）/ FAILED（失败无缓存）</li>
 * </ul>
 *
 * <p>权限：视图 {@code quota:view}；手动刷新属变更 {@code quota:edit}。</p>
 */
@RestController
@RequestMapping("/api/admin/quota")
@RequiredArgsConstructor
public class QuotaController {

    private final BalanceQueryService balanceQueryService;
    private final QuotaWidgetSettings quotaWidgetSettings;

    /**
     * 聚合 4 家余额快照。
     *
     * @param fresh true（默认）= 读缓存、未命中才实时抓；false = 只读落库快照，绝不触网
     */
    @SaCheckPermission("quota:view")
    @GetMapping("/overview")
    public R<List<BalanceSnapshot>> overview(
            @RequestParam(name = "fresh", defaultValue = "true") boolean fresh) {
        return R.ok(fresh ? balanceQueryService.overview() : balanceQueryService.cachedOverview());
    }

    /**
     * 浮球运行期配置：是否渲染 + 自动刷新间隔（0=仅手动）。
     *
     * <p>与 overview 同权限 {@code quota:view} 保护，前端不依赖 {@code config:view}
     * （该权限未绑 ADMIN，仅超管持 '*')。前端据此决定是否轮询及轮询周期。</p>
     */
    @SaCheckPermission("quota:view")
    @GetMapping("/widget-config")
    public R<QuotaWidgetConfig> widgetConfig() {
        return R.ok(new QuotaWidgetConfig(
                quotaWidgetSettings.isWidgetEnabled(),
                quotaWidgetSettings.refreshIntervalSeconds()));
    }

    /** 手动实时刷新单家（跳过缓存）。 */
    @SaCheckPermission("quota:edit")
    @PostMapping("/refresh/{key}")
    public R<BalanceSnapshot> refresh(@PathVariable("key") String key) {
        BalanceSnapshot snapshot = balanceQueryService.refresh(key);
        return R.ok(snapshot);
    }
}