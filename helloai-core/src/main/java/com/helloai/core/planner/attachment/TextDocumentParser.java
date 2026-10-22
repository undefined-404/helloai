package com.helloai.core.planner.attachment;

import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 文本族解析：直读字节 + 判码，<b>不经 Tika 的 SAX 渲染</b>（方案 §3.3）。
 *
 * <p>实测依据：同一份 md/代码喂给 {@code ToMarkdownContentHandler} 会被转义成
 * {@code \# 标题} / {@code \|a\|b\|} / {@code \`code\`}；喂给 {@code BodyContentHandler}
 * 则把标题与表格剥平，并给代码文件插一行文件名。两种都算失真，故文本族保留直读。</p>
 */
@Component
public class TextDocumentParser implements DocumentParser {

    private final TextCharsetResolver charsetResolver;

    public TextDocumentParser() {
        this(new TextCharsetResolver());
    }

    TextDocumentParser(TextCharsetResolver charsetResolver) {
        this.charsetResolver = charsetResolver;
    }

    @Override
    public ContentKind kind() {
        return ContentKind.TEXT;
    }

    @Override
    public ParsedContent parse(ParsedInput input) {
        byte[] bytes = input.content();
        if (bytes == null || bytes.length == 0) {
            return ParsedContent.ofText("", "UTF-8", false);
        }
        Charset charset = charsetResolver.resolve(bytes, input.fileName());
        String text = TextCharsetResolver.decode(bytes, charset);
        boolean truncated = text.length() > MAX_EXTRACTED_CHARS;
        if (truncated) {
            text = truncate(text);
        }
        return ParsedContent.ofText(text, charset.name(), truncated);
    }

    /** 截断并显式标注，避免模型把「只给了前 200k」误当全文。 */
    private static String truncate(String text) {
        return text.substring(0, MAX_EXTRACTED_CHARS)
                + "\n\n…（正文已截断：仅保留前 " + MAX_EXTRACTED_CHARS + " 字符，原文共 " + text.length() + " 字符）";
    }
}
