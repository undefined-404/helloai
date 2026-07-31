package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LocalArtifactStorage 单元测试：落盘/读取往返、storageUrl 协议、
 * 路径穿越防护与文件名清洗（@TempDir 隔离，不触碰真实数据目录）。
 */
@DisplayName("LocalArtifactStorage 本地产物存储")
class LocalArtifactStorageTest {

    @TempDir
    Path tempDir;

    private LocalArtifactStorage storage;

    @BeforeEach
    void setUp() {
        ArtifactStorageProperties properties = new ArtifactStorageProperties();
        properties.setLocalBaseDir(tempDir.toString());
        properties.setBucket("test-bucket");
        storage = new LocalArtifactStorage(properties);
    }

    @Test
    @DisplayName("store 后 load 往返一致，storageUrl 为 local://{bucket}/{objectKey}")
    void shouldStoreAndLoadRoundTrip() {
        byte[] content = "# 产出内容".getBytes(StandardCharsets.UTF_8);

        StoredArtifact stored = storage.store(123L, "报告.md", content);

        assertThat(stored.storageUrl()).startsWith("local://test-bucket/123/");
        assertThat(stored.bucketName()).isEqualTo("test-bucket");
        assertThat(stored.objectKey()).startsWith("123/").endsWith("-报告.md");
        assertThat(stored.fileSize()).isEqualTo(content.length);
        assertThat(storage.load(stored.storageUrl())).isEqualTo(content);
    }

    @Test
    @DisplayName("supports 仅认 local:// 前缀")
    void shouldSupportOnlyLocalPrefix() {
        assertThat(storage.supports("local://bucket/1/x.md")).isTrue();
        assertThat(storage.supports("minio://bucket/1/x.md")).isFalse();
        assertThat(storage.supports(null)).isFalse();
    }

    @Test
    @DisplayName("load 路径穿越（..）被拒绝")
    void shouldRejectPathTraversalOnLoad() {
        assertThatThrownBy(() -> storage.load("local://test-bucket/../../etc/passwd"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("load 文件不存在抛 BizException")
    void shouldThrowWhenFileMissing() {
        assertThatThrownBy(() -> storage.load("local://test-bucket/999/20260101/none-x.md"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("load 非法地址格式抛 BizException")
    void shouldRejectMalformedUrl() {
        assertThatThrownBy(() -> storage.load("minio://bucket/1/x.md"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> storage.load("local://onlybucket"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("文件名清洗：保留字符替换、点前缀剥离、空白兜底、超长截断保扩展名")
    void shouldSanitizeFileName() {
        assertThat(LocalArtifactStorage.sanitizeFileName("a/b\\c:d.md")).isEqualTo("a_b_c_d.md");
        assertThat(LocalArtifactStorage.sanitizeFileName("..\\..\\evil.md")).isEqualTo("_.._evil.md");
        assertThat(LocalArtifactStorage.sanitizeFileName("...hidden.md")).isEqualTo("hidden.md");
        assertThat(LocalArtifactStorage.sanitizeFileName(null)).isEqualTo("output.md");
        assertThat(LocalArtifactStorage.sanitizeFileName("  ")).isEqualTo("output.md");
        String longName = "n".repeat(120) + ".md";
        String sanitized = LocalArtifactStorage.sanitizeFileName(longName);
        assertThat(sanitized).hasSizeLessThanOrEqualTo(100).endsWith(".md");
    }
}
