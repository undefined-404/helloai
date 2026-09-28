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

    /** 存储类型：local（本地磁盘）/ minio（对象存储），对应 ArtifactStorage 实现的主路由选择。 */
    private String type = "local";

    /**
     * local 存储的根目录（相对路径基于进程工作目录）。
     * 实际文件路径为 {@code {local-base-dir}/{objectKey}}。
     */
    private String localBaseDir = "./data/artifacts";

    /** local 存储的逻辑 bucket 名，参与 storageUrl（local://{bucket}/{objectKey}）与附件元数据。 */
    private String bucket = "helloai-local";

    /** MinIO endpoint（type=minio 时生效），本地 docker compose 默认 29000 端口。 */
    private String minioEndpoint = "http://localhost:29000";

    /** MinIO access key（type=minio 时生效）。 */
    private String minioAccessKey = "minioadmin";

    /** MinIO secret key（type=minio 时生效）。 */
    private String minioSecretKey = "minioadmin123";

    /** MinIO bucket 名（type=minio 时生效），参与 storageUrl（minio://{bucket}/{objectKey}）与附件元数据。 */
    private String minioBucket = "helloai-artifacts";

    /** 单次执行物化的最大文件数，超出部分丢弃并记日志（防解析异常导致附件爆炸）。 */
    private int maxFiles = 10;

    /** 单文件最大字节数，超限文件跳过物化。默认 5MB（产出为文本，足够宽裕）。 */
    private long maxFileSize = 5_242_880L;

    // ================================================================
    // 存储对账巡检（DB attachment ↔ 对象存储真实对象）
    // ================================================================

    /**
     * 对账巡检开关（只读，默认开启）。
     * 每轮枚举桶内真实对象与 attachment 表双向比对，悬空记录 / 孤儿对象 / 字节不符
     * 一律只告警不修数据，供人工排查环境切换、存储漂移。
     */
    private boolean reconcileEnabled = true;

    /**
     * 孤儿对象清理开关（<b>会删除对象，默认关闭</b>）。
     *
     * <p>首次引入删除能力：全仓此前没有任何删除对象的代码。开启前必须先跑若干轮
     * dry-run 巡检、人工确认孤儿清单无误。关闭时巡检照常报告孤儿，只是不删。</p>
     */
    private boolean orphanCleanupEnabled = false;

    /**
     * 孤儿判定时间窗（小时，默认 24）：对象 {@code lastModified} 早于
     * {@code now - 该值} 才视为可清理孤儿。
     *
     * <p>用于规避"已上传对象、尚未写 attachment 记录"的时间窗——平台侧
     * {@code store()} 与 {@code register()} 是两步，外部 Agent 先传对象再登记也是两步，
     * 窗口内对象暂时无人引用属正常，不得当垃圾删除。时间窗不足或无法取得
     * {@code lastModified} 的对象一律跳过。</p>
     */
    private int orphanMinAgeHours = 24;

    /** 单轮最多删除的孤儿对象数（默认 200），防止误判导致批量损伤。 */
    private int orphanMaxDeletesPerRound = 200;
}
