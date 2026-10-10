package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.core.system.port.RestoreQuiescencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REF-2.3b 验收：恢复侧三门 —— **三类非法恢复各一个「必拒绝」用例**（计划硬要求）。
 *
 * <p>闸门是备份功能里风险最高的一段：无闸门的"可恢复" = 可把生产库恢复成不一致状态。
 * 故这里每个门都必须有一条把它钉死的用例，外加一条"全过"的正向用例防误拦。</p>
 */
@ExtendWith(MockitoExtension.class)
// 严格桩在此处会误报：闸门**短路**是刻意设计（门①不过就不该碰门③的资源），
// 于是"后门的桩未被使用"成为正常结果。故显式放宽 —— 这是短路型守卫测试的固有形态。
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("恢复侧安全闸门（REF-2.3b）")
class RestoreGateTest {

    @Mock
    private PgDumpRunner runner;

    @Mock
    private MigrationVersionResolver migrationVersionResolver;

    private static final Path DUMP = Path.of("no-such-file.dump");

    /** 代码内已知最高迁移号固定为 V1 —— 使门② 的用例不依赖仓库真实迁移数（否则加迁移就红）。 */
    private static final int CODE_MAX_MIGRATION = 1;

    /** 运行时 PG 主版本 = 16（与服务端 postgres:16.4 一致）。 */
    private static DataSource ds(int runtimeMajor, int activeQueries) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseMajorVersion()).thenReturn(runtimeMajor);

        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeQuery(any())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(activeQueries);
        return ds;
    }

    private static RestoreQuiescencePort quietProbe() {
        return () -> 0;
    }

    private static BackupManifest manifest(String pgVersion, String flywayMax) {
        return new BackupManifest(1L, "MANUAL", OffsetDateTime.now(), pgVersion, pgVersion,
                flywayMax, "helloai", "backups/k/dump", 1L, "sha", 0, 0L, "helloai-artifacts", "admin");
    }

    private void headerIs(String pgVersion) {
        when(runner.peekHeader(any()))
                .thenReturn(new PgDumpRunner.DumpHeader(pgVersion, pgVersion, 10));
    }

    private RestoreGate gate(RestoreQuiescencePort probe, DataSource ds) {
        return new RestoreGate(runner, probe, migrationVersionResolver, ds);
    }

    @BeforeEach
    void stubCommon() {
        headerIs("16.4");
        when(migrationVersionResolver.codeMaxMigrationVersion()).thenReturn(CODE_MAX_MIGRATION);
    }

    // ────────────────────────────────────────────────────────────
    //  三类必拒绝
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★门① 跨引擎：备份来自 PG 15、运行时 16 ⇒ 拒")
    void crossEngineRejected() throws Exception {
        headerIs("15.7");
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(manifest("15.7", "1"), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[跨引擎]")
                .hasMessageContaining("15")
                .hasMessageContaining("16");
    }

    @Test
    @DisplayName("★门① 归档头解析不出 PG 版本 ⇒ 拒（不是合法归档）")
    void unparsableHeaderRejected() throws Exception {
        headerIs("not-a-version");
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(manifest("x", "1"), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[跨引擎]");
    }

    @Test
    @DisplayName("★门② schema 高过运行时：备份 V999 > 代码已知最高 ⇒ 拒")
    void schemaNewerRejected() throws Exception {
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(manifest("16.4", "999"), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[schema]")
                .hasMessageContaining("V999");
    }

    @Test
    @DisplayName("★门② 清单缺 flywayMaxVersion ⇒ 拒（判不出就不放行）")
    void missingFlywayVersionRejected() throws Exception {
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(manifest("16.4", null), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[schema]");
    }

    @Test
    @DisplayName("★门③ 在线恢复：库上有活动查询 ⇒ 拒")
    void activeQueryRejected() throws Exception {
        RestoreGate gate = gate(quietProbe(), ds(16, 3));

        assertThatThrownBy(() -> gate.assertRestorable(currentManifest(), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[在线]")
                .hasMessageContaining("活动查询");
    }

    @Test
    @DisplayName("★门③ 在线恢复：平台仍有在飞子任务 ⇒ 拒")
    void inFlightSubTasksRejected() throws Exception {
        RestoreGate gate = gate(() -> 7, ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(currentManifest(), DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("[在线]")
                .hasMessageContaining("在飞子任务");
    }

    @Test
    @DisplayName("★门③ 清单缺失 ⇒ 拒（前置信息不全不放行）")
    void missingManifestRejected() throws Exception {
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatThrownBy(() -> gate.assertRestorable(null, DUMP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少备份清单");
    }

    // ────────────────────────────────────────────────────────────
    //  正向：全过（防误拦）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("三门全过 ⇒ 放行（版本同主版本、schema 不高于代码、无活动查询无在飞）")
    void allGatesPass() throws Exception {
        // flywayMaxVersion 用 1（远低于代码已知最高迁移），确保正向用例不依赖具体版本号
        RestoreGate gate = gate(quietProbe(), ds(16, 0));

        assertThatCode(() -> gate.assertRestorable(manifest("16.4", "1"), DUMP))
                .doesNotThrowAnyException();
    }

    private static BackupManifest currentManifest() {
        return manifest("16.4", "1");
    }
}
