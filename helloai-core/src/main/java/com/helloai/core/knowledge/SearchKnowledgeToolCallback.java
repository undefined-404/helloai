package com.helloai.core.knowledge;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Predicate;

/**
 * 知识库检索平台工具 {@code search_knowledge}（REF-4.2，阶段 REF-4 Capability 化）。
 *
 * <p>能力归属 RAG（REF-4.x）：把 {@link KnowledgeBaseService} 封装为 spring-ai {@code @Tool}，
 * 经 {@link ToolCallbackContributor} 端口并入平台工具目录（ToolRegistry/ToolExecutor
 * 自动可见），**禁止**给 LLM 直插域内 Service（CODE_STYLE §35.1 接线规则，与 web_search 同模式）。</p>
 *
 * <p><b>REF-4.1「无 KB 即摘工具」</b>：{@link #toolAvailability()} 声明「无知识库数据
 * （或总开关关）⇒ {@code search_knowledge} 从模型可见列表摘除」——与
 * {@code web_search} 的「无凭据即摘」同一语义位（REF-1.3）。摘除不落库、不改管理面、
 * 不改 MCP {@code tools/list} 暴露面，作用面 = 进程内 Runtime 的模型可见列表。</p>
 *
 * <p>实现不加 {@code @Transactional}/AOP 注解：agent 域收集器要求原始对象（代理会丢
 * {@code @Tool} 注解，见 {@code McpToolConfig} 说明）。失败降级为空结果（与 web_search
 * 同契约：搜索失败绝不抛，消费方按空结果降级）。</p>
 */
@Slf4j
@Component
public class SearchKnowledgeToolCallback implements ToolCallbackContributor {

    private final KnowledgeBaseService kbService;

    /** 显式构造器（与主类口径一致，绕开 Lombok 增量编译坑）。 */
    public SearchKnowledgeToolCallback(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @Override
    public Object toolObject() {
        return this;
    }

    /**
     * 「按条件可用」声明（REF-4.1）：总开关关 或 无知识库数据 ⇒ {@code search_knowledge}
     * 从模型可见列表摘除。判定**委托能力所有者**（{@link KnowledgeBaseService#hasKnowledge()}），
     * 不在工具侧重算存储事实（必然与 Repository 漂移）。与上下文无关，故天然满足 fail-open
     * 契约（不读 {@link ToolContext} 字段，判定只取决于进程内存储事实）。</p>
     */
    @Override
    public Map<String, Predicate<ToolContext>> toolAvailability() {
        return Map.of("search_knowledge", context -> kbService.hasKnowledge());
    }

    @Tool(name = "search_knowledge", description = """
            【何时使用】需要检索平台知识库（内部文档 / 历史任务产出 / 技能包内容）时调用；
            是内部知识检索，区别于外部实时搜索 web_search。
            【调用频率】每次检索调用一次；可对同一问题换关键词多次检索合并，按需控制总数。
            【引用溯源（REF-4.3）】命中结果每条含 source 与 chunkIdx（如 source='manual-rag'、chunkIdx=0）。
            凡在最终报告 / 核验意见中引用某条知识库事实，必须在该句末尾原样附来源标记
            `[kb:{source}#{chunkIdx}]`（如 `[kb:manual-rag#0]`），便于人类点开核对原文；
            同一来源多次引用可只标一次。仅当确未使用任何知识库事实时才不输出该标记。
            【Gotchas】
            - 无命中的查询返回空 hits（hits=[]），请在回复中说明未检索到相关内部资料
            - maxResults 1-${max}, 超出按边界收敛；返回值已按注入预算截断（truncated=true 表示内容被截断）
            - 禁止臆造 source#chunkIdx：只能引用本次返回 hits 里真实存在的 source 与 chunkIdx
            【相关工具】web_search（外部实时检索，知识库缺失时的补充）
            """)
    public KnowledgeSearchResult searchKnowledge(
            @ToolParam(description = "检索关键词 / 待回答问题", required = true) String query,
            @ToolParam(description = "最大返回条数，范围 1-8，默认 4", required = false) Integer maxResults) {
        try {
            return kbService.search(query, maxResults);
        } catch (Exception e) {
            // 契约：检索失败绝不抛（消费方按空结果降级）；防御性兜底
            log.warn("search_knowledge 调用异常，降级空结果: err={}", e.getMessage());
            return KnowledgeSearchResult.empty(query);
        }
    }
}