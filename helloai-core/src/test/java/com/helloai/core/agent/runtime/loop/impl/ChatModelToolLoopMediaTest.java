package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * `VU-1` 视觉直读通道的消息装配判据。
 *
 * <p>核心是<b>「零媒体零变化」</b>：加媒体通道之前，全平台每条链路的首条 user 消息都是
 * {@code new UserMessage(text)}；改造后若无媒体也走 builder，请求体就多出 builder 补的默认值，
 * 等于给所有既有纯文本链路做了一次未经声明的行为变更。本类把这条件固化成断言。</p>
 *
 * <p>另一条是被计划反复强调的<b>「媒体与指令必须同一条消息」</b>：图若单独成消息，
 * 模型会把图当孤立附件、丢掉任务锚点。</p>
 */
@DisplayName("VU-1 视觉直读通道：消息装配")
class ChatModelToolLoopMediaTest {

    /** 显式类型：{@code List.of()} 在重载构造器下会被推断成 {@code List<Object>} 而匹配失败。 */
    private static final List<ToolCallback> NO_TOOLS = List.of();

    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);

    // ---------- 判据一：零媒体零变化 ----------

    @Test
    @DisplayName("无媒体时首条 UserMessage 的 media 为空（纯文本链路不被改造波及）")
    void shouldNotAttachMediaWhenAbsent() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop().run(new AgentLoopInput(model, "sys", "user", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, null));

        UserMessage sent = firstUserMessage(model);
        assertThat(sent.getMedia()).isEmpty();
    }

    @Test
    @DisplayName("无媒体时正文逐字一致（不多空格、不改空串语义）")
    void shouldKeepTextVerbatimWithoutMedia() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop().run(new AgentLoopInput(model, "sys", "  原样保留的正文  ", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, null));

        // 加媒体通道不得顺带 trim / 归一化既有正文
        assertThat(firstUserMessage(model).getText()).isEqualTo("  原样保留的正文  ");
    }

    @Test
    @DisplayName("userPrompt 为 null 时正文按空串处理（与改造前同语义）")
    void shouldTreatNullPromptAsEmptyText() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop().run(new AgentLoopInput(model, "sys", null, toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, null));

        assertThat(firstUserMessage(model).getText()).isEmpty();
        assertThat(firstUserMessage(model).getMedia()).isEmpty();
    }

    @Test
    @DisplayName("media 传 null 被归一化为空表（调用方漏传不退化为 NPE）")
    void shouldNormalizeNullMedia() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        AgentLoopInput input = new AgentLoopInput(model, "sys", "user", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L,
                null, null, null, null, null, null);
        assertThat(input.media()).isEmpty();

        loop().run(input);
        assertThat(firstUserMessage(model).getMedia()).isEmpty();
    }

    // ---------- 判据二：有媒体时数量与 mime 正确 ----------

    @Test
    @DisplayName("有媒体时图与指令同条 user 消息，数量与 mime 原样透传")
    void shouldAttachMediaToSameUserMessage() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));
        Media png = pngMedia("chart.png");

        loop().run(new AgentLoopInput(model, "sys", "看图后判定", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L,
                null, null, null, null, null, List.of(png)));

        List<Message> sent = model.lastMessages();
        // 图不得单独成消息：system + 唯一一条 user（若图被拆出去，这里会是 3 条）
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);

        UserMessage user = firstUserMessage(model);
        assertThat(user.getText()).isEqualTo("看图后判定");
        assertThat(user.getMedia()).hasSize(1);
        assertThat(user.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
        assertThat(user.getMedia().get(0).getName()).isEqualTo("chart.png");
    }

    @Test
    @DisplayName("多张图按传入顺序保留")
    void shouldPreserveMediaOrder() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));
        Media first = pngMedia("a.png");
        Media second = pngMedia("b.png");

        loop().run(new AgentLoopInput(model, "sys", "对比两张图", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L,
                null, null, null, null, null, List.of(first, second)));

        assertThat(firstUserMessage(model).getMedia())
                .extracting(Media::getName)
                .containsExactly("a.png", "b.png");
    }

    @Test
    @DisplayName("空 systemPrompt 守卫与媒体共存：有图也不得发出空 system 消息")
    void shouldStillSkipBlankSystemMessageWithMedia() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop().run(new AgentLoopInput(model, "", "看图", toolExecutor, NO_TOOLS,
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L,
                null, null, null, null, null, List.of(pngMedia("a.png"))));

        List<Message> sent = model.lastMessages();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(UserMessage.class);
        assertThat(sent).noneMatch(m -> m instanceof SystemMessage);
    }

    // ---------- 判据三：兼容构造器逐级委托贯通 ----------

    @Test
    @DisplayName("12/13/14/15 参兼容构造器均得到 media 为空表（旧调用点零改动）")
    void shouldDefaultEmptyMediaThroughLegacyConstructors() {
        ChatModel model = mock(ChatModel.class);

        AgentLoopInput c12 = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null);
        AgentLoopInput c13 = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null, null);
        AgentLoopInput c14 = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null, null, null);
        AgentLoopInput c15 = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null, null, null, null);
        AgentLoopInput c16 = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null, null, null, null, null);

        assertThat(List.of(c12, c13, c14, c15, c16))
                .allSatisfy(input -> assertThat(input.media()).isEmpty());
        // 其余字段不得因多包一层而丢失
        assertThat(c16.runId()).isEqualTo("run");
        assertThat(c16.subTaskId()).isEqualTo(2L);
        assertThat(c16.maxIterations()).isEqualTo(5);
    }

    @Test
    @DisplayName("17 参主构造器保留媒体（兼容构造器不得把媒体吞掉）")
    void shouldKeepMediaThroughPrimaryConstructor() {
        ChatModel model = mock(ChatModel.class);
        List<Media> media = List.of(pngMedia("chart.png"));

        AgentLoopInput input = new AgentLoopInput(model, "s", "u", toolExecutor, NO_TOOLS,
                5, "run", 1L, 2L, 1, 3L, null, null, null, null, null, media);

        assertThat(input.media()).isSameAs(media);
    }

    // ---------- 辅助 ----------

    private ChatModelToolLoop loop() {
        return new ChatModelToolLoop();
    }

    private static UserMessage firstUserMessage(StubChatModel model) {
        return model.lastMessages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未发送任何 UserMessage"));
    }

    private static Media pngMedia(String name) {
        return Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[] {(byte) 0x89, 'P', 'N', 'G'}))
                .name(name)
                .build();
    }

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())), null);
    }

    /** 确定性 ChatModel stub：响应队列 + 记录最近一次收到的 messages。 */
    private static final class StubChatModel implements ChatModel {

        private final Queue<ChatResponse> responses = new LinkedList<>();
        private List<Message> lastMessages = new ArrayList<>();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastMessages = new ArrayList<>(prompt.getInstructions());
            return responses.poll();
        }

        List<Message> lastMessages() {
            return lastMessages;
        }
    }
}