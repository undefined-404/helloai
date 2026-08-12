package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MinioArtifactStorage 单元测试：storageUrl 协议、objectKey 目录分层规则、
 * store 上传参数与 load 读取（MinioClient 以 mock 注入，不依赖真实对象存储）。
 */
@DisplayName("MinioArtifactStorage MinIO 产物存储")
class MinioArtifactStorageTest {

    private ArtifactStorageProperties properties;
    private MinioClient client;
    private MinioArtifactStorage storage;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        properties.setMinioBucket("test-bucket");
        client = mock(MinioClient.class);
        storage = new MinioArtifactStorage(properties);
        storage.client = client; // 同包直接注入 mock，跳过懒创建
    }

    @Test
    @DisplayName("store 上传到 MinIO，storageUrl 为 minio://{bucket}/{objectKey}，目录按 ownerName/年/月/taskId/subTaskId 组织")
    void shouldStoreAndReturnUrl() throws Exception {
        byte[] content = "# 产出".getBytes(StandardCharsets.UTF_8);

        StoredArtifact stored = storage.store("tester", 7L, 123L, "报告.md", content);

        assertThat(stored.storageUrl()).startsWith("minio://test-bucket/tester/");
        assertThat(stored.bucketName()).isEqualTo("test-bucket");
        assertThat(stored.objectKey()).startsWith("tester/")
                .containsPattern("\\d{4}/\\d{2}/7/123/")
                .endsWith("-报告.md");
        assertThat(stored.fileSize()).isEqualTo(content.length);
        verify(client).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("load 从 MinIO 读取对象内容")
    void shouldLoadFromMinio() throws Exception {
        byte[] data = "# 内容".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse resp = mock(GetObjectResponse.class);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(resp);
        when(resp.readAllBytes()).thenReturn(data);

        assertThat(storage.load("minio://test-bucket/tester/2026/08/7/123/aaaa-报告.md")).isEqualTo(data);
        verify(client).getObject(any(GetObjectArgs.class));
    }

    @Test
    @DisplayName("supports 仅认 minio:// 前缀")
    void shouldSupportOnlyMinioPrefix() {
        assertThat(storage.supports("minio://bucket/1/x.md")).isTrue();
        assertThat(storage.supports("local://bucket/1/x.md")).isFalse();
        assertThat(storage.supports(null)).isFalse();
    }

    @Test
    @DisplayName("load 非法地址格式抛 BizException")
    void shouldRejectMalformedUrl() {
        assertThatThrownBy(() -> storage.load("local://bucket/1/x.md"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> storage.load("minio://onlybucket"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("storageType 为 minio")
    void shouldReportStorageType() {
        assertThat(storage.storageType()).isEqualTo("minio");
    }
}
