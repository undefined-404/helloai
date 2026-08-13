package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompositeArtifactStorage 单元测试：store 按 helloai.storage.type 路由主存储，
 * load/supports 按 storageUrl 协议前缀分派（存量 local 与新 minio 附件同时可读）。
 */
@DisplayName("CompositeArtifactStorage 存储路由")
class CompositeArtifactStorageTest {

    private final LocalArtifactStorage local = mock(LocalArtifactStorage.class);
    private final MinioArtifactStorage minio = mock(MinioArtifactStorage.class);

    private CompositeArtifactStorage composite(String type) {
        when(local.storageType()).thenReturn("local");
        when(minio.storageType()).thenReturn("minio");
        ArtifactStorageProperties properties = new ArtifactStorageProperties();
        properties.setType(type);
        ObjectProvider<ArtifactStorage> provider = mock(ObjectProvider.class);
        // Stream 只能消费一次：每次调用返回新实例
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(local, minio));
        return new CompositeArtifactStorage(properties, provider);
    }

    @Test
    @DisplayName("store 路由到配置 type 对应的主存储")
    void shouldRouteStoreToPrimary() {
        CompositeArtifactStorage composite = composite("minio");
        StoredArtifact expected = new StoredArtifact("minio://b/k", "b", "k", 1);
        when(minio.store("tester", 7L, 123L, "a.md", new byte[]{1})).thenReturn(expected);

        assertThat(composite.store("tester", 7L, 123L, "a.md", new byte[]{1})).isEqualTo(expected);
        verify(minio).store("tester", 7L, 123L, "a.md", new byte[]{1});
        verify(local, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("type=local 时 store 路由到本地存储")
    void shouldRouteStoreToLocal() {
        CompositeArtifactStorage composite = composite("local");
        StoredArtifact expected = new StoredArtifact("local://b/k", "b", "k", 1);
        when(local.store("tester", 7L, 123L, "a.md", new byte[]{1})).thenReturn(expected);

        assertThat(composite.store("tester", 7L, 123L, "a.md", new byte[]{1})).isEqualTo(expected);
        verify(local).store("tester", 7L, 123L, "a.md", new byte[]{1});
        verify(minio, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未配置的存储类型抛 IllegalStateException")
    void shouldRejectUnknownType() {
        CompositeArtifactStorage composite = composite("s3");

        assertThatThrownBy(() -> composite.store("t", 7L, 123L, "a.md", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("s3");
    }

    @Test
    @DisplayName("load 按协议前缀分派到对应实现（存量 local 与新 minio 同时可读）")
    void shouldDispatchLoadByPrefix() {
        CompositeArtifactStorage composite = composite("minio");
        when(minio.supports("minio://b/k")).thenReturn(true);
        when(local.supports("local://b/k")).thenReturn(true);
        when(minio.load("minio://b/k")).thenReturn(new byte[]{1});
        when(local.load("local://b/k")).thenReturn(new byte[]{2});

        assertThat(composite.load("minio://b/k")).isEqualTo(new byte[]{1});
        assertThat(composite.load("local://b/k")).isEqualTo(new byte[]{2});
    }

    @Test
    @DisplayName("supports 任一实现支持即返回 true")
    void shouldSupportAnyPrefix() {
        CompositeArtifactStorage composite = composite("minio");
        when(local.supports("local://b/k")).thenReturn(true);
        when(minio.supports("minio://b/k")).thenReturn(true);

        assertThat(composite.supports("local://b/k")).isTrue();
        assertThat(composite.supports("minio://b/k")).isTrue();
        assertThat(composite.supports("oss://b/k")).isFalse();
    }

    @Test
    @DisplayName("无实现支持的地址抛 BizException")
    void shouldRejectUnsupportedUrl() {
        CompositeArtifactStorage composite = composite("minio");

        assertThatThrownBy(() -> composite.load("oss://b/k"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("oss://b/k");
    }
}
