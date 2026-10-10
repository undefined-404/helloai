package com.helloai.core.shared.util;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OutboundUrlGuard} 单元测试（REF-5.4 出站 SSRF 守卫）。
 *
 * <p>纯离线：只做地址/协议判定与一次 {@code localhost} 解析（走系统 hosts，不发外部请求）。</p>
 */
@DisplayName("出站 URL 守卫")
class OutboundUrlGuardTest {

    @Nested
    @DisplayName("协议白名单")
    class Scheme {

        @Test
        @DisplayName("https 默认放行")
        void httpsAllowed() {
            HttpUrl parsed = OutboundUrlGuard.assertFetchable("https://example.com/a?b=1", false);
            assertThat(parsed.host()).isEqualTo("example.com");
        }

        @Test
        @DisplayName("http 默认拒绝（需显式开启 url-fetch-allow-insecure-http）")
        void httpBlockedByDefault() {
            assertThatThrownBy(() -> OutboundUrlGuard.assertFetchable("http://example.com", false))
                    .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class)
                    .hasMessageContaining("只允许 https");
        }

        @Test
        @DisplayName("http 显式开启后放行")
        void httpAllowedWhenExplicitlyEnabled() {
            assertThat(OutboundUrlGuard.assertFetchable("http://example.com", true).host())
                    .isEqualTo("example.com");
        }

