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
    @DisplayName("store 后 load 往返一致，storageUrl 为 local://{bucket}/{objectKey}，目录按 ownerName/年/月/taskId/subTaskId 组织")
    void shouldStoreAndLoadRoundTrip() {
        byte[] content = "# 产出内容".getBytes(StandardCharsets.UTF_8);

        StoredArtifact stored = storage.store("tester", 7L, 123L, "报告.md", content);

        assertThat(stored.storageUrl()).startsWith("local://test-bucket/tester/");
        assertThat(stored.bucketName()).isEqualTo("test-bucket");
        assertThat(stored.objectKey()).startsWith("tester/")
                .containsPattern("\\d{4}/\\d{2}/7/123/")
                .endsWith("-报告.md");
        assertThat(stored.fileSize()).isEqualTo(content.length);
        assertThat(storage.load(stored.storageUrl())).isEqualTo(content);
    }

    @Test
    @DisplayName("ownerName 为空或含路径分隔符时清洗为安全目录段")
    void shouldSanitizeOwnerNameInObjectKey() {
        StoredArtifact stored = storage.store("../evil\\name", 7L, 123L, "a.md", new byte[]{1});

        assertThat(stored.objectKey()).startsWith("_evil_name/");
        assertThat(storage.supports(stored.storageUrl())).isTrue();
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
        assertThatThrownBy(() -> storage.load("local://test-bucket/unknown/2026/01/9/999/none-x.md"))
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
        assertThat(ArtifactStorage.sanitizeFileName("a/b\\c:d.md")).isEqualTo("a_b_c_d.md");
        assertThat(ArtifactStorage.sanitizeFileName("..\\..\\evil.md")).isEqualTo("_.._evil.md");
        assertThat(ArtifactStorage.sanitizeFileName("...hidden.md")).isEqualTo("hidden.md");
        assertThat(ArtifactStorage.sanitizeFileName(null)).isEqualTo("output.md");
        assertThat(ArtifactStorage.sanitizeFileName("  ")).isEqualTo("output.md");
        String longName = "n".repeat(120) + ".md";
        String sanitized = ArtifactStorage.sanitizeFileName(longName);
        assertThat(sanitized).hasSizeLessThanOrEqualTo(100).endsWith(".md");
    }

    @Test
    @DisplayName("ownerName 清洗：路径分隔符替换、点前缀剥离、空白兜底、超长截断")
    void shouldSanitizeOwnerName() {
        assertThat(ArtifactStorage.sanitizeOwnerName("../evil/name")).isEqualTo("_evil_name");
        assertThat(ArtifactStorage.sanitizeOwnerName("...hidden")).isEqualTo("hidden");
        assertThat(ArtifactStorage.sanitizeOwnerName("  ")).isEqualTo("unknown");
        assertThat(ArtifactStorage.sanitizeOwnerName(null)).isEqualTo("unknown");
        assertThat(ArtifactStorage.sanitizeOwnerName("n".repeat(100))).hasSize(64);
    }
}
