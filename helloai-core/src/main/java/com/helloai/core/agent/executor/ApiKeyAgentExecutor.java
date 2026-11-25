package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.support.AttachmentMediaAssembler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * API_KEY_LLM 执行器占位实现。
 *
 * <p>先把平台内执行抽象和路由入口立住，真实 ChatClient 链路放到 /接入。</p>
 */
@Slf4j
@Component
public class ApiKeyAgentExecutor implements AgentExecutor {

    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final AgentExecutionProperties executionProperties;
    /** 媒体引用 → 媒体装载（`VU-9`）：与执行链同一装载器，判据不写第二份。 */
    private final AttachmentMediaAssembler attachmentMediaAssembler;

    public ApiKeyAgentExecutor(
            AgentChatClientService agentChatClientService,
            AgentLlmCredentialResolver agentLlmCredentialResolver,
            AgentExecutionProperties executionProperties,
            AttachmentMediaAssembler attachmentMediaAssembler) {
        this.agentChatClientService = agentChatClientService;
        this.agentLlmCredentialResolver = agentLlmCredentialResolver;
        this.executionProperties = executionProperties;
        this.attachmentMediaAssembler = attachmentMediaAssembler;
    }

    @Override
    public AgentResult execute(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());

        String vaultApiKey = resolveApiKey(agent, provider);
        List<Media> media = loadMedia(task);
        ChatResponse response = agentChatClientService.generate(
                agent,
                task.getSystemPrompt(),
                task.getUserPrompt(),
                provider,
                vaultApiKey,
                task.getTemperature(),
                media
        );
        // 分离正文与思考过程：推理模型（如 Minimax ）的 thinking 块不混入 output
        ChatResponseContentExtractor.ExtractedContent extracted = ChatResponseContentExtractor.extract(response);
        String content = extracted.text();
        String thinking = extracted.thinking().isBlank() ? null : extracted.thinking();
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

        log.info("API_KEY_LLM 执行完成: agentId={}, subTaskId={}, modelType={}, tokens={}",
                agent.getId(), task.getSubTaskId(), agent.getModelType(), totalTokens);
        return AgentResult.success(content, thinking, "STOP", getName(), totalTokens);
    }

    /**
     * 解析并校验 API Key（同步与流式共用）：mock 模式返回 null；真实模式优先平台级
     * （模型配置）密钥、Agent 级兜底（系统管理轮换 API Key 后实时生效），
     * 开启 requireVault 且无可用密钥时抛 BizException。
     */
    private String resolveApiKey(Agent agent, String provider) {
        if (executionProperties.isMockMode()) {
            return null;
        }
        String vaultApiKey = agentLlmCredentialResolver.resolveApiKey(agent);
        if (executionProperties.isRequireVault()
                && (vaultApiKey == null || vaultApiKey.isBlank())) {
            throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId()
                                + ", provider=" + provider + "，请先在系统管理中配置模型 API Key");
        }
        return vaultApiKey;
    }

    /**
     * 流式执行：同同步链路的凭证解析（mock/真实一致），LLM 调用走
     * {@link AgentChatClientService#generateStream} 的 token 增量通道。
     *
     * <p>凭证解析与真实 LLM 流都放进 Flux.defer：订阅时才真正发起（惰性语义），
     * 供上层在业务线程池里订阅时异步执行。</p>
     */
    @Override
    public Flux<com.helloai.core.agent.chat.LlmStreamChunk> executeStream(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        return Flux.defer(() -> {
            String vaultApiKey = resolveApiKey(agent, provider);
            // 媒体装载放进 defer：与凭据解析同一时点（订阅时才真正读存储），
            // 且「请求不发就不读字节」——流式被 dispose 时不该产生无用的存储读取
            return agentChatClientService.generateStream(
                    agent,
                    task.getSystemPrompt(),
                    task.getUserPrompt(),
                    provider,
                    vaultApiKey,
                    loadMedia(task)
            );
        });
    }

    /**
     * 装载本轮媒体（`VU-9`）：把 {@link AgentTask#getMediaRefs()} 的<b>引用</b>交给装载器读字节。
     *
     * <p><b>这里是「引用 → 字节」的唯一时点</b>，理由见 {@code AgentTask.mediaRefs} 的 javadoc：
     * 能力判定必须发生在知道实际模型之后（不变量 I-2），而实际模型只有走到
     * {@code AgentChatClientService} 才确定。此处装载只是「按引用取数」，
     * <b>不判能力</b>——判能力的责任在 {@code AgentChatClientService#applyUserMessage}。</p>
     *
     * <p><b>异常不外抛</b>：{@code loadInlineImages} 内部逐条 catch；它自身抛出的概率极低
     * （仅空入参等），但媒体是增强而非必要条件，一律降级为空表而不是让整轮失败。</p>
     */
    private List<Media> loadMedia(AgentTask task) {
        try {
            return attachmentMediaAssembler.loadInlineImages(task.getMediaRefs());
        } catch (RuntimeException e) {
            log.warn("媒体装载失败，本轮按无媒体处理: subTaskId={}, err={}",
                    task.getSubTaskId(), e.toString());
            return List.of();
        }
    }

    @Override
    public boolean supports(Agent agent) {
        return agent != null && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

}
