package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Spring AI ChatClient 调用入口（平台内 Agent 执行链的 LLM 交互层）。
 *
 * <p>支持两种凭据路径：调用方显式传入 provider + API Key 明文（外部 Agent 凭据注入），
 * 或使用 Spring 容器内已配置的 ChatClient.Builder（平台级默认通道）。</p>
 */
public interface AgentChatClientService {

    /**
     * 使用 Spring AI ChatClient 生成回复（默认平台通道）。
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt);

    /**
     * 使用 Spring AI ChatClient 生成回复。
     *
     * @param provider       显式指定 provider；null/空 = 走执行属性默认 provider
     * @param apiKeyPlaintext 明文 API Key；null/空 = 走容器内已配置的 ChatClient.Builder
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                          String provider, String apiKeyPlaintext);

    /**
     * 使用 Spring AI ChatClient 生成回复（显式采样温度）。
     *
     * @param temperature 采样温度；null = 不覆盖模型默认值（与 5 参重载行为完全一致）
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                          String provider, String apiKeyPlaintext, Double temperature);

    /**
     * 带媒体的同步生成（`VU-9`）：媒体与正文<b>同一条 {@code UserMessage}</b>。
     *
     * <p><b>能力判定在本方法内部按「本次实际调用的模型」进行</b>（不变量 I-2）：
     * 调用方只管把「用户点名要看的那几张图」以引用形式交进来，<b>不判断</b>模型能不能看图。
     * 这条分工是硬性的——{@code agent} 实体在调用方可能已被 {@code AgentModelPin} 换过模型，
     * 也可能被会话级 {@code modelType} 覆盖，调用方看到的模型与这里将要调用的模型
     * 不保证一致。写成让调用方先判、这里只发，就复刻了 OpenCode 官方 issue
     * {@code anomalyco/opencode#60736}。</p>
     *
     * <p><b>媒体为空时逐字等价于 6 参重载</b>：走原 {@code .user(String)} 单参重载，
     * 请求体不多出任何默认值（回归面不放大）。</p>
     *
     * @param media 媒体（已装载；{@code null}/空 = 纯文本，行为与 6 参重载一致）
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                          String provider, String apiKeyPlaintext, Double temperature, List<Media> media);

    /**
     * 流式生成回复（默认平台通道）：以 token 增量 {@link Flux} 输出，订阅时发起调用。
     *
     * <p>语义与 {@link #generate(Agent, String, String)} 完全一致，仅传输形态不同：
     * mock 模式走 {@code MockChatModel.stream()} 分片伪流式（输出与同步 call 一致），
     * 真实模式走 {@code ChatClient.prompt().stream().chatResponse()}（正文与思考过程**分离**输出，
     * 与同步链路的 {@code ChatResponseContentExtractor} 同一把尺子）；并发限流与错误语义同同步路径。</p>
     */
    Flux<com.helloai.core.agent.chat.LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt);

    /**
     * 流式生成回复（显式 provider / 明文 API Key，语义同 {@link #generate(Agent, String, String, String, String)}）。
     */
    Flux<com.helloai.core.agent.chat.LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                String provider, String apiKeyPlaintext);

    /**
     * 带媒体的流式生成（`VU-9`）：语义与
     * {@link #generate(Agent, String, String, String, String, Double, List)} 逐字一致，
     * 仅传输形态不同。
     *
     * <p><b>同/流两条入口必须同批改</b>：只改同步会得到「重发一次就没图了」的错觉
     * （工作台的重新生成走流式）。</p>
     */
    Flux<com.helloai.core.agent.chat.LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                String provider, String apiKeyPlaintext, List<Media> media);

    /**
     * 构建底层 ChatModel（P0-B-2：Runtime AgentLoop 直接持有模型而非 ChatClient）。
     *
     * <p>mock 模式返回 MockChatModel（分片伪流式与同步 call 一致）；真实模式经
     * {@code LlmProviderChatClientFactoryRegistry.createChatModel} 返回缓存实例；
     * 显式 provider + API Key 缺失时抛 BizException（Runtime 循环需可解析凭据，
     * 容器内 ChatClient.Builder 无 ChatModel 出口，不在此路径兜底）。</p>
     *
     * @param agent           Agent 实体（mock 前缀 / 模型解析）
     * @param provider        显式 provider；null/空 = 走执行属性默认 provider
     * @param apiKeyPlaintext 明文 API Key（mock 模式可为 null）
     * @return ChatModel（永不为 null）
     */
    ChatModel buildChatModel(Agent agent, String provider, String apiKeyPlaintext);
}
