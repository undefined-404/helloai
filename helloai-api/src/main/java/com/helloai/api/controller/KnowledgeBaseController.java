package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.admin.KbChunkView;
import com.helloai.api.dto.admin.KbIngestRequest;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库（RAG，REF-4.x）管理端接口。
 *
 * <p>摄入 / 清空为管理操作（{@code kb:manage}，仅 ADMIN）；检索为只读（{@code kb:view}，
 * ADMIN + NORMAL_USER）。本面是**验证与运维数据源**（e2e 用小样本 ingest → search），
 * 不建菜单页（与 V103 红线一致）。`search_knowledge` 工具本体在 {@code ToolRegistry}，
 * 与本章节管理面各自独立。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    /** 仅用于把 `chat:{conv}:{att}` 反解成文件名（弹窗标题）；不参与检索与摄入。 */
    private final ChatAttachmentService chatAttachmentService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   ChatAttachmentService chatAttachmentService) {
        this.kbService = kbService;
        this.chatAttachmentService = chatAttachmentService;
    }

    /**
     * 摄入一段正文到知识库（分片 → 嵌入 → 落库），同 source 幂等覆盖。
     */
    @SaCheckPermission("kb:manage")
    @PostMapping("/ingest")
    public R<Integer> ingest(@RequestBody KbIngestRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            return R.fail("text 不能为空");
        }
        try {
            int chunks = kbService.ingest(req.source(), req.text());
            log.info("知识库摄入完成: source={}, chunks={}", req.source(), chunks);
            return R.ok(chunks);
        } catch (BizException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 检索知识库（query 嵌入 → 余弦 top-k），返回已按注入预算截断的结果。
     */
    @SaCheckPermission("kb:view")
    @GetMapping("/search")
    public R<KnowledgeSearchResult> search(@RequestParam("query") String query,
                                           @RequestParam(value = "maxResults", required = false) Integer maxResults) {
        try {
            return R.ok(kbService.search(query, maxResults));
        } catch (BizException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 清空某来源的知识库分片（回滚 / 重摄前置；派生数据硬删）。
     */
    @SaCheckPermission("kb:manage")
    @DeleteMapping("/source/{source}")
    public R<Void> clearSource(@PathVariable("source") String source) {
        kbService.clearSource(source);
        log.info("知识库来源已清空: source={}", source);
        return R.ok();
    }

    /**
     * 按来源 + 分片键取单个分片（引用溯源 marker 卡片，REF-4.3）。
     */
    @SaCheckPermission("kb:view")
    @GetMapping("/chunk/{source}/{chunkIdx}")
    public R<KbChunkView> chunk(@PathVariable("source") String source,
                                @PathVariable("chunkIdx") int chunkIdx) {
        var hit = kbService.chunkByKey(source, chunkIdx);
        if (hit.isEmpty()) {
            return R.fail("知识库分片不存在：" + source + "#" + chunkIdx);
        }
        return R.ok(new KbChunkView(hit.get().source(), hit.get().chunkIdx(), hit.get().content(),
                resolveFileName(source)));
    }

    /**
     * `chat:{conversationId}:{attachmentId}` → 附件原始文件名；其它来源（管理端 ingest 的手册等）返回 null。
     *
     * <p>纯展示用途，best-effort：解析失败 / 附件已删 / 非会话附件来源一律返回 null，
     * 前端回落到通用标题。**不得因此让分片正文取不出来**——正文才是这个接口的价值。</p>
     *
     * <p>复用 {@code listByConversation} 而不是新增按 id 查询的服务方法：会话附件数量是个位数，
     * 一次列表查询比给契约加方法更省；也避免为「取个文件名」引入 getByIdRequired 的 404 抛错路径。</p>
     */
    private String resolveFileName(String source) {
        if (source == null || !source.startsWith("chat:")) {
            return null;
        }
        String[] parts = source.split(":");
        if (parts.length < 3) {
            return null;
        }
        try {
            Long conversationId = Long.valueOf(parts[1]);
            Long attachmentId = Long.valueOf(parts[2]);
            return chatAttachmentService.listByConversation(conversationId).stream()
                    .filter(a -> attachmentId.equals(a.getId()))
                    .map(ChatAttachment::getFileName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("分片来源解析文件名失败（回落通用标题）: source={}, err={}", source, e.toString());
            return null;
        }
    }
}