package com.helloai.core.task.adapter;

import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.mapper.AttachmentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArtifactReferencePortAdapter} 单元测试（§6.146 端口反转配套）：
 * 验证 attachment 实体 → {@link ArtifactReference} 值对象的映射
 * （storageUrl / fileSize 原样搬运、空行保留、不泄漏实体），
 * 以及 {@code G-047} 新增的「按 key 集合批量查活跃行」护栏查询。
 *
 * <p><b>桩对象是 Mapper 而不是 Service</b>：{@code G-047} 修订后本适配器为避开
 * Spring 构造器环（{@code ChatAttachmentServiceImpl} → 本类 → {@code AttachmentServiceImpl}
 * → {@code AttachmentObjectPurgeSupport} → {@code ChatAttachmentReferencePort} → 回到起点）
 * 改为直接依赖同域 {@link AttachmentMapper}。沿用旧的 {@code @Mock AttachmentService}
 * 会让注入字段恒为 null，三条用例全变 NPE——不是代码回归，是测试没跟上改造。</p>
 */
@ExtendWith(MockitoExtension.class)
class ArtifactReferencePortAdapterTest {

    @Mock
    private AttachmentMapper attachmentMapper;

    @InjectMocks
    private ArtifactReferencePortAdapter adapter;

    // ── listAllIncludingDeleted ─────────────────────────────

    @Test
    @DisplayName("逐行映射：storageUrl/fileSize 原样搬运，顺序保持")
    void shouldMapRowsInOrder() {
        when(attachmentMapper.selectAllIncludingDeleted())
                .thenReturn(List.of(row("minio://b/k1.md", 10L), row("local://b/k2.md", null)));

        List<ArtifactReference> refs = adapter.listAllIncludingDeleted();

        assertThat(refs).containsExactly(
                new ArtifactReference("minio://b/k1.md", 10L),
                new ArtifactReference("local://b/k2.md", null));
    }

    @Test
    @DisplayName("空行保留：地址为 null 的行不丢弃（对账侧需感知'有行无地址'）")
    void shouldKeepBlankRows() {
        when(attachmentMapper.selectAllIncludingDeleted()).thenReturn(List.of(row(null, null)));

        List<ArtifactReference> refs = adapter.listAllIncludingDeleted();

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).storageUrl()).isNull();
        assertThat(refs.get(0).fileSize()).isNull();
    }

    @Test
    @DisplayName("无行返回空列表（绝不返回 null）")
    void shouldReturnEmptyWhenNoRows() {
        when(attachmentMapper.selectAllIncludingDeleted()).thenReturn(List.of());

        assertThat(adapter.listAllIncludingDeleted()).isEmpty();
    }

    // ── listActiveObjectKeysIn（G-047 跨表护栏） ─────────────

    @Test
    @DisplayName("护栏查询：key 集合原样下传，返回集去重后交给调用方 contains")
    void shouldPassKeysThroughAndDedupe() {
        // 同一 objectKey 可能有多条活跃行（历史上按 task / subTask 各落一行），必须去重
        when(attachmentMapper.selectActiveObjectKeysIn(List.of("k1", "k2")))
                .thenReturn(List.of("k1", "k1", "k2"));

        Set<String> active = adapter.listActiveObjectKeysIn(List.of("k1", "k2"));

        assertThat(active).containsExactlyInAnyOrder("k1", "k2");
        verify(attachmentMapper).selectActiveObjectKeysIn(List.of("k1", "k2"));
    }

    @Test
    @DisplayName("护栏查询：空入参不发 SQL（否则 IN () 语法错，且零 key 本就无「被引用」可能）")
    void shouldSkipQueryWhenKeysEmpty() {
        assertThat(adapter.listActiveObjectKeysIn(List.of())).isEmpty();
        assertThat(adapter.listActiveObjectKeysIn(null)).isEmpty();

        verify(attachmentMapper, never()).selectActiveObjectKeysIn(any());
    }

    @Test
    @DisplayName("护栏查询：一条活跃行都没有 ⇒ 空集（调用方据此判定可安全回收）")
    void shouldReturnEmptyWhenNothingActive() {
        when(attachmentMapper.selectActiveObjectKeysIn(List.of("gone"))).thenReturn(List.of());

        assertThat(adapter.listActiveObjectKeysIn(List.of("gone"))).isEmpty();
    }

    private static Attachment row(String storageUrl, Long fileSize) {
        Attachment a = new Attachment();
        a.setStorageUrl(storageUrl);
        a.setFileSize(fileSize);
        return a;
    }
}