package com.helloai.core.knowledge;

import com.helloai.common.base.BizException;
import com.helloai.common.config.KbProperties;
import com.helloai.core.knowledge.KnowledgeSearchResult.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeBaseService —— REF-4.2 摄入/检索 + REF-4.1 预算/摘除判据")
class KnowledgeBaseServiceTest {

    @Mock
    KnowledgeEmbedder embedder;
    @Mock
    KnowledgeRepository repository;

    private KbProperties props() {
        KbProperties p = new KbProperties();
        p.setEnabled(true);
        p.setChunkSize(4);
        p.setChunkOverlap(1);
        p.setDefaultResults(2);
        p.setMaxResults(8);
        p.setCharBudget(1000);
        return p;
    }

    private KnowledgeBaseService svc(KbProperties p) {
        return new KnowledgeBaseService(embedder, repository, p);
    }

    @Test
    @DisplayName("总开关关 ⇒ ingest 显式拒绝（REF-6.13 默认关闭）")
    void ingest_whenDisabled_rejects() {
        KbProperties p = props();
        p.setEnabled(false);
        assertThatThrownBy(() -> svc(p).ingest("s", "t"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("无 KB ⇒ hasKnowledge=false（摘工具判据）")
    void hasKnowledge_false_whenEmpty() {
        when(repository.count()).thenReturn(0L);
        assertThat(svc(props()).hasKnowledge()).isFalse();
        verify(repository).count();
    }

    @Test
    @DisplayName("库内有数据 ⇒ hasKnowledge=true")
    void hasKnowledge_true_whenNonEmpty() {
        when(repository.count()).thenReturn(3L);
        assertThat(svc(props()).hasKnowledge()).isTrue();
    }

    @Test
    @DisplayName("ingest：分片→嵌入→清旧→逐片落库")
    void ingest_chunksEmbedsAndPersists() {
        KbProperties p = props();
        p.setChunkSize(4);
        p.setChunkOverlap(1); // step=3

        // "abcdefgh" => [0,4)"abcd" / [3,7)"defg" / [6,8)"gh"
        when(embedder.embed(anyList())).thenReturn(List.of(
                new double[]{0.1, 0.11}, new double[]{0.2, 0.22}, new double[]{0.3, 0.33}));
        doNothing().when(repository).clearSource(anyString());

        int n = svc(p).ingest("src-1", "abcdefgh");

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<String> srcCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> idxCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> contentCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<double[]> vecCap = ArgumentCaptor.forClass(double[].class);
        verify(repository, org.mockito.Mockito.times(3))
                .insertChunk(srcCap.capture(), idxCap.capture(), contentCap.capture(), vecCap.capture());
        assertThat(contentCap.getAllValues()).containsExactly("abcd", "defg", "gh");
        assertThat(idxCap.getAllValues()).containsExactly(0, 1, 2);
        verify(repository).clearSource("src-1");
    }

    @Test
    @DisplayName("ingest 重摄同 source 前先清旧")
    void ingest_clearsSourceFirst() {
        KbProperties p = props();
        p.setChunkSize(100);
        when(embedder.embed(anyList())).thenReturn(List.of(new double[]{0.5, 0.6}));
        svc(p).ingest("s", "single");
        // clearSource 与 insertChunk 均命中；先清后写（顺序由 vi 编排体现，此处断言调用了 clearSource）
        verify(repository).clearSource("s");
        verify(repository).insertChunk(anyString(), anyInt(), anyString(), any(double[].class));
    }

    @Test
    @DisplayName("search：top-k 受 maxResults 收敛，超预算截断并标记 truncated")
    void search_budgetTruncates() {
        KbProperties p = props();
        p.setCharBudget(10);
        when(embedder.embedOne(anyString())).thenReturn(new double[]{0.9});
        when(repository.search(any(double[].class), anyInt())).thenReturn(List.of(
                new KnowledgeHit(1L, "a", 0, "0123456789abcdef", 0.1), // 16 字符
                new KnowledgeHit(2L, "b", 0, "xyz", 0.2)));

        KnowledgeSearchResult r = svc(p).search("q", 99);

        assertThat(r.query()).isEqualTo("q");
        assertThat(r.truncated()).isTrue();
        assertThat(r.totalChars()).isLessThanOrEqualTo(10);
        // 第一个命中被截断到预算内；第二个因预算耗尽未入详情（remaining<=0 break）
        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).content().length()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("search：空 query 返回空结果，不调嵌入")
    void search_blankQuery_returnsEmpty() {
        KnowledgeSearchResult r = svc(props()).search("  ", 3);
        assertThat(r.hits()).isEmpty();
        verify(embedder, never()).embedOne(anyString());
    }

    @Test
    @DisplayName("search：maxResults 收敛到上限 8")
    void search_clampsMaxResults() {
        KbProperties p = props();
        p.setMaxResults(8);
        when(embedder.embedOne(anyString())).thenReturn(new double[]{0.9});
        when(repository.search(any(double[].class), anyInt())).thenReturn(List.of());
        svc(p).search("q", 99);
        verify(repository).search(any(double[].class), org.mockito.ArgumentMatchers.eq(8));
    }

    @Test
    @DisplayName("search：缺省 maxResults 用 defaultResults")
    void search_defaultResultsWhenNull() {
        KbProperties p = props();
        p.setDefaultResults(2);
        when(embedder.embedOne(anyString())).thenReturn(new double[]{0.9});
        when(repository.search(any(double[].class), anyInt())).thenReturn(List.of());
        svc(p).search("q", null);
        verify(repository).search(any(double[].class), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    @DisplayName("chunkByKey：命中返回分片（引用溯源取原文）")
    void chunkByKey_hit() {
        when(repository.findBySourceIdx("manual-rag", 0))
                .thenReturn(java.util.Optional.of(new KnowledgeHit(1L, "manual-rag", 0, "原文", 0.1)));
        var hit = svc(props()).chunkByKey("manual-rag", 0);
        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("原文");
        assertThat(hit.get().source()).isEqualTo("manual-rag");
    }

    @Test
    @DisplayName("chunkByKey：找不到返回空 Optional")
    void chunkByKey_missing() {
        when(repository.findBySourceIdx("x", 9)).thenReturn(java.util.Optional.empty());
        assertThat(svc(props()).chunkByKey("x", 9)).isEmpty();
    }

    @Test
    @DisplayName("chunkByKey：总开关关 ⇒ 显式拒绝（REF-6.13）")
    void chunkByKey_whenDisabled_rejects() {
        KbProperties p = props();
        p.setEnabled(false);
        assertThatThrownBy(() -> svc(p).chunkByKey("x", 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("search：maxResults 缺省时不用 full 上限，避免默认拉满")
    void search_maxResultsNotAppliedWhenNull() {
        KbProperties p = props();
        p.setDefaultResults(2);
        p.setMaxResults(8);
        when(embedder.embedOne(anyString())).thenReturn(new double[]{0.9});
        when(repository.search(any(double[].class), anyInt())).thenReturn(List.of());
        svc(p).search("q", null);
        verify(repository).search(any(double[].class), org.mockito.ArgumentMatchers.eq(2));
    }
}