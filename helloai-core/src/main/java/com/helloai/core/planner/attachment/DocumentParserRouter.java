package com.helloai.core.planner.attachment;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按内容族路由到具体 {@link DocumentParser}（方案 §3.3）。
 *
 * <p>路由表由 Spring 注入的全部实现拼装，新增解析器（视觉 / Docling）不需要改这里。</p>
 */
@Component
public class DocumentParserRouter {

    private final Map<ContentKind, DocumentParser> parsers = new EnumMap<>(ContentKind.class);

    public DocumentParserRouter(List<DocumentParser> parserList) {
        for (DocumentParser parser : parserList) {
            DocumentParser previous = parsers.put(parser.kind(), parser);
            if (previous != null) {
                throw new IllegalStateException("内容族 " + parser.kind() + " 存在多个解析器实现："
                        + previous.getClass().getName() + " / " + parser.getClass().getName());
            }
        }
    }

    /** 找该内容族的解析器；图片/未知格式在 P0 阶段没有实现，返回空由调用方标 SKIPPED/拒收。 */
    public Optional<DocumentParser> find(ContentKind kind) {
        return Optional.ofNullable(parsers.get(kind));
    }

    /**
     * 解析；无可用的解析器时抛 {@link AttachmentParseException}（调用方据此标 SKIPPED 而不是 FAILED）。
     */
    public ParsedContent parse(ContentKind kind, ParsedInput input) {
        return find(kind)
                .orElseThrow(() -> new AttachmentParseException("暂不支持解析该类型：" + kind + " / " + input.fileName()))
                .parse(input);
    }
}
