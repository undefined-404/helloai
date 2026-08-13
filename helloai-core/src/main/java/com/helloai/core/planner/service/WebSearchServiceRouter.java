package com.helloai.core.planner.service;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 联网搜索服务路由（业务层唯一注入入口）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>业务侧（{@code RequirementClarifyService}）只依赖 {@link WebSearchService} 接口，
 *       真正拿到的是本 Router（{@code @Primary}），不需要知道有几种供应商实现。</li>
 *   <li>Spring 启动期会按 {@code helloai.web-search.provider} 激活一个具体实现
 *       （{@link BochaWebSearchServiceImpl} / {@link TavilyWebSearchServiceImpl}），本 Router
 *       通过 {@code ObjectProvider} 拿到当前已激活的候选（可能为 0 / 1 / N）。</li>
 *   <li>总开关 {@code enabled=false} 或当前没有匹配实现 → 所有方法短路返回空列表，
 *       绝不抛异常。需求澄清主流程不依赖搜索可用性。</li>
 *   <li>多实现同时存在（一般不会发生，仅作防御）时按 provider 配置选定一个；
 *       选定实现失败时不再尝试 failover（避免长尾延迟），仅记日志。</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
public class WebSearchServiceRouter implements WebSearchService {

    private final WebSearchProperties properties;
    private final ObjectProvider<WebSearchService> candidates;

    public WebSearchServiceRouter(WebSearchProperties properties,
                                  ObjectProvider<WebSearchService> candidates) {
        this.properties = properties;
        this.candidates = candidates;
    }

    @Override
    public String provider() {
        return "router->" + resolvedProvider();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        if (!properties.isEnabled()) {
            log.debug("联网搜索总开关关闭（helloai.web-search.enabled=false），跳过本次搜索");
            return List.of();
        }
        WebSearchService delegate = resolve();
        if (delegate == null) return List.of();
        return delegate.search(query, maxResults);
    }

    /** 解析当前应使用的供应商实现，候选为 0 或与 provider 配置不匹配时返回 null。 */
    private WebSearchService resolve() {
        List<WebSearchService> available = candidates.orderedStream().toList();
        if (available.isEmpty()) {
            log.debug("无任何 WebSearchService 激活（请检查 helloai.web-search.provider / *-api-key）");
            return null;
        }
        String want = properties.getProvider() == null ? "bocha" : properties.getProvider().trim().toLowerCase();
        for (WebSearchService s : available) {
            if (want.equals(s.provider())) return s;
        }
        // 没匹配上（典型场景：provider=tavily 但 apiKey 缺失 → 0 候选；或配置错误）
        if (available.size() == 1) return available.get(0);
        log.warn("联网搜索 provider={} 没有匹配到任何已激活实现，回退跳过", want);
        return null;
    }

    private String resolvedProvider() {
        WebSearchService d = resolve();
        return d == null ? "none" : d.provider();
    }
}
