package com.helloai.core.planner.attachment;

/**
 * 附件解析端口（方案 §3.3）：一个端口，按 {@link ContentKind} 分实现。
 *
 * <p>P0 有两个实现：{@link TextDocumentParser}（文本族直读 + 判码）、{@link TikaDocumentParser}
 * （结构化格式经 Tika 转 Markdown）。P1 会加视觉实现（图片/扫描件），P2 预留 Docling 实现位。
 * 新增实现只需 {@code @Component} 并声明自己处理的 {@link #kind()}，无需改调用方。</p>
 */
public interface DocumentParser {

    /** 正文提取上限（字符）：与方案 §3.3「直读全文上限 200k」一致，超限截断并标注。 */
    int MAX_EXTRACTED_CHARS = 200_000;

    /** 本实现负责的内容族。 */
    ContentKind kind();

    /**
     * 提取正文。
     *
     * @throws AttachmentParseException 格式损坏、超时、解析器异常（由调用方标 {@code FAILED}，可重试）
     */
    ParsedContent parse(ParsedInput input);
}
