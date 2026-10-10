package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台备份 / 恢复配置（REF-2，`D-2026-10-10-2`）。
 *
 * <p><b>为什么独立于 {@code spring.datasource}</b>：备份要的 host / port / database / username / password
 * 与数据源一致，但**不能直接依赖它**——数据源是 JDBC URL，而 {@code pg_dump} 要的是离散参数。
 * 本类给独立配置项，**留空时由 {@code BackupDatabaseTarget} 从 {@code spring.datasource.url} 派生**
 * （见 {@link #deriveFromDataSource}），从而既不重复配置、又不产生两处漂移。</p>
 *
 * <p><b>落点为什么不是 {@code helloai-artifacts}</b>：对账巡检以
 * {@code listObjects(bucket, null)} **枚举整桶**，凡无 DB 记录者即孤儿候选；
 * 备份若落共用桶，一旦开 {@code orphan-cleanup-enabled} 就会被当孤儿删掉。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.backup")
public class BackupProperties {

    /** 总开关。关闭时备份端点显式返回不可用，其余功能不受影响。 */
    private boolean enabled = true;

    /**
     * 备份产物桶。
     *
     * <p>**必须独立于 {@code helloai.storage.minio-bucket}** —— 那个桶参与对账巡检，
     * 备份对象在其中会被当作孤儿清理。</p>
     */
    private String bucket = "helloai-backups";

    /**
     * {@code pg_dump} 可执行文件路径。
     *
     * <p>空 ⇒ 按 PATH 查找 {@code pg_dump}（生产镜像由 Dockerfile 装 postgresql-client 后即如此）；
     * 本地开发可指向免安装 zip 版，如 {@code .tools/pgsql/bin/pg_dump.exe}。</p>
     */
    private String pgDumpPath = "";

    /** {@code pg_restore} 可执行文件路径。语义同 {@link #pgDumpPath}。 */
    private String pgRestorePath = "";

    // ── PG 连接（全留空 ⇒ 从 spring.datasource.url 派生） ──────────────

    /**
     * PG 主机；留空则派生。
     *
     * <p>以下五项刻意都用 {@code String}（而非 {@code Integer} 等）：一是它们**统一以「留空」表示派生**，
     * 二是 `@ConfigurationProperties` 把 yml 里的空值（`${VAR:}`）绑定到 {@code Integer} 的行为不确定，
     * 用 String 可以零风险地表达"未配置"。</p>
     */
    private String host = "";

    /** PG 端口；留空则派生（派生时按数值解析，非法值会显式报错）。 */
    private String port = "";

    /** 库名；留空则派生。 */
    private String database = "";

    /** 用户名；留空则派生。 */
    private String username = "";

    /** 口令；留空则派生。**只经进程 env 传递（PGPASSWORD），不进命令行**（避免密码出现在 ps / 进程列表）。 */
    private String password = "";

    // ── 调度与保留 ────────────────────────────────────────────────

    /** 是否启用定时自动备份。默认关 —— 开启前须确认宿主/镜像侧已具备 pg_dump。 */
    private boolean autoEnabled = false;

    /** 自动备份间隔（毫秒），默认 6 小时。 */
    private long autoIntervalMs = 21_600_000L;

    /**
     * **自动**备份保留份数（默认 7）。**手动备份永不参与淘汰**（`D-2026-10-10-2⑤`）。
     */
    private int autoRetentionCount = 7;

    // ── 执行安全 ──────────────────────────────────────────────────

    /** 单次 pg_dump / pg_restore 超时（秒），默认 30 分钟 —— 超时即视为失败并杀进程。 */
    private int commandTimeoutSeconds = 1800;

    /**
     * 备份产物在桶下的键前缀（一次备份一个前缀）。
     */
    private String keyPrefix = "backups";

    /** 是否有任一项连接参数留空（需要派生）。 */
    public boolean deriveFromDataSource() {
        return isBlank(host) || isBlank(port) || isBlank(database) || isBlank(username) || isBlank(password);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
