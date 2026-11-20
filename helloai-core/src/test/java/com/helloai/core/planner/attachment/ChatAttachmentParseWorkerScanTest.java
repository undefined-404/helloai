package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.common.constant.SensitiveScanPoint;
import com.helloai.common.constant.SensitiveScanRule;
import com.helloai.core.agent.observability.SensitiveScanRecorder;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.system.storage.ArtifactStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `MA-8` 挂点④：附件正文扫描单测。
 *
 * <p><b>为什么这个挂点要单独一套测试</b>：它是唯一一个「扫描结果要落库供用户看」的挂点
 * （前三个只写事件）。因此除了「有没有扫」，还必须验证<b>三态语义</b>：
 * {@code null}=未扫描 / {@code 0}=扫过没命中 / {@code >0}=命中——三者一旦混同，
 * 用户界面上「没检查」与「检查过、很干净」就会长得一模一样。</p>
 */
@DisplayName("MA-8 挂点④：附件正文扫描")
class ChatAttachmentParseWorkerScanTest {

    private ChatAttachmentMapper chatAttachmentMapper;
    private ArtifactStorage artifactStorage;
    private DocumentParserRouter documentParserRouter;
    private SensitiveScanRecorder sensitiveScanRecorder;
    private ChatAttachmentParseWorker worker;

    @BeforeEach
    void setUp() {
        chatAttachmentMapper = mock(ChatAttachmentMapper.class);
        artifactStorage = mock(ArtifactStorage.class);
        documentParserRouter = mock(DocumentParserRouter.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        sensitiveScanRecorder = mock(SensitiveScanRecorder.class);

        worker = new ChatAttachmentParseWorker(chatAttachmentMapper, artifactStorage,
                documentParserRouter, knowledgeBaseService, sensitiveScanRecorder);
        // 非 @Value 注入的字段（contextWindowTokens）在纯单测下走默认 128000
        ReflectionTestUtils.setField(worker, "contextWindowTokens", 128000);
    }

    /** 构造一份「可解析」的附件与解析结果，减少各用例重复样板。 */
    private void stubParsable(String text) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(77L);
        attachment.setConversationId(55L);
        attachment.setFileName("application.yml");
        attachment.setMimeType("application/x-yaml");
        attachment.setStorageUrl("mem://a/77");

        when(chatAttachmentMapper.markParsing(77L)).thenReturn(1);
        when(chatAttachmentMapper.selectById(77L)).thenReturn(attachment);
        when(documentParserRouter.find(any())).thenReturn(Optional.of(mock(DocumentParser.class)));
        when(artifactStorage.load("mem://a/77")).thenReturn(new byte[] {1, 2, 3});
        when(documentParserRouter.parse(any(), any()))
                .thenReturn(ParsedContent.ofText(text, "UTF-8", false));
    }

    @Nested
    @DisplayName("三态语义（落库列的核心约定）")
    class ThreeStateSemantics {

        @Test
        @DisplayName("命中 ⇒ hitCount 落实际条数，rules 落规则 code")
        void hitPersistsCountAndRules() {
            stubParsable("spring:\n  datasource:\n    password: X9k2mP4v\n");
            when(sensitiveScanRecorder.isEnabled()).thenReturn(true);
            when(sensitiveScanRecorder.scanAttachmentAndRecord(
                    eq(SensitiveScanPoint.ATTACHMENT), anyString(), anyString(), eq(55L), eq(77L)))
                    .thenReturn(new SensitiveScanRecorder.ScanSummary(
                            1, Set.of(SensitiveScanRule.CONFIG_SECRET), false));

            worker.parse(77L);

            verify(chatAttachmentMapper).markReady(eq(77L), anyString(), any(), any(),
                    anyString(), anyString(), eq(1), eq("config_secret"));
        }

        @Test
        @DisplayName("扫描开启但未命中 ⇒ 落 0，rules 为 null（不是「未扫描」）")
        void noHitPersistsZero() {
            stubParsable("key: value\n");
            when(sensitiveScanRecorder.isEnabled()).thenReturn(true);
            when(sensitiveScanRecorder.scanAttachmentAndRecord(any(), anyString(), anyString(),
                    anyLong(), anyLong()))
                    .thenReturn(SensitiveScanRecorder.ScanSummary.none());

            worker.parse(77L);

            // 0 与 null 语义不同：0 = 扫过且干净，前端两种都不提示，但库里必须可区分
            verify(chatAttachmentMapper).markReady(eq(77L), anyString(), any(), any(),
                    anyString(), anyString(), eq(0), isNull());
        }

