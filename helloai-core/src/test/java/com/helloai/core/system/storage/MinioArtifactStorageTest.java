package com.helloai.core.system.storage;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    // ================================================================
    // exists / validateAddress / load 404（僵尸附件防治）
    // ================================================================

    /** 构造一个带指定 S3 错误码的 ErrorResponseException（模拟 pgjdbc 之外的存储侧应答）。 */
    private static ErrorResponseException s3Error(String code) {
        return new ErrorResponseException(
                new ErrorResponse(code, "mock-" + code, "test-bucket", "tester/2026/08/7/123/a.md",
                        null, null, null),
                null, null);
    }

    private static final String KEY = "minio://test-bucket/tester/2026/08/7/123/a.md";

    @Test
    @DisplayName("exists: statObject 成功 → true")
    void exists_shouldReturnTrueWhenStatObjectOk() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(null);

        assertThat(storage.exists(KEY)).isTrue();
        verify(client).statObject(any(StatObjectArgs.class));
    }

    @Test
    @DisplayName("exists: NoSuchKey → false（确定不存在）")
    void exists_shouldReturnFalseOnNoSuchKey() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(s3Error("NoSuchKey"));

        assertThat(storage.exists(KEY)).isFalse();
    }

    @Test
    @DisplayName("exists: 鉴权失败必须抛异常而不是 false —— 否则一次配置漂移会被误读成'对象全丢'")
    void exists_shouldThrowInsteadOfFalseOnAuthFailure() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(s3Error("SignatureDoesNotMatch"));

        assertThatThrownBy(() -> storage.exists(KEY))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("存在性校验失败");
    }

    @Test
    @DisplayName("load: NoSuchKey → 404（'文件缺失'语义，区别于 500 '服务故障'）")
    void load_shouldMapNoSuchKeyTo404() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(s3Error("NoSuchKey"));

        BizException ex = catchThrowableOfType(
                () -> storage.load(KEY), BizException.class);
        assertThat(ex.getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("load: 鉴权失败仍为 500（不得伪装成文件缺失）")
    void load_shouldKeep500OnAuthFailure() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(s3Error("SignatureDoesNotMatch"));

        BizException ex = catchThrowableOfType(
                () -> storage.load(KEY), BizException.class);
        assertThat(ex.getCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("validateAddress: bucket 段为平台桶时通过")
    void validateAddress_shouldPassOnPlatformBucket() {
        storage.validateAddress(KEY); // 不抛即通过
    }

    @Test
    @DisplayName("validateAddress: 把 Agent 注册名当 bucket（minio://trae-executor/...）→ 400")
    void validateAddress_shouldRejectForeignBucket() {
        BizException ex = catchThrowableOfType(
                () -> storage.validateAddress("minio://trae-executor/2026/09/1/2/x.md"), BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("trae-executor").contains("test-bucket");
    }

    @Test
    @DisplayName("validateAddress: objectKey 含路径穿越 → 400")
    void validateAddress_shouldRejectPathTraversal() {
        BizException ex = catchThrowableOfType(
                () -> storage.validateAddress("minio://test-bucket/a/../../etc/passwd"), BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("validateAddress: 非本实现协议直接放行（交给其它实现判断）")
    void validateAddress_shouldIgnoreForeignProtocol() {
        storage.validateAddress("https://example.com/a.md"); // 不抛
        storage.validateAddress(null);                      // 不抛
    }

    // ================================================================
    // listObjects / removeObject（对账巡检用）
    // ================================================================

    /** MinioClient 返回的是 Iterable<Result<Item>>，用 mock 拼一条。 */
    @SuppressWarnings("unchecked")
    private void stubListing(String objectName, long size, ZonedDateTime lastModified) throws Exception {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(objectName);
        when(item.size()).thenReturn(size);
        when(item.lastModified()).thenReturn(lastModified);
        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(result));
    }

    @Test
    @DisplayName("listObjects: 枚举桶内对象，带 objectKey / size / lastModified")
    void listObjects_shouldEnumerateBucket() throws Exception {
        ZonedDateTime time = ZonedDateTime.parse("2026-09-24T08:27:41Z");
        stubListing("workBuddy-executor/2026/09/1/2/ab-x.md", 9525L, time);

        List<ArtifactStorage.StoredObject> objects = storage.listObjects("test-bucket", "workBuddy-executor/");

        assertThat(objects).hasSize(1);
        assertThat(objects.get(0).objectKey()).isEqualTo("workBuddy-executor/2026/09/1/2/ab-x.md");
        assertThat(objects.get(0).size()).isEqualTo(9525L);
        assertThat(objects.get(0).lastModified().toInstant()).isEqualTo(time.toInstant());
    }

    @Test
    @DisplayName("listObjects: lastModified 缺失时置 null（不做时间判定，由调用方保守跳过）")
    void listObjects_shouldTolerateNullLastModified() throws Exception {
        stubListing("a/b.md", 1L, null);

        List<ArtifactStorage.StoredObject> objects = storage.listObjects("test-bucket", null);

        assertThat(objects.get(0).lastModified()).isNull();
    }

    @Test
    @DisplayName("listObjects: 枚举失败必须抛出（静默空列表会让调用方误判'桶是空的'）")
    void listObjects_shouldFailLoudly() throws Exception {
        // MinioClient.listObjects 未声明 IOException，桩用非受检异常（实现侧统一 catch Exception 包装）
        when(client.listObjects(any(ListObjectsArgs.class))).thenThrow(new IllegalStateException("conn reset"));

        assertThatThrownBy(() -> storage.listObjects("test-bucket", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("列表读取失败");
    }

    @Test
    @DisplayName("removeObject: 调用 S3 删除接口")
    void removeObject_shouldCallRemove() throws Exception {
        storage.removeObject("test-bucket", "a/b.md");

        verify(client).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("removeObject: 对象已不存在视为成功（幂等，便于对账重复执行）")
    void removeObject_shouldBeIdempotent() throws Exception {
        // removeObject 返回 void，只能用 doThrow 桩
        doThrow(s3Error("NoSuchKey")).when(client).removeObject(any(RemoveObjectArgs.class));

        storage.removeObject("test-bucket", "a/b.md"); // 不抛
    }

    @Test
    @DisplayName("removeObject: 鉴权失败必须抛出（不得伪装成删除成功）")
    void removeObject_shouldThrowOnAuthFailure() throws Exception {
        doThrow(s3Error("SignatureDoesNotMatch")).when(client).removeObject(any(RemoveObjectArgs.class));

        assertThatThrownBy(() -> storage.removeObject("test-bucket", "a/b.md"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("删除失败");
    }
}
