package com.helloai.core.planner.attachment;

import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.detect.EncodingResult;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文本族字符集判定（方案 §3.3「直读 + 判码」）。
 *
 * <p>为什么不用「一律 UTF-8」：中文场景里 GBK/GB18030 的 txt/csv 极常见，按 UTF-8 硬解会整篇乱码，
 * 等于把文件丢了（实测 {@code ����һ��˵��…}）。为什么不把文本族直接交给 Tika：Tika 的
 * {@code ToMarkdownContentHandler} 会把正文里的 {@code # | ` _ [ ]} 全部转义，
 * {@code BodyContentHandler} 又会把标题/表格结构剥平，两种都算对原文的失真。</p>
 *
 * <p>判定顺序（越靠前越可信）：</p>
 * <ol>
 *   <li>BOM（UTF-8 / UTF-16LE / UTF-16BE）——最强证据，直接采信；</li>
 *   <li>严格 UTF-8 解码成功——UTF-8 的非法字节序列概率极低，成功即采信；</li>
 *   <li>Tika {@link DefaultEncodingDetector}（置信度 ≥ {@value #MIN_CONFIDENCE}）——覆盖 GB18030/Shift_JIS 等；</li>
 *   <li>GB18030——中文环境兜底（GBK 的超集，不会因扩展字失败）；</li>
 *   <li>UTF-8 + 替换符——保证不抛异常，宁可少量乱码也不让整份文件解析失败。</li>
 * </ol>
 *
 * <p>实例持有 {@link EncodingDetector}：探测器来自 ServiceLoader，构造有成本；
 * 由 {@link TextDocumentParser} 作为单例持有，不放在静态字段里（显式依赖更好测）。</p>
 */
public class TextCharsetResolver {

    private static final Logger log = LoggerFactory.getLogger(TextCharsetResolver.class);

    /** 低于该置信度的探测结果不采信（实测纯 ASCII 会被报成 windows-1252/0.1）。 */
    static final float MIN_CONFIDENCE = 0.5f;

    private static final Charset GB18030 = Charset.forName("GB18030");

    private final EncodingDetector encodingDetector;

    public TextCharsetResolver() {
        this(new DefaultEncodingDetector());
    }

    TextCharsetResolver(EncodingDetector encodingDetector) {
        this.encodingDetector = encodingDetector;
    }

    /**
     * 判定字符集。
     *
     * @param bytes    文件字节（空数组返回 UTF-8）
     * @param fileName 文件名，供 Tika 做类型/编码判定（缺了会退化成二进制判定，实测）
     */
    public Charset resolve(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        Charset fromBom = detectBom(bytes);
        if (fromBom != null) {
            return fromBom;
        }
        if (isStrictUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }
        Charset detected = detectByTika(bytes, fileName);
        if (detected != null) {
            return detected;
        }
        return GB18030;
    }

    /** BOM 判定；无 BOM 返回 null。 */
    static Charset detectBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /** 严格 UTF-8 解码是否成功（不落盘、不建 String，只验证码点序列）。 */
    static boolean isStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** 交给 Tika 探测器；无高置信结果或探测异常都返回 null（由上层继续兜底）。 */
    private Charset detectByTika(byte[] bytes, String fileName) {
        Metadata metadata = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(bytes))) {
            List<EncodingResult> results = encodingDetector.detect(in, metadata, new ParseContext());
            if (results == null || results.isEmpty()) {
                return null;
            }
            EncodingResult best = null;
            for (EncodingResult r : results) {
                if (r == null || r.getCharset() == null || r.getConfidence() < MIN_CONFIDENCE) {
                    continue;
                }
                if (best == null || r.getConfidence() > best.getConfidence()) {
                    best = r;
                }
            }
            if (best == null) {
                return null;
            }
            // decodeAs 是探测器给出的「按什么解」建议（可能不同于探测到的原始编码名）
            Charset decodeAs = best.getDecodeAs();
            return decodeAs != null ? decodeAs : best.getCharset();
        } catch (IOException | RuntimeException e) {
            log.warn("Tika 编码探测失败，回退 GB18030：file={} err={}", fileName, e.toString());
            return null;
        }
    }

    /** 按指定字符集解码（跳过 BOM）；无法解码的字节替换为 U+FFFD，不抛异常。 */
    static String decode(byte[] bytes, Charset charset) {
        int offset = 0;
        if (charset.equals(StandardCharsets.UTF_8) && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        } else if (charset.equals(StandardCharsets.UTF_16LE) || charset.equals(StandardCharsets.UTF_16BE)) {
            offset = bytes.length >= 2 ? 2 : 0;
        }
        CharBuffer decoded = charset.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return decoded.toString();
    }
}
