package com.helloai.core.planner.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link BochaRerankService} 降级语义单测（W-5）。
 *
 * <p>重排的网络路径（真实 HTTP）由 E2E / 真实链路日志验证；单测聚焦「不会坏」的契约：
 * 开关关闭 / 密钥缺失 / 结果不足 2 条 / 语义锚为空 / 网络失败 —— 一律**原样返回入参列表**
 * （同一实例、同顺序），保证重排永不阻断澄清主流程。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BochaRerankService 语义重排降级语义")
class BochaRerankServiceTest {

    @Mock
    private WebSearchProperties properties;

    @Mock
    private WebSearchCredentialKeyStore credentialKeyStore;

    private BochaRerankService newService() {
        lenient().when(properties.getRerankTimeoutMs()).thenReturn(5_000L); // HttpClient.connectTimeout 需 >0
        return new BochaRerankService(properties, credentialKeyStore, new ObjectMapper());
    }

    private static List<WebSearchResult> threeResults() {
        return List.of(
                WebSearchResult.builder().title("A").url("https://a.com/1").snippet("a").build(),
                WebSearchResult.builder().title("B").url("https://b.com/2").snippet("b").build(),
                WebSearchResult.builder().title("C").url("https://c.com/3").snippet("c").build());
    }

    @Test
    @DisplayName("开关关闭（默认）→ isAvailable=false 且 rerank 原样返回（同一实例同顺序）")
    void rerank_disabled_returnsSameList() {
        when(properties.isRerankEnabled()).thenReturn(false);
        BochaRerankService service = newService();
        List<WebSearchResult> input = threeResults();

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.rerank("为什么膝盖旧伤不能跑步", input)).isSameAs(input);
    }

    @Test
    @DisplayName("开关开启但无博查密钥 → isAvailable=false，不发起请求")
    void rerank_enabledButNoKey_unavailable() {
        when(properties.isRerankEnabled()).thenReturn(true);
        when(credentialKeyStore.resolveBochaApiKey()).thenReturn("  ");
        BochaRerankService service = newService();
        List<WebSearchResult> input = threeResults();

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.rerank("query", input)).isSameAs(input);
    }

    @Test
    @DisplayName("结果不足 2 条 → 无排空间，原样返回（不浪费一次计费调用）")
    void rerank_singleResult_noOp() {
        // 注意：size<2 在 isAvailable() 之前短路 ⇒ 不 stub 开关/密钥（strict 模式会报未使用）
        BochaRerankService service = newService();
        List<WebSearchResult> single = List.of(
                WebSearchResult.builder().title("A").url("https://a.com/1").snippet("a").build());

        assertThat(service.rerank("query", single)).isSameAs(single);
    }

    @Test
    @DisplayName("语义锚为空/空白 → 原样返回（纯 URL 消息场景，不误排）")
    void rerank_blankAnchor_noOp() {
        when(properties.isRerankEnabled()).thenReturn(true);
        when(credentialKeyStore.resolveBochaApiKey()).thenReturn("key");
        BochaRerankService service = newService();
        List<WebSearchResult> input = threeResults();

        assertThat(service.rerank("   ", input)).isSameAs(input);
        assertThat(service.rerank(null, input)).isSameAs(input);
    }

    @Test
    @DisplayName("网络失败（端点不可达）→ 降级原顺序，绝不抛异常")
    void rerank_networkFailure_degradesSilently() {
        when(properties.isRerankEnabled()).thenReturn(true);
        when(credentialKeyStore.resolveBochaApiKey()).thenReturn("key");
        // 指向必然不可达的本机端口，验证 catch 分支（超时/连接拒绝）
        when(properties.getRerankBaseUrl()).thenReturn("http://127.0.0.1:1/v1/rerank");
        when(properties.getRerankTimeoutMs()).thenReturn(300L);
        when(properties.getRerankMaxDocuments()).thenReturn(50);
        when(properties.getRerankModel()).thenReturn("gte-rerank");
        BochaRerankService service = newService();
        List<WebSearchResult> input = threeResults();

        List<WebSearchResult> out = service.rerank("膝盖旧伤能不能跑步", input);
        assertThat(out).as("失败必须原样返回（同实例同顺序），绝不阻断").isSameAs(input);
    }
}
