package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.common.config.BackupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PgDumpRunner} 的**纯逻辑**单测（不依赖真实 pg_dump、不依赖数据库）。
 *
 * <p>真跑 {@code pg_dump} / {@code pg_restore} 的验证在 e2e 脚本
 * （`verify-backup-restore.ps1`）——那里才有真实库与真实二进制；单测里不制造
 * "环境不可用即假失败"，与仓库既有取向一致（协作规约 §27）。</p>
 */
@DisplayName("PgDumpRunner 纯逻辑（REF-2.3）")
class PgDumpRunnerTest {

    private static BackupProperties props() {
        return new BackupProperties();
    }

    private static MockEnvironment envWithDatasource() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://localhost:15432/helloai?currentSchema=public&reWriteBatchedInserts=true")
                .withProperty("spring.datasource.username", "postgres")
                .withProperty("spring.datasource.password", "postgres");
    }

    @Test
    @DisplayName("连接目标：全留空 ⇒ 从 spring.datasource.url 派生（含端口与库名）")
    void deriveTargetFromDataSource() {
        PgDumpRunner runner = new PgDumpRunner(props(), envWithDatasource());

        PgDumpRunner.DatabaseTarget t = runner.resolveTarget();

        assertThat(t.host()).isEqualTo("localhost");
        assertThat(t.port()).isEqualTo(15432);
        assertThat(t.database()).isEqualTo("helloai");
        assertThat(t.username()).isEqualTo("postgres");
        assertThat(t.password()).isEqualTo("postgres");
    }

    @Test
    @DisplayName("连接目标：显式配置优先于派生（防两处漂移的「覆盖」侧）")
    void explicitConfigWins() {
        BackupProperties p = props();
        p.setHost("db.internal");
        p.setPort("6543");
        p.setDatabase("helloai_prod");
        p.setUsername("backup_user");
        p.setPassword("s3cret");
        PgDumpRunner runner = new PgDumpRunner(p, envWithDatasource());

        PgDumpRunner.DatabaseTarget t = runner.resolveTarget();

        assertThat(t.host()).isEqualTo("db.internal");
        assertThat(t.port()).isEqualTo(6543);
        assertThat(t.database()).isEqualTo("helloai_prod");
        assertThat(t.username()).isEqualTo("backup_user");
    }

    @Test
    @DisplayName("连接目标：JDBC URL 缺端口时按 PG 默认 5432")
    void defaultPortWhenAbsent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://pg.internal/helloai")
                .withProperty("spring.datasource.username", "u")
                .withProperty("spring.datasource.password", "p");

        PgDumpRunner.DatabaseTarget t = new PgDumpRunner(props(), env).resolveTarget();

        assertThat(t.host()).isEqualTo("pg.internal");
        assertThat(t.port()).isEqualTo(5432);
        assertThat(t.database()).isEqualTo("helloai");
    }

    @Test
    @DisplayName("连接目标：URL 不可解析且未显式配置 ⇒ 显式报错（不静默用错库）")
    void unparsableUrlFailsLoudly() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", "not-a-jdbc-url");

        assertThatThrownBy(() -> new PgDumpRunner(props(), env).resolveTarget())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法从 spring.datasource.url 解析");
    }

    @Test
    @DisplayName("describe() 不含口令（防日志泄漏）")
    void describeRedactsPassword() {
        PgDumpRunner.DatabaseTarget t =
                new PgDumpRunner.DatabaseTarget("h", 1, "d", "u", "TOP-SECRET", "jdbc:x");

        assertThat(t.describe()).isEqualTo("u@h:1/d").doesNotContain("TOP-SECRET");
    }

    @Test
    @DisplayName("主版本解析：16.4 -> 16；缺失/非数字 -> -1")
    void majorVersion() {
        assertThat(PgDumpRunner.majorOf("16.4")).isEqualTo(16);
        assertThat(PgDumpRunner.majorOf("17")).isEqualTo(17);
        assertThat(PgDumpRunner.majorOf(null)).isEqualTo(-1);
        assertThat(PgDumpRunner.majorOf("")).isEqualTo(-1);
        assertThat(PgDumpRunner.majorOf("x.y")).isEqualTo(-1);
    }

    @Test
    @DisplayName("配置了相对路径但自工作目录向上找不到 ⇒ 显式报错并给出可操作信息")
    void unresolvableRelativePathFailsLoudly() {
        BackupProperties p = props();
        p.setPgDumpPath("no-such-dir/no-such/pg_dump.exe");
        PgDumpRunner runner = new PgDumpRunner(p, envWithDatasource());

        assertThatThrownBy(runner::assertDumpAvailable)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("找不到 pg_dump");
    }

    @Test
    @DisplayName("总开关关闭 ⇒ 前置检查显式报不可用（不静默）")
    void disabledFailsLoudly() {
        BackupProperties p = props();
        p.setEnabled(false);
        PgDumpRunner runner = new PgDumpRunner(p, envWithDatasource());

        assertThatThrownBy(runner::assertDumpAvailable)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("备份功能已关闭");
    }

    @Test
    @DisplayName("配置了绝对路径但文件不存在 ⇒ 显式报错（不落到 PATH 去找同名命令）")
    void absolutePathMissingFailsLoudly() {
        BackupProperties p = props();
        p.setPgDumpPath("C:/definitely/not/here/pg_dump.exe");
        PgDumpRunner runner = new PgDumpRunner(p, envWithDatasource());

        assertThatThrownBy(runner::assertDumpAvailable)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("路径不存在");
    }
}
