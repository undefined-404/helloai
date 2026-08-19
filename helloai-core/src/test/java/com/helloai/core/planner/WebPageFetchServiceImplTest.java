package com.helloai.core.planner;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.service.impl.WebPageFetchServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebPageFetchServiceImpl} 单元测试（V43 用户消息 URL 直取）。
 *
 * <p>JDK 内置 {@link HttpServer} 起本地桩站点：验证 HTML 转纯文本（剔除 script/style、
 * 去标签、实体解码、空白折叠）、title 提取、正文截断、非 2xx / 非文本类型 / 空正文
 * 一律降级 ok=false 不抛异常。</p>
 */
@DisplayName("网页直取服务（V43）")
class WebPageFetchServiceImplTest {

    private HttpServer server;
    private WebSearchProperties properties;
    private WebPageFetchServiceImpl service;
    private String baseUrl;

    /** 桩响应（由用例覆盖）。 */
    private volatile int responseStatus = 200;
    private volatile String contentType = "text/html; charset=utf-8";
    private volatile String responseBody = "";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(responseStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";

        properties = new WebSearchProperties();
        properties.setUrlFetchTimeoutMs(5_000L);
        properties.setUrlFetchMaxTextChars(4_000);
        service = new WebPageFetchServiceImpl(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static String html(String body) {
        return "<html><head><title>OpenMaic 开放平台</title>"
                + "<style>body{color:red}</style>"
                + "<script>var leaked='脚本内容不应出现';</script></head>"
                + "<body>" + body + "</body></html>";
    }

    @Test
    @DisplayName("正常页面：提取 title + 正文剔噪转纯文本")
    void fetch_parsesTitleAndCleanText() {
        responseBody = html("<h1>欢迎&nbsp;使用</h1><p>这是<strong>正文</strong>内容</p><!-- 注释隐藏 -->");

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.getTitle()).isEqualTo("OpenMaic 开放平台");
        assertThat(page.getText())
                .contains("欢迎 使用")
                .contains("这是 正文 内容")     // 内联标签边界转空格，不影响 LLM 语义
                .doesNotContain("脚本内容不应出现")   // script 剔除
                .doesNotContain("color:red")          // style 剔除
                .doesNotContain("注释隐藏")            // 注释剔除
                .doesNotContain("<");                 // 标签全部去除
    }

    @Test
    @DisplayName("实体解码：&amp;/&lt;/&gt;/&quot;/&#39; 还原")
    void fetch_decodesEntities() {
        responseBody = html("<p>a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39;</p>");

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.getText()).contains("a & b <c> \"d\" 'e'");
    }

    @Test
    @DisplayName("正文超长：按 urlFetchMaxTextChars 截断并追加省略号")
    void fetch_truncatesLongText() {
        properties.setUrlFetchMaxTextChars(50);
        responseBody = html("<p>" + "甲".repeat(200) + "</p>");

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.getText()).hasSize(51).endsWith("…");
    }

    @Test
    @DisplayName("无 title 标签：回退 URL host 作标题")
    void fetch_missingTitleFallsBackToHost() {
        responseBody = "<html><body><p>纯正文</p></body></html>";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.getTitle()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("非 2xx：降级 ok=false 带状态码原因")
    void fetch_non2xx_returnsFailed() {
        responseStatus = 404;
        responseBody = "not found";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isFalse();
        assertThat(page.getReason()).contains("404");
    }

    @Test
    @DisplayName("非文本 Content-Type（图片）：降级 ok=false 不解析")
    void fetch_binaryContentType_returnsFailed() {
        contentType = "image/png";
        responseBody = "fake-bytes";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isFalse();
        assertThat(page.getReason()).contains("image/png");
    }

    @Test
    @DisplayName("V44 SPA 空壳页（有 title 无正文）：元数据兜底成功 metaOnly=true")
    void fetch_emptyText_salvagesTitleMeta() {
        responseBody = "<html><head><title>OpenMaic 开放平台</title>"
                + "<meta name=\"description\" content=\"多智能体协作平台\"/>"
                + "</head><body><div id=\"app\"></div>"
                + "<script>console.log('all in js')</script></body></html>";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.isMetaOnly()).isTrue();
        assertThat(page.getTitle()).isEqualTo("OpenMaic 开放平台");
        assertThat(page.getText())
                .contains("OpenMaic 开放平台")
                .contains("多智能体协作平台");
    }

    @Test
    @DisplayName("V44 SPA 空壳页（og 标签属性序反转）：content 在前也能提取")
    void fetch_emptyText_salvagesOgMetaReversedAttrOrder() {
        responseBody = "<html><head>"
                + "<meta content=\"AI 多智能体平台\" property=\"og:description\">"
                + "<meta content=\"OpenMaic\" property=\"og:site_name\">"
                + "</head><body><div id=\"app\"></div></body></html>";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isTrue();
        assertThat(page.isMetaOnly()).isTrue();
        // 无 title 标签 → 标题回退 host；描述与站点名入文本
        assertThat(page.getTitle()).isEqualTo("127.0.0.1");
        assertThat(page.getText())
                .contains("AI 多智能体平台")
                .contains("OpenMaic");
    }

    @Test
    @DisplayName("V44 空壳页无任何元数据：仍降级 ok=false 注明原因")
    void fetch_emptyTextNoMeta_returnsFailed() {
        responseBody = "<html><head></head><body><div id=\"app\"></div>"
                + "<script>console.log('all in js')</script></body></html>";

        WebPageContent page = service.fetch(baseUrl);

        assertThat(page.isOk()).isFalse();
        assertThat(page.getReason()).contains("正文为空");
    }

    @Test
    @DisplayName("非法 URL：降级 ok=false 不抛异常")
    void fetch_invalidUrl_returnsFailed() {
        WebPageContent page = service.fetch("https://不合法的主机");

        assertThat(page.isOk()).isFalse();
        assertThat(page.getReason()).isNotBlank();
    }

    @Test
    @DisplayName("空白 URL：降级 ok=false 不抛异常")
    void fetch_blankUrl_returnsFailed() {
        WebPageContent page = service.fetch("   ");

        assertThat(page.isOk()).isFalse();
    }

    @Test
    @DisplayName("连接拒绝（无服务端口）：异常内部降级 ok=false 不抛出")
    void fetch_connectionRefused_returnsFailed() {
        WebPageContent page = service.fetch("http://127.0.0.1:1/nothing");

        assertThat(page.isOk()).isFalse();
        assertThat(page.getReason()).isNotBlank();
    }
}
