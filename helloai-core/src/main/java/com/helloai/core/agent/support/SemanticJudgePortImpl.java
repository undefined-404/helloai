package com.helloai.core.agent.support;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link SemanticJudgePort} 的实现：取 Agent → 建模型（唯一实现见 {@link AgentChatModelResolver}）
 * → 单轮调用 → 抽正文。
 *
 * <p><b>温度 0</b>：判定类调用的结论必须可复现，同一份草案两次确认不应得到相反结论。</p>
 * <p><b>不设工具、不带历史</b>：判定只需要材料，给模型工具面会让它去「查证」而不是「比对」，
 * 那是执行链的职责。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticJudgePortImpl implements SemanticJudgePort {

    private final AgentService agentService;
    private final AgentChatModelResolver agentChatModelResolver;

    @Override
    public String judgeOnce(Long agentId, String systemPrompt, String userPrompt) {
        if (agentId == null) {
            throw new BizException("语义判定失败：未指定 agentId");
        }
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("语义判定失败：Agent 不存在: agentId=" + agentId);
        }
        ChatModel chatModel = agentChatModelResolver.resolve(agent);

        ChatOptions options = ChatOptions.builder().temperature(0.0d).build();
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt == null ? "" : systemPrompt),
                new UserMessage(userPrompt == null ? "" : userPrompt)), options));
        String text = response == null ? "" : ChatResponseContentExtractor.extract(response).text();
        log.debug("语义判定模型返回（{} 字）: agentId={}", text == null ? 0 : text.length(), agentId);
        return text == null ? "" : text;
    }
}
