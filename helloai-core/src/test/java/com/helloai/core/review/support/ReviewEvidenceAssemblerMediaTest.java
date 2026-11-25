package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewEvidenceAssembler} 的媒体作用域单测（`VU-2`）。
 *
 * <p><b>覆盖的四条判据</b>：</p>
 * <ol>
 *   <li>无媒体附件 ⇒ 媒体段为空串、引用为空表（「本来就没图」不加噪声）；</li>
 *   <li>有图片且模型能看图 ⇒ 引用带该图、说明「已随消息提供」；</li>
 *   <li>有图片但模型看不到 ⇒ 引用为空表、说明<b>保留</b>「无法查看、从严核验」
 *       （降级路径是唯一正确行为，不是可删的历史遗留）；</li>
 *   <li>只有音频/视频 ⇒ 引用为空表、说明「无可随消息提供的图片」——
 *       这一态若并入「已带来图」，模型就会声称看过它压根没收到的东西（不变量 I-4）。</li>
 * </ol>
 *
 * <p><b>另覆盖一条结构判据</b>：媒体说明不再拼进 {@code attachmentContent}
 * —— 那一段的语义是「平台直读的文本，超限要截断/续读」，图片不适用。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewEvidenceAssembler（VU-2 媒体作用域）")
class ReviewEvidenceAssemblerMediaTest {

    private static final Long SUB_TASK_ID = 11L;
    private static final Long TASK_ID = 22L;

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AgentDispatchProperties dispatchProperties;

    private ReviewEvidenceAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ReviewEvidenceAssembler(attachmentService, dispatchProperties);
        lenient().when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(true);
        lenient().when(attachmentService.loadContent(anyLong()))
                .thenReturn("【正文】".getBytes(StandardCharsets.UTF_8));
    }

    private SubTaskView subTask() {
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 0,
                "截图核对", "核对界面截图", "截图.png", "界面与文案一致", null,
                Map.of("lastExecution", Map.of("output", "已生成截图")), List.of());
    }

    private static AttachmentView textAttachment() {
        return new AttachmentView(1L, "report.md", "markdown", "text/markdown", 64L, true);
    }

    private static AttachmentView imageAttachment(long id, String name, String mime) {
        return new AttachmentView(id, name, "image", mime, 1024L, true);
    }

    @Nested
    @DisplayName("媒体作用域四态")
    class Scope {

        @Test
        @DisplayName("① 无媒体附件 ⇒ 媒体段空串 + 引用空表（不加无意义说明）")
        void noMediaYieldsEmptyScope() {
            when(attachmentService.listActiveViews(any())).thenReturn(List.of(textAttachment()));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), true);

            assertThat(content.mediaVisibility()).isEmpty();
            assertThat(content.mediaRefs()).isEmpty();
        }

        @Test
        @DisplayName("② 有图片且模型能看图 ⇒ 引用带该图 + 说明「已随消息提供」")
        void imageWithVisionYieldsRefs() {
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(textAttachment(), imageAttachment(9L, "ui.png", "image/png")));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), true);

            assertThat(content.mediaRefs()).hasSize(1);
            MediaRef ref = content.mediaRefs().get(0);
            assertThat(ref.attachmentId()).isEqualTo(9L);
            assertThat(ref.source()).isEqualTo(MediaRef.Source.TASK_ATTACHMENT);
            assertThat(ref.mimeType()).isEqualTo("image/png");
            assertThat(content.mediaVisibility())
                    .contains("ui.png")
                    .contains("随本条消息一并提供")
                    .doesNotContain("无法查看");
        }

        @Test
        @DisplayName("③ 有图片但模型看不到 ⇒ 引用空表 + 保留「无法查看、从严核验」")
        void imageWithoutVisionKeepsStrictNote() {
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(imageAttachment(9L, "ui.png", "image/png")));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), false);

            assertThat(content.mediaRefs()).isEmpty();
            assertThat(content.mediaVisibility())
                    .contains("ui.png")
                    .contains("无法查看")
                    .contains("从严核验")
                    .doesNotContain("一并提供");
        }

        @Test
        @DisplayName("④ 只有音频 ⇒ 引用空表 + 说明「无可随消息提供的图片」（不谎称已带来图）")
        void audioOnlyDoesNotClaimImageProvided() {
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(new AttachmentView(5L, "call.mp3", "audio", "audio/mpeg", 2048L, true)));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), true);

            assertThat(content.mediaRefs()).isEmpty();
            assertThat(content.mediaVisibility())
                    .contains("call.mp3")
                    .contains("没有可随消息提供的图片")
                    .doesNotContain("你可以直接查看图片内容");
        }

        @Test
        @DisplayName("⑤ 单参入口等价 mediaVisible=false（保守默认，不因漏传而「碰巧为真」）")
        void singleArgEntryDefaultsToInvisible() {
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(imageAttachment(9L, "ui.png", "image/png")));

            ReviewEvidenceAssembler.EvidenceContent content = assembler.buildEvidenceContent(subTask());

            assertThat(content.mediaRefs()).isEmpty();
            assertThat(content.mediaVisibility()).contains("无法查看");
        }
    }

    @Nested
    @DisplayName("与附件正文的分家")
    class Separation {

        @Test
        @DisplayName("⑥ 媒体说明不拼进 attachmentContent（图片不属于「可直读文本」段）")
        void mediaNoteIsNotPartOfAttachmentContent() {
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(textAttachment(), imageAttachment(9L, "ui.png", "image/png")));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), true);

            assertThat(content.attachmentContent()).isNotBlank();
            assertThat(content.attachmentContent())
                    .doesNotContain("随本条消息一并提供")
                    .doesNotContain("无法查看其原始内容");
            assertThat(content.mediaVisibility()).contains("随本条消息一并提供");
        }

        @Test
        @DisplayName("⑦ 附件注入开关关闭 ⇒ 媒体段照常产出（媒体与开关无关）")
        void mediaScopeSurvivesDisabledInjection() {
            when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(false);
            when(attachmentService.listActiveViews(any()))
                    .thenReturn(List.of(imageAttachment(9L, "ui.png", "image/png")));

            ReviewEvidenceAssembler.EvidenceContent content =
                    assembler.buildEvidenceContent(subTask(), true);

            assertThat(content.attachmentContent()).contains("附件内容注入已关闭");
            assertThat(content.mediaRefs()).hasSize(1);
            assertThat(content.mediaVisibility()).contains("随本条消息一并提供");
        }
    }
}
