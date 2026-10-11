package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.admin.KbIngestRequest;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
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

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
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
    public R<KnowledgeSearchResult.Hit> chunk(@PathVariable("source") String source,
                                              @PathVariable("chunkIdx") int chunkIdx) {
        var hit = kbService.chunkByKey(source, chunkIdx);
        if (hit.isEmpty()) {
            return R.fail("知识库分片不存在：" + source + "#" + chunkIdx);
        }
        return R.ok(new KnowledgeSearchResult.Hit(hit.get().source(), hit.get().chunkIdx(), hit.get().content()));
    }
}