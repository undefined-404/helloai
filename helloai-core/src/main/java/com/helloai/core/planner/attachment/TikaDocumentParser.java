package com.helloai.core.planner.attachment;

import org.apache.tika.config.OutputLimits;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;

/**
 * 结构化格式解析（pdf / docx / pptx / xlsx / html / rtf…）：Tika 转 Markdown（方案 §3.3）。
 *
 * <p>三个必须做对的细节：</p>
 * <ol>
 *   <li><b>必须带文件名</b>：{@link TikaCoreProperties#RESOURCE_NAME_KEY} 缺失时，
 *       类型判定会退化成 {@code application/octet-stream} 并解析出空串（实测）。</li>
 *   <li><b>必须用 Markdown handler</b>：{@code Tika.parseToString} 走 {@code BodyContentHandler}，
 *       标题与表格结构全丢；Markdown handler 才能把表格输出成 {@code |a|b|}。</li>
 *   <li><b>必须设限额</b>：{@link TimeoutLimits} 给总时长与停顿预算，{@link OutputLimits}
 *       给输出字符上限（顺带防 zip 炸弹式的无限展开），比在外面包 {@code Future.get} 更准。</li>
 * </ol>
 *
 * <p>不做进程隔离（Tika 官方建议不可信文件走 Pipes fork）：P0 以内网/团队来源为主，
 * 用限额兜住；开放公网上传时再评估 {@code tika-pipes-fork-parser}（方案 §5 风险表）。</p>
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    /** 总预算 60s、停顿（无进展）10s —— 与方案 §3.3 的「解析超时 60s」一致。 */
    private static final long TOTAL_TIMEOUT_MILLIS = 60_000L;
    private static final long PROGRESS_TIMEOUT_MILLIS = 10_000L;

    /** 页数元数据候选键：PDF 走 xmpTPg:NPages，其它格式可能没有，读不到就返回 null。 */
    private static final String[] PAGE_COUNT_KEYS = {"xmpTPg:NPages", "meta:page-count", "Page-Count"};

    @Override
    public ContentKind kind() {
        return ContentKind.STRUCTURED;
    }

    @Override
    public ParsedContent parse(ParsedInput input) {
        Metadata metadata = new Metadata();
        if (input.fileName() != null && !input.fileName().isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, input.fileName());
        }
        if (input.mimeType() != null && !input.mimeType().isBlank()) {
            // 4.x 的 Content-Type 是 namespaced Property（HttpHeaders.CONTENT_TYPE），
            // 3.x 的 Metadata.CONTENT_TYPE 常量已移除，照抄旧示例会编译失败。
            metadata.set(HttpHeaders.CONTENT_TYPE, input.mimeType());
        }

        ParseContext context = new ParseContext();
        context.set(TimeoutLimits.class,
                new TimeoutLimits(TOTAL_TIMEOUT_MILLIS, PROGRESS_TIMEOUT_MILLIS));
        OutputLimits outputLimits = new OutputLimits();
        outputLimits.setWriteLimit(MAX_EXTRACTED_CHARS);
        context.set(OutputLimits.class, outputLimits);

        StringWriter writer = new StringWriter();
        byte[] bytes = input.content() == null ? new byte[0] : input.content();
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(bytes))) {
            new AutoDetectParser().parse(in, new ToMarkdownContentHandler(writer), metadata, context);
        } catch (SAXException | TikaException | IOException | RuntimeException e) {
            throw new AttachmentParseException("Tika 解析失败：" + input.fileName() + "：" + e.getMessage(), e);
        }

        String text = writer.toString();
        boolean truncated = text.length() > MAX_EXTRACTED_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_EXTRACTED_CHARS)
                    + "\n\n…（正文已截断：仅保留前 " + MAX_EXTRACTED_CHARS + " 字符）";
        }
        log.debug("Tika 解析完成：file={} mime={} chars={} truncated={}",
                input.fileName(), metadata.get(HttpHeaders.CONTENT_TYPE), text.length(), truncated);
        return ParsedContent.ofTika(text, pageCountOf(metadata), truncated);
    }

    /** 读页数（读不到返回 null，不猜）。 */
    private static Integer pageCountOf(Metadata metadata) {
        for (String key : PAGE_COUNT_KEYS) {
            String value = metadata.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // 元数据格式不合预期，继续试下一个候选键
            }
        }
        return null;
    }
}