        @Test
        @DisplayName("★ 扫描开关关闭 ⇒ 落 null（未扫描），绝不能写 0")
        void scanDisabledPersistsNull() {
            stubParsable("password: X9k2mP4v\n");
            when(sensitiveScanRecorder.isEnabled()).thenReturn(false);

            worker.parse(77L);

            // 写 0 会让「扫描被关掉了」在库里长成「扫过没命中」——观察期数据直接失真
            verify(chatAttachmentMapper).markReady(eq(77L), anyString(), any(), any(),
                    anyString(), anyString(), isNull(), isNull());
            // 开关关闭时不应该调用扫描
            verify(sensitiveScanRecorder, never()).scanAttachmentAndRecord(any(), anyString(),
                    anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("旁路纪律：扫描绝不影响解析结果")
    class BypassDiscipline {

        @Test
        @DisplayName("★ 扫描抛异常 ⇒ markReady 仍执行（观测不得伤主链）")
        void scanFailureDoesNotBlockReady() {
            stubParsable("password: X9k2mP4v\n");
            when(sensitiveScanRecorder.isEnabled()).thenReturn(true);
            // 模拟扫描器内部炸掉（recorder 契约是「绝不抛出」，但调用点不能假定它一定守约）
            when(sensitiveScanRecorder.scanAttachmentAndRecord(any(), anyString(), anyString(),
                    anyLong(), anyLong()))
                    .thenThrow(new RuntimeException("boom"));

            // 不应抛出：扫描是旁路，解析结果必须照常落库
            try {
                worker.parse(77L);
            } catch (RuntimeException e) {
                // 本用例断言「不阻断」，若 recorder 真抛出，说明契约在调用点被破坏了
                throw new AssertionError("扫描异常不应冒泡到解析主链", e);
            }

            verify(chatAttachmentMapper).markReady(eq(77L), anyString(), any(), any(),
                    anyString(), anyString(), isNull(), isNull());
        }

        @Test
        @DisplayName("解析被抢领（markParsing 返回 0）⇒ 不扫描、不落库")
        void skipsWhenAlreadyClaimed() {
            when(chatAttachmentMapper.markParsing(77L)).thenReturn(0);

            worker.parse(77L);

            verify(chatAttachmentMapper, never()).markReady(any(), anyString(), any(), any(),
                    anyString(), anyString(), any(), any());
            verify(sensitiveScanRecorder, never()).scanAttachmentAndRecord(any(), anyString(),
                    anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("失败路径：不落正文，也不写扫描列")
    class FailurePath {

        @Test
        @DisplayName("解析异常 ⇒ FAILED，不写 markReady（扫描列保持 null）")
        void parseFailureDoesNotTouchScanColumns() {
            ChatAttachment attachment = new ChatAttachment();
            attachment.setId(77L);
            attachment.setConversationId(55L);
            attachment.setFileName("bad.pdf");
            attachment.setMimeType("application/pdf");
            attachment.setStorageUrl("mem://a/bad");

            when(chatAttachmentMapper.markParsing(77L)).thenReturn(1);
            when(chatAttachmentMapper.selectById(77L)).thenReturn(attachment);
            when(documentParserRouter.find(any())).thenReturn(Optional.of(mock(DocumentParser.class)));
            when(artifactStorage.load(anyString())).thenReturn(new byte[] {1});
            when(documentParserRouter.parse(any(), any()))
                    .thenThrow(new AttachmentParseException("文件损坏"));

            worker.parse(77L);

            verify(chatAttachmentMapper).markFailed(eq(77L), anyString());
            // 解析失败没有正文可扫 ⇒ 扫描列必须留 null（未扫描），不能写 0
            verify(chatAttachmentMapper, never()).markReady(any(), anyString(), any(), any(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("无解析器（图片）⇒ SKIPPED，不扫描")
        void unsupportedKindSkipped() {
            ChatAttachment attachment = new ChatAttachment();
            attachment.setId(77L);
            attachment.setFileName("photo.png");
            attachment.setMimeType("image/png");
            attachment.setParseStatus(ChatAttachmentParseStatus.PENDING);

            when(chatAttachmentMapper.markParsing(77L)).thenReturn(1);
            when(chatAttachmentMapper.selectById(77L)).thenReturn(attachment);
            when(documentParserRouter.find(any())).thenReturn(Optional.empty());

            worker.parse(77L);

            verify(chatAttachmentMapper).markSkipped(eq(77L), anyString());
            verify(sensitiveScanRecorder, never()).scanAttachmentAndRecord(any(), anyString(),
                    anyString(), anyLong(), anyLong());
        }
    }
}
