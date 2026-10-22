package com.helloai.core.planner.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本族判码（方案 §3.3「直读 + 判码」）。
 *
 * <p>这几条不是理论用例：中文场景里 GBK 的 txt/csv 极为常见，按 UTF-8 硬解会整篇乱码；
 * 而纯 ASCII 又会被部分探测器报成 windows-1252（置信度极低），必须靠「严格 UTF-8 先试」挡住。
 * 本测试锁定判定顺序，避免以后有人图省事改成「一律 UTF-8」。</p>
 */
@DisplayName("TextCharsetResolver 文本族判码")
class TextCharsetResolverTest {

    private final TextCharsetResolver resolver = new TextCharsetResolver();

    @Test
    @DisplayName("UTF-8 中文：严格解码成功即采信 UTF-8（不误判为 windows-1252）")
    void shouldResolveUtf8Chinese() {
        byte[] bytes = "这是 UTF-8 编码的中文需求说明。".getBytes(StandardCharsets.UTF_8);
        Charset charset = resolver.resolve(bytes, "req.md");
        assertThat(charset).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TextCharsetResolver.decode(bytes, charset)).contains("中文需求说明");
    }

    @Test
    @DisplayName("GBK 中文：判成 GB18030 并能正确解码（按 UTF-8 硬解会乱码）")
    void shouldResolveGbkChinese() {
        Charset gbk = Charset.forName("GBK");
        byte[] bytes = "这是一份需求说明。\n第三章节讲述并发模型。".getBytes(gbk);
        // 先证明按 UTF-8 硬解确实会坏（这正是必须判码的原因）
        assertThat(TextCharsetResolver.isStrictUtf8(bytes)).isFalse();

        Charset charset = resolver.resolve(bytes, "legacy.txt");
        assertThat(charset.name()).isIn("GB18030", "GBK", "GB2312");
        assertThat(TextCharsetResolver.decode(bytes, charset)).contains("这是一份需求说明").contains("并发模型");
    }

    @Test
    @DisplayName("UTF-8 BOM：按 UTF-8 判定并跳过 BOM（正文首字符不是 U+FEFF）")
    void shouldHandleUtf8Bom() {
        byte[] body = "带 BOM 的中文".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        Charset charset = resolver.resolve(bytes, "bom.txt");
        assertThat(charset).isEqualTo(StandardCharsets.UTF_8);
        String decoded = TextCharsetResolver.decode(bytes, charset);
        assertThat(decoded).isEqualTo("带 BOM 的中文");
        assertThat(decoded.charAt(0)).isNotEqualTo('\uFEFF');
    }

    @Test
    @DisplayName("UTF-16LE BOM：按 UTF-16LE 判定并跳过 BOM")
    void shouldHandleUtf16LeBom() {
        byte[] body = "UTF16 中文".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);

        Charset charset = resolver.resolve(bytes, "u16.txt");
        assertThat(charset).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(TextCharsetResolver.decode(bytes, charset)).isEqualTo("UTF16 中文");
    }

    @Test
    @DisplayName("纯 ASCII 代码：UTF-8 严格解码通过，直接采信（置信度 0.1 的 windows-1252 不会被采用）")
    void shouldResolveAsciiCodeAsUtf8() {
        byte[] bytes = "public class A { int x = a | b; }".getBytes(StandardCharsets.UTF_8);
        assertThat(resolver.resolve(bytes, "A.java")).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("空文件：返回 UTF-8，不抛异常")
    void shouldHandleEmpty() {
        assertThat(resolver.resolve(new byte[0], "empty.txt")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(resolver.resolve(null, "empty.txt")).isEqualTo(StandardCharsets.UTF_8);
    }
}
