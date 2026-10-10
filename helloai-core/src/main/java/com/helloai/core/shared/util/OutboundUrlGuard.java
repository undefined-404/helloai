package com.helloai.core.shared.util;

import okhttp3.Dns;
import okhttp3.HttpUrl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 出站 URL 守卫（`REF-5.4`，裁定 `D-2026-10-09-6②`）—— 平台**自己发起**的外联必须过它。
 *
 * <p><b>要防什么</b>：平台会抓取「用户/模型给出的 URL」（网页直取链路）。不加约束时，一个
 * {@code http://169.254.169.254/…}（云元数据）或 {@code http://127.0.0.1:6565/...}（平台自身管理面）
 * 就能被当成"网页"抓回来 —— 典型 SSRF。两条真实路径：</p>
 * <ul>
 *   <li><b>直连内网</b> —— 目标主机名/字面量本身就是内网地址；</li>
 *   <li><b>重定向绕过</b> —— 首跳是公网合法域名，302 到内网地址（旧实现用
 *       {@code HttpClient.Redirect.NORMAL} 跟随跳转且**不校验跳转目标**）。</li>
 * </ul>
 *
 * <p><b>两层防线</b>：</p>
 * <ol>
 *   <li>{@link #assertFetchable(String, boolean)} —— <b>URL 层</b>：协议白名单（默认只放 https）
 *       + **IP 字面量直判**（见其 javadoc：OkHttp 对字面量主机不走 {@code Dns}，而这正是云元数据
 *       类 SSRF 的典型形态）。跳转的**每一跳**都要重跑（见 {@code WebPageFetchServiceImpl} 的手工跳转循环）；</li>
 *   <li>{@link SafeDns} —— <b>地址层</b>：交给 OkHttp 的 {@link Dns} 实现，解析结果里**任一**地址
 *       命中禁用网段即整体拒绝。OkHttp 用这份解析结果去建连（不再二次解析），因此这同时是
 *       <b>DNS-rebinding 的 pinning</b>：解析与建连之间没有被换答案的窗口。
 *       <b>边界</b>：本层只对**域名**生效 —— 字面量路径由第 1 层兜住。</li>
 * </ol>
 *
 * <p><b>为什么用纯静态工具 + 内部 Dns 实现</b>：守卫是"判定"，不该是 Bean —— 静态可离线单测
 * （不启 Spring、不发网络请求），与同包的 {@link AttachmentContentPolicy} / {@link TextTruncator} 同型。</p>
 *
 * <p><b>不做的事（诚实边界）</b>：不校验证书（由 https 自身保证）、不做域名黑名单
 * （那是策略不是守卫）、不提供"放行私网"的开关 —— 放行私网等于关掉本类唯一的作用。</p>
 */
public final class OutboundUrlGuard {

    /** 允许的最大跳转次数（每跳都重跑 URL 层守卫）。 */
    public static final int MAX_REDIRECTS = 5;

    /** 规范化为点分四段的 IPv4 字面量（OkHttp 的 HttpUrl 已把 hex/octal/十进制写法归一到该形态）。 */
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private OutboundUrlGuard() {
    }

    /**
     * URL 层守卫：规范化并校验一个待抓取 URL。
     *
     * <p><b>为什么用 OkHttp 的 {@link HttpUrl} 而不是 {@code java.net.URI} 来解析</b>：判的对象必须与
     * **真正建连的那个 URL** 是同一个。{@code HttpUrl} 会做主机规范化（{@code 2130706433} /
     * {@code 0x7f.1} / {@code 0177.0.0.1} 都会归一到 {@code 127.0.0.1}），而 {@code java.net.URI}
     * 只把它们当普通主机名 —— 拿 URI 判就会**看走眼**。</p>
     *
     * <p><b>为什么字面量必须在这一层判（实测结论）</b>：OkHttp 对 **IP 字面量主机不走 {@link Dns}**
     * —— 直连字面量，地址层守卫（{@link SafeDns}）在那条路径上**根本不会被调用**。
     * 而 {@code http://169.254.169.254/}（云元数据）恰恰是最典型的 SSRF 目标。
     * 故本层对字面量主机**直接判定**，地址层只负责域名解析出来的地址。</p>
     *
     * @param url               待校验 URL
     * @param allowInsecureHttp 是否显式允许明文 http（默认 false；本地内网调试才开）
     * @return 规范化后的 {@link HttpUrl}（供后续建连与相对跳转解析复用）
     * @throws SsrfBlockedException URL 无法解析（含非 http(s) 协议）/ 协议被拒 / 主机为禁用地址
     */
    public static HttpUrl assertFetchable(String url, boolean allowInsecureHttp) {
        if (url == null || url.isBlank()) {
            throw new SsrfBlockedException("URL 为空");
        }
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            // HttpUrl 只接受 http / https —— file: / ftp: / gopher: / jar: / 畸形串都在此被拒
            throw new SsrfBlockedException("URL 无法解析或协议不受支持: " + url);
        }
        String scheme = parsed.scheme();
        if ("http".equals(scheme) && !allowInsecureHttp) {
            throw new SsrfBlockedException(
                    "出站请求默认只允许 https（明文 http 需显式开启 url-fetch-allow-insecure-http）");
        }
        String host = parsed.host();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL 缺少主机名: " + url);
        }
        if (isIpLiteral(host)) {
            InetAddress literal;
            try {
                // 已确认是字面量（点分四段 / 含冒号的 IPv6），此处不触发 DNS 查询
                literal = InetAddress.getByName(host);
            } catch (Exception e) {
                throw new SsrfBlockedException("目标主机字面量无法解析: " + host);
            }
            if (isBlockedAddress(literal)) {
                throw new SsrfBlockedException(
                        "目标地址被出站守卫拒绝（回环 / 私网 / 链路本地 / 保留段）: " + host);
            }
        }
        return parsed;
    }

    /** 是否 IP 字面量（规范化后的形态：点分四段 或 含冒号的 IPv6）。 */
    static boolean isIpLiteral(String host) {
        return host != null && (host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches());
    }

    /**
     * 地址层判定：该地址是否属于**不得外联**的范围。
     *
     * <p>覆盖：未指定 / 回环 / 链路本地（含云元数据 {@code 169.254.169.254}）/ 私网
     * （{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}）/ 运营商级 NAT（{@code 100.64/10}）/
     * 保留段（{@code 240/4}、{@code 198.18/15}、{@code 192.0.0/24}）/ 组播，以及
     * IPv6 的未指定 / 回环 / 链路本地 / 唯一本地（{@code fc00::/7}）/ 组播。</p>
     *
     * <p><b>IPv4-mapped IPv6 必须先拆包</b>（{@code ::ffff:127.0.0.1}）：不拆就等于给了一条
     * 绕过 IPv4 判定的现成通道。</p>
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isBlockedV4(bytes);
        }
        if (address instanceof Inet6Address) {
            // IPv4-mapped（::ffff:a.b.c.d）与 IPv4-compatible：拆成 IPv4 再判一遍
            if (isV4Mapped(bytes)) {
                return isBlockedV4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            return address.isLinkLocalAddress() || isUniqueLocalV6(bytes);
        }
        return true;
    }

    /** 解析结果整体判定：**任一**地址被禁即拒绝（防同一主机名混答「公网 + 私网」）。 */
    public static void assertAddressesAllowed(String host, List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new SsrfBlockedException("目标主机解析为空: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new SsrfBlockedException(
                        "目标地址被出站守卫拒绝（回环 / 私网 / 链路本地 / 保留段）: " + host
                                + " → " + address.getHostAddress());
            }
        }
    }

    // ────────────────────────────────────────────────────────────

    /** 出站守卫拒绝（unchecked：调用方按"抓取失败"降级处理，不中断主流程）。 */
    public static class SsrfBlockedException extends RuntimeException {
        public SsrfBlockedException(String message) {
            super(message);
        }
    }

    /**
     * 交给 OkHttp 的安全 DNS —— 解析后逐个地址过守卫，**用同一份结果建连**（即 pinning）。
     *
     * <p>放在本类内部而非独立 Bean：它无状态、无配置，且必须与 {@link #isBlockedAddress} 同源
     * —— 两处各写一套地址判定，就是下一个漂移点。</p>
     */
    public static final class SafeDns implements Dns {

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            try {
                assertAddressesAllowed(hostname, addresses);
            } catch (SsrfBlockedException e) {
                // 转成 UnknownHostException：对 OkHttp 而言等价于"这个名字不可达"，
                // 既让请求就地失败，也不把内网地址回显进上层错误链（少一处信息泄露面）
                throw new UnknownHostException(e.getMessage());
            }
            return addresses;
        }
    }

    // ────────────────────────────────────────────────────────────

    private static boolean isBlockedV4(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        if (a0 == 0) {
            return true;                            // 0.0.0.0/8（含 this-network）
        }
        if (a0 == 10) {
            return true;                            // 10.0.0.0/8
        }
        if (a0 == 127) {
            return true;                            // 127.0.0.0/8（loopback 的显式兜底）
        }
        if (a0 == 169 && a1 == 254) {
            return true;                            // 169.254.0.0/16（link-local，含云元数据）
        }
        if (a0 == 172 && a1 >= 16 && a1 <= 31) {
            return true;                            // 172.16.0.0/12
        }
        if (a0 == 192 && a1 == 168) {
            return true;                            // 192.168.0.0/16
        }
        if (a0 == 192 && a1 == 0 && (b[2] & 0xFF) == 0) {
            return true;                            // 192.0.0.0/24（IETF 保留）
        }
        if (a0 == 100 && a1 >= 64 && a1 <= 127) {
            return true;                            // 100.64.0.0/10（运营商级 NAT）
        }
        if (a0 == 198 && (a1 == 18 || a1 == 19)) {
            return true;                            // 198.18.0.0/15（基准测试保留）
        }
        if (a0 >= 240) {
            return true;                            // 240.0.0.0/4（保留，含 255.255.255.255）
        }
        return false;
    }

    /** {@code ::ffff:a.b.c.d} / {@code ::a.b.c.d} 形态的 IPv4-mapped / compatible 判定。 */
    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        // 前 10 字节全 0；第 11/12 字节为 0x00 00（compatible）或 0xFF FF（mapped）
        return (b[10] == 0 && b[11] == 0) || ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF);
    }

    /** IPv6 唯一本地地址（{@code fc00::/7}）。 */
    private static boolean isUniqueLocalV6(byte[] b) {
        return (b[0] & 0xFE) == 0xFC;
    }
}
