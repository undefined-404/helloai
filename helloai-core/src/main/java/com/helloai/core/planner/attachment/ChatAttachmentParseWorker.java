package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.system.storage.ArtifactStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 会话附件解析 worker：把 {@code PENDING} 推进到终态（方案 §3.3）。
 *
 * <p>状态机：{@code PENDING → PARSING → READY | FAILED | SKIPPED}。四类语义不可混：</p>
 * <ul>
 *   <li>{@code READY}：正文可注入；</li>
 *   <li>{@code FAILED}：解析器出错（文件损坏、超时），<b>可重试</b>；</li>
 *   <li>{@code SKIPPED}：读不出且重试无用（图片/音视频在 P0 无正文能力），<b>不许当空文件糊过去</b>；</li>
 *   <li>{@code PARSING}：已被领取，重复投递不会二次解析（条件更新闸门）。</li>
 * </ul>
 *
 * <p><b>为什么解析失败不抛给调用方</b>：上传是同步请求，解析是后台任务，两者生命周期不同。
 * 上传成功 = 文件已落库可预览；解析失败只影响「能否把正文喂给模型」，由前端胶囊与
 * Prompt 清单如实呈现，不阻断对话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentParseWorker {

    /** 错误摘要入库长度（与 {@code parse_error} 列宽 512 对齐，留余量）。 */
    private static final int ERROR_MAX_LENGTH = 500;

    private final ChatAttachmentMapper chatAttachmentMapper;
    private final ArtifactStorage artifactStorage;
    private final DocumentParserRouter documentParserRouter;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 与渲染侧同源的窗口配置：判定「是否超预算」必须用同一个值，否则 ingest 门槛与注入预算脱节。 */
    @Value("${helloai.attachment.context-window-tokens:128000}")
    private int contextWindowTokens;

    /**
     * 异步解析入口（上传成功后调用）。
     *
     * <p>用独立线程池而非默认 executor：解析是 CPU/内存型任务，不能与 SSE 长连接抢线程
     * （见 {@link ChatAttachmentAsyncConfig}）。</p>
     */
    @Async("chatAttachmentParseExecutor")
    public void parseAsync(Long attachmentId) {
        parse(attachmentId);
    }

    /**
     * 同步解析（worker 主体；测试与运维重跑直接调这里，不经过代理）。
     *
     * @param attachmentId 附件 ID
     */
    public void parse(Long attachmentId) {
        // 幂等闸门：条件更新 PENDING→PARSING，抢不到说明已被处理或已在解析，直接返回
        if (chatAttachmentMapper.markParsing(attachmentId) != 1) {
            log.debug("附件解析跳过（非 PENDING 或已被领取）: id={}", attachmentId);
            return;
        }
        ChatAttachment attachment = chatAttachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            log.warn("附件解析跳过（记录不存在或已删除）: id={}", attachmentId);
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            ContentKind kind = ContentKind.of(attachment.getMimeType(), attachment.getFileName());
            if (documentParserRouter.find(kind).isEmpty()) {
                // 无解析器 = 该类型读不出正文（P0 的图片/音视频）。SKIPPED 而非 FAILED：
                // 重试同样没有实现，报 FAILED 只会诱导用户反复重试。
                chatAttachmentMapper.markSkipped(attachmentId, skipReason(kind));
                log.info("附件解析跳过（无可用解析器）: id={}, kind={}, fileName={}",
                        attachmentId, kind, attachment.getFileName());
                return;
            }
            byte[] content = artifactStorage.load(attachment.getStorageUrl());
            ParsedContent parsed = documentParserRouter.parse(kind,
                    new ParsedInput(attachment.getFileName(), attachment.getMimeType(), content));
            chatAttachmentMapper.markReady(attachmentId, parsed.text(), parsed.chars(),
                    parsed.pageCount(), parsed.parser(), parsed.charset());
            log.info("附件解析完成: id={}, kind={}, parser={}, chars={}, pages={}, truncated={}, costMs={}",
                    attachmentId, kind, parsed.parser(), parsed.chars(), parsed.pageCount(),
                    parsed.truncated(), System.currentTimeMillis() - startedAt);
            maybeIngestToKb(attachment, parsed);
        } catch (AttachmentParseException e) {
            chatAttachmentMapper.markFailed(attachmentId, summarize(e.getMessage()));
            log.warn("附件解析失败（可重试）: id={}, fileName={}, err={}",
                    attachmentId, attachment.getFileName(), e.getMessage());
        } catch (RuntimeException e) {
            // 存储读取失败等基础设施异常也归 FAILED：用户可重试，且状态不会永远停在 PARSING
            chatAttachmentMapper.markFailed(attachmentId, summarize(e.toString()));
            log.error("附件解析异常: id={}, fileName={}", attachmentId, attachment.getFileName(), e);
        }
    }

    /**
     * `ATT-3`：超预算附件 ingest 到 kb，之后检索命中片段而非注入全文。
     *
     * <p><b>只在超预算时做</b>：预算内附件全量注入更快更准（无召回损失），ingest 是
     * 「读不到」的补丁，不是默认路径。</p>
     *
     * <p><b>失败一律降级</b>：KB 未启用、嵌入接口失败、切片异常——都只记 WARN，
     * 不影响解析结果与 READY 状态。此时该附件退化为「按预算截断注入」，用户仍能问
     * 前一部分，不会因为 kb 故障连正文都看不到。</p>
     */
    private void maybeIngestToKb(ChatAttachment attachment, ParsedContent parsed) {
        int budget = AttachmentContentPolicy.resolveChatAttachmentTotalLimit(contextWindowTokens);
        if (parsed.chars() <= budget) {
            return; // 预算内：全量注入即可
        }
        String source = "chat:" + attachment.getConversationId() + ":" + attachment.getId();
        try {
            int chunks = knowledgeBaseService.ingest(source, parsed.text(),
                    attachment.getConversationId(), attachment.getUploaderUserId());
            if (chunks > 0) {
                chatAttachmentMapper.markKbSource(attachment.getId(), source);
            }
            log.info("超预算附件已 ingest: id={}, source={}, chunks={}, chars={}, budget={}",
                    attachment.getId(), source, chunks, parsed.chars(), budget);
        } catch (RuntimeException e) {
            log.warn("附件 ingest 失败（不影响解析结果，退化为按预算截断注入）: id={}, chars={}, err={}",
                    attachment.getId(), parsed.chars(), e.toString());
        }
    }

    /** 跳过原因：写进清单供模型与用户看，措辞必须说明「没有正文」，避免臆测。 */
    private static String skipReason(ContentKind kind) {
        return kind == ContentKind.MEDIA
                ? "图片/音视频暂不支持正文提取（P0 阶段），仅登记元信息"
                : "该类型暂不支持正文提取，仅登记元信息";
    }

    /** 错误摘要：去掉换行并截断，避免把整段堆栈塞进 512 列。 */
    private static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "解析失败（无错误信息）";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_MAX_LENGTH ? flat : flat.substring(0, ERROR_MAX_LENGTH);
    }
}
