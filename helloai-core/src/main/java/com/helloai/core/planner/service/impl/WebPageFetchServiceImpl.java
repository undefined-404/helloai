package com.helloai.core.planner.service.impl;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.service.WebPageFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页直取服务实现（V43，纯 JDK 无额外依赖）。
 *
 * <p>流程：GET 目标 URL（跟随重定向）→ 限流读取响应体（{@value #MAX_BODY_BYTES} 字节上限，
 * 防超大页面拖垮内存）→ 仅接受文本类 Content-Type → 轻量 HTML 转纯文本
 * （剔除 script/style/noscript 块 → 去标签 → 解码常见实体 → 折叠空白）→
 * 提取 &lt;title&gt; → 正文按 {@code urlFetchMaxTextChars} 截断。</p>
 *
 * <p>局限（已知代价，不引 jsoup）：正则式 HTML 解析对极端嵌套/畸形标签不完备，
 * 但对本场景（抓站点介绍/文档页给 LLM 作资料）足够；SPA 空壳页正文极少，
 * V44 元数据兜底：正文为空时仍提取 &lt;title&gt;/meta 描述/og 标签拼作最低限度资料
 * （{@code metaOnly=true}），而非整体丢弃（用户实测 open.maic.chat 空壳页 textChars=0）。</p>
 *
 * <p>失败语义：捕获所有异常返回 ok=false 记录，不抛出（{@link WebPageFetchService} 契约）。</p>
 */
@Slf4j
@Service
public class WebPageFetchServiceImpl implements WebPageFetchService {

    /** 响应体读取上限（1MB），超出丢弃，防超大页面。 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /** script/style/noscript 块（含内容）整体剔除。 */
    private static final Pattern NOISE_BLOCK = Pattern.compile(
            "<(script|style|noscript|svg|head)[^>]*>[\\s\\S]*?</\\1\\s*>", Pattern.CASE_INSENSITIVE);

    /** 注释块。 */
    private static final Pattern COMMENT_BLOCK = Pattern.compile("<!--[\\s\\S]*?-->");

    /** 剩余标签。 */
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** 连续空白折叠为单空格。 */
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n]+");

    /** title 提取。 */
    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);

    /** meta 描述提取（V44：属性顺序双向兼容 name/property 与 content 先后）。 */
    private static final Pattern[] META_DESC = {
            Pattern.compile("<meta[^>]+name=[\"']description[\"'][^>]*content=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[^>]*name=[\"']description[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+property=[\"']og:description[\"'][^>]*content=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[^>]*property=[\"']og:description[\"']", Pattern.CASE_INSENSITIVE)
    };

    /** og:site_name 提取（V44，属性顺序双向兼容）。 */
    private static final Pattern[] META_SITE_NAME = {
            Pattern.compile("<meta[^>]+property=[\"']og:site_name[\"'][^>]*content=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']*)[^>]*property=[\"']og:site_name[\"']", Pattern.CASE_INSENSITIVE)
    };

    private final WebSearchProperties properties;
    private final HttpClient httpClient;

    public WebPageFetchServiceImpl(WebSearchProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getUrlFetchTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public WebPageContent fetch(String url) {
        if (url == null || url.isBlank()) {
            return failed(url, "URL 为空");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getUrlFetchTimeoutMs()))
                    // V44：浏览器风格 UA（原「HelloAI-WebPageFetch」自曝爬虫身份易被反爬拦截）
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                return failed(url, "HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank() && !contentType.contains("text/")
                    && !contentType.contains("application/xhtml") && !contentType.contains("application/json")) {
                return failed(url, "非文本内容类型: " + truncate(contentType, 60));
            }
            String body = readLimited(response.body());
            String text = htmlToText(body);
            String title = extractTitle(body);
            if (text.isBlank()) {
                // V44 SPA 空壳兜底：正文为空但 title/meta 描述存在时，拼作最低限度资料
                // （站点名+一句描述对 LLM 仍优于零信息），而非整体丢弃
                String metaText = salvageMetaText(body, title);
                if (metaText.isBlank()) {
                    return failed(url, "页面正文为空且无元数据（可能是 SPA 空壳或反爬拦截）");
                }
                log.info("网页直取 SPA 空壳元数据兜底: url={}, metaChars={}", url, metaText.length());
                text = metaText;
                if (title == null) title = hostOf(url);
                int metaMax = properties.getUrlFetchMaxTextChars();
                return WebPageContent.builder()
                        .url(url)
                        .ok(true)
                        .metaOnly(true)
                        .title(title)
                        .text(text.length() <= metaMax ? text : text.substring(0, metaMax) + "…")
                        .build();
            }
            if (title == null) title = hostOf(url);
            int max = properties.getUrlFetchMaxTextChars();
            return WebPageContent.builder()
                    .url(url)
                    .ok(true)
                    .title(title)
                    .text(text.length() <= max ? text : text.substring(0, max) + "…")
                    .build();
        } catch (Exception e) {
            log.warn("网页直取失败（已降级，不阻断澄清主流程）: url={}, err={}", url, e.getMessage());
            return failed(url, truncate(String.valueOf(e.getMessage()), 120));
        }
    }

    /** 限流读取响应体到 {@value #MAX_BODY_BYTES} 字节，UTF-8 解码。 */
    private static String readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            int allow = Math.min(n, MAX_BODY_BYTES - total);
            if (allow <= 0) break;
            buf.write(chunk, 0, allow);
            total += allow;
            if (total >= MAX_BODY_BYTES) break;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** 轻量 HTML 转纯文本：剔噪块 → 去注释 → 去标签 → 解码实体 → 折叠空白。 */
    static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = NOISE_BLOCK.matcher(html).replaceAll(" ");
        s = COMMENT_BLOCK.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** 常见 HTML 实体解码（覆盖高频子集，不做全量表）。 */
    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String extractTitle(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) return null;
        String t = decodeEntities(WHITESPACE.matcher(m.group(1)).replaceAll(" ")).trim();
        return t.isBlank() ? null : truncate(t, 120);
    }

    /**
     * V44 SPA 空壳元数据兜底：从原始 HTML 提取 meta 描述 / og:site_name，
     * 与 title 拼为最低限度资料文本；全部缺失时返回空串（仍按失败处理）。
     */
    static String salvageMetaText(String html, String title) {
        String desc = firstMatch(html, META_DESC);
        String siteName = firstMatch(html, META_SITE_NAME);
        if (desc == null && siteName == null && (title == null || title.isBlank())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("站点名称：").append(title);
        }
        if (siteName != null && !siteName.equals(title) && !siteName.isBlank()) {
            if (sb.length() > 0) sb.append("；");
            sb.append("站点：").append(siteName);
        }
        if (desc != null && !desc.isBlank()) {
            if (sb.length() > 0) sb.append("；");
            sb.append("站点描述：").append(truncate(desc, 300));
        }
        return sb.toString();
    }

    /** 依次尝试多个模式，返回首个命中组的解码后文本（去空白折叠），均未命中返回 null。 */
    private static String firstMatch(String html, Pattern[] patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String v = decodeEntities(WHITESPACE.matcher(m.group(1)).replaceAll(" ")).trim();
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static WebPageContent failed(String url, String reason) {
        return WebPageContent.builder()
                .url(url == null ? "" : url)
                .ok(false)
                .reason(reason)
                .title("")
                .text("")
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