        @Test
        @DisplayName("非 http(s) 协议一律拒绝（file / ftp / gopher / jar）")
        void nonHttpSchemesBlocked() {
            for (String url : List.of("file:///etc/passwd", "ftp://example.com/x",
                    "gopher://example.com/", "jar:file:///tmp/a.jar!/x")) {
                assertThatThrownBy(() -> OutboundUrlGuard.assertFetchable(url, true))
                        .as("应拒绝: %s", url)
                        .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class);
            }
        }

        @Test
        @DisplayName("空串 / 无协议 / 无主机 → 拒绝")
        void malformedBlocked() {
            // 注：`https:///path` **不在此列** —— 实测 OkHttp 把它归一为 host=path（一个普通域名，
            // 仍会走地址层守卫），不是畸形串。判据必须与真正建连的解析器一致，故不为它造特例。
            for (String url : new String[]{"", "   ", "https://", "not a url"}) {
                assertThatThrownBy(() -> OutboundUrlGuard.assertFetchable(url, true))
                        .as("应拒绝: %s", url)
                        .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class);
            }
        }

        @Test
        @DisplayName("IP 字面量在 URL 层直接拒绝（OkHttp 对字面量不走 Dns，正是云元数据类 SSRF 的形态）")
        void ipLiteralBlocked() {
            for (String url : List.of("http://169.254.169.254/latest/meta-data/",
                    "http://127.0.0.1:6565/api/backup", "https://10.0.0.5/", "https://[::1]/")) {
                assertThatThrownBy(() -> OutboundUrlGuard.assertFetchable(url, true))
                        .as("应拒绝: %s", url)
                        .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class)
                        .hasMessageContaining("出站守卫拒绝");
            }
        }
    }

    @Nested
    @DisplayName("地址层：禁用网段")
    class Addresses {

        private void blocked(String literal) throws Exception {
            assertThat(OutboundUrlGuard.isBlockedAddress(InetAddress.getByName(literal)))
                    .as("应判为禁用地址: %s", literal)
                    .isTrue();
        }

        private void allowed(String literal) throws Exception {
            assertThat(OutboundUrlGuard.isBlockedAddress(InetAddress.getByName(literal)))
                    .as("应判为可外联: %s", literal)
                    .isFalse();
        }

        @Test
        @DisplayName("IPv4：回环 / 私网 / 链路本地（云元数据）/ 保留段全部禁用")
        void ipv4BlockedRanges() throws Exception {
            blocked("127.0.0.1");
            blocked("127.1.2.3");
            blocked("10.0.0.1");
            blocked("172.16.0.1");
            blocked("172.31.255.254");
            blocked("192.168.1.1");
            blocked("169.254.169.254");   // 云元数据（AWS/GCP/Aliyun）
            blocked("100.64.0.1");        // 运营商级 NAT
            blocked("0.0.0.0");
            blocked("198.18.0.1");
            blocked("192.0.0.1");
            blocked("240.0.0.1");
            blocked("255.255.255.255");
        }

        @Test
        @DisplayName("IPv4：公网地址放行")
        void ipv4PublicAllowed() throws Exception {
            allowed("8.8.8.8");
            allowed("1.1.1.1");
            allowed("39.106.204.43");
            allowed("172.32.0.1");   // 172.16/12 之外
            allowed("100.128.0.1");  // 100.64/10 之外
        }

        @Test
        @DisplayName("IPv6：回环 / 链路本地 / 唯一本地禁用；公网放行")
        void ipv6Ranges() throws Exception {
            blocked("::1");
            blocked("fe80::1");
            blocked("fc00::1");
            blocked("fd12:3456::1");
            allowed("2606:4700:4700::1111");
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 必须拆包再判（::ffff:127.0.0.1 不得绕过）")
        void v4MappedUnwrapped() throws Exception {
            blocked("::ffff:127.0.0.1");
            blocked("::ffff:169.254.169.254");
            blocked("::ffff:10.1.2.3");
        }

        @Test
        @DisplayName("解析结果含任一禁用地址 → 整体拒绝（防同主机名混答公网+私网）")
        void mixedAnswerRejected() throws Exception {
            List<InetAddress> mixed = List.of(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("127.0.0.1"));
            assertThatThrownBy(() -> OutboundUrlGuard.assertAddressesAllowed("evil.example", mixed))
                    .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class)
                    .hasMessageContaining("127.0.0.1");
        }

        @Test
        @DisplayName("解析为空 → 拒绝")
        void emptyAnswerRejected() {
            assertThatThrownBy(() -> OutboundUrlGuard.assertAddressesAllowed("void.example", List.of()))
                    .isInstanceOf(OutboundUrlGuard.SsrfBlockedException.class);
        }
    }

    @Nested
    @DisplayName("OkHttp Dns 实现（pinning 落点）")
    class SafeDnsTest {

        @Test
        @DisplayName("localhost → 解析到回环 ⇒ 转 UnknownHostException（对 OkHttp 即「不可达」）")
        void localhostRejected() {
            assertThatThrownBy(() -> new OutboundUrlGuard.SafeDns().lookup("localhost"))
                    .isInstanceOf(UnknownHostException.class)
                    .hasMessageContaining("出站守卫拒绝");
        }

        @Test
        @DisplayName("公网域名 → 原样返回解析结果（用同一份结果建连 = pinning）")
        void publicHostPassesThrough() throws Exception {
            List<InetAddress> resolved = new OutboundUrlGuard.SafeDns().lookup("8.8.8.8");
            assertThat(resolved).isNotEmpty();
        }

        @Test
        @DisplayName("非规范 IPv4 写法按「解析结果」判（实测：OkHttp 4.12 不做该规范化，故这些串走本层）")
        void nonCanonicalIpv4FormsJudgedByResolvedAddress() throws Exception {
            // 判据是**最终解析出的地址**，不是字面量形态 —— 这三条的实测结果（JDK 17 / OkHttp 4.12）：
            assertThatThrownBy(() -> new OutboundUrlGuard.SafeDns().lookup("2130706433"))
                    .as("十进制写法解析为 127.0.0.1 ⇒ 拒")
                    .isInstanceOf(UnknownHostException.class);
            assertThatThrownBy(() -> new OutboundUrlGuard.SafeDns().lookup("0x7f.1"))
                    .as("JDK 不认该写法 ⇒ 解析不了 ⇒ 同样不可达")
                    .isInstanceOf(UnknownHostException.class);
            // `0177.0.0.1`：实测该 JDK **按十进制**解析为 177.0.0.1（公网），而非八进制的 127.0.0.1
            // ⇒ 不构成绕过（守卫的不变量是「最终连的地址必须公网」，此处恰好成立）。
            // 这条断言刻意钉住该行为：**若某次 JDK 变更把它解析回 127.0.0.1，本用例会红**，
            // 提醒重新评估 —— 那是安全相关行为变化，不该静默通过。
            assertThat(new OutboundUrlGuard.SafeDns().lookup("0177.0.0.1")).isNotEmpty();
        }
    }
}
