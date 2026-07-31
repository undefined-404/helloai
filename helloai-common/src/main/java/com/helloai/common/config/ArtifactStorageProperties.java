package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 执行产出物化存储配置（方案2：附件物化 + 本地存储抽象）。
 *
 * <p>执行成功后由 {@code ExecutionArtifactService} 把 lastExecution.output
 * 物化为附件文件，经 {@code ArtifactStorage} 落盘并注册 attachment 元数据，
 * 详见 {@code doc/design/HelloAI_执行产出物化与结构化多文件产出方案.md}。</p>
 *
 * <p>本配置仿 {@link DoorbellProperties} 风格集中管理物化参数，
 * 全部字段带默认值，yml 未配置也可直接运行。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.storage")
public class ArtifactStorageProperties {

    /** 是否启用执行产出物化。关闭时执行链不再自动生成附件，仅保留 context.lastExecution。 */
    private boolean enabled = true;

    /** 存储类型。当前仅实现 local（本地磁盘）；预留 minio/s3 扩展位。 */
    private String type = "local";

    /**
     * local 存储的根目录（相对路径基于进程工作目录）。
     * 实际文件路径为 {@code {local-base-dir}/{objectKey}}。
     */
    private String localBaseDir = "./data/artifacts";

    /** local 存储的逻辑 bucket 名，参与 storageUrl（local://{bucket}/{objectKey}）与附件元数据。 */
    private String bucket = "helloai-local";

    /** 单次执行物化的最大文件数，超出部分丢弃并记日志（防解析异常导致附件爆炸）。 */
    private int maxFiles = 10;

    /** 单文件最大字节数，超限文件跳过物化。默认 5MB（产出为文本，足够宽裕）。 */
    private long maxFileSize = 5_242_880L;
}
