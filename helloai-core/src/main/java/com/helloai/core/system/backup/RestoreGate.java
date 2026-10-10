package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.core.system.port.RestoreQuiescencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 恢复侧安全闸门（REF-2.3b，`D-2026-10-10-2⑦`）—— **三门全过才允许 {@code pg_restore}**。
 *
 * <p>计划把这段列为"备份功能里风险最高的一段"（上游对应约 650 行）：无闸门的「可恢复」
 * = 可把生产库恢复成不一致状态。故本类**只做判定、不做动作**，且判定一律**先于**归档展开。</p>
 *
 * <p><b>三门</b>：</p>
 * <ol>
 *   <li><b>跨引擎拒</b> —— 归档头 {@code Dumped from database version} 的**主版本** 必须等于
 *       运行时 PG 主版本。判据取自 {@code pg_restore -l} 的头部（**不解档**，实测可行）。</li>
 *   <li><b>schema 高过运行时拒</b> —— manifest 携带的 {@code flywayMaxVersion} 若**高于**
 *       当前代码内已知的最高迁移号，说明这份备份来自更新的版本 ⇒ 拒。
 *       （反之允许：较旧的备份恢复后由 Flyway 在下次启动补齐。）</li>
 *   <li><b>在线恢复拒</b> —— 库上有**活动查询**、或平台仍有**在飞子任务** ⇒ 拒。
 *       计划 `REF-2.4` 定性为「停机恢复」，而"停机"是人的约定、不是机器事实，
 *       故把它变成可判定的前置条件。</li>
 * </ol>
 *
 * <p><b>依赖方向</b>：在飞子任务的真相在 task 域，而本类在 system 域。
 * 直查会撞冻结红线（{@code system->task} / {@code system->task.mapper} 均为严格拦截），
 * 故经 {@link RestoreQuiescenceProbe} 端口反转（§7.2）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestoreGate {

    private final PgDumpRunner runner;
    private final RestoreQuiescencePort quiescencePort;
    private final MigrationVersionResolver migrationVersionResolver;
    private final DataSource dataSource;

    /**
     * 三门校验；任一不过抛 {@link BizException}（消息给出可读原因），**不执行任何恢复动作**。
     *
     * @param manifest 备份清单（由 manifest 对象反序列化而来）
     * @param dumpFile 已下载到本地的归档文件（用于读归档头）
     */
    public void assertRestorable(BackupManifest manifest, java.nio.file.Path dumpFile) {
        if (manifest == null) {
            throw new BizException("恢复被拒：缺少备份清单（manifest），无法判定兼容性");
        }
        assertSameEngine(manifest, dumpFile);
        assertSchemaNotNewer(manifest);
        assertQuiescent();
        log.info("恢复前置三闸门全部通过: backupId={}, pgVersion={}, flywayMax={}",
                manifest.backupId(), manifest.pgVersion(), manifest.flywayMaxVersion());
    }

    // ────────────────────────────────────────────────────────────
    //  ① 跨引擎
    // ────────────────────────────────────────────────────────────

    private void assertSameEngine(BackupManifest manifest, java.nio.file.Path dumpFile) {
        PgDumpRunner.DumpHeader header = runner.peekHeader(dumpFile);
        int dumpMajor = PgDumpRunner.majorOf(header.pgVersion());
        if (dumpMajor <= 0) {
            throw new BizException("恢复被拒[跨引擎]：归档头未给出可解析的 PG 版本"
                    + "（是否不是 pg_dump -Fc 归档？）");
        }
        int runtimeMajor = runtimePgMajor();
        if (dumpMajor != runtimeMajor) {
            throw new BizException("恢复被拒[跨引擎]：备份由 PG " + dumpMajor
                    + " 导出，当前运行时为 PG " + runtimeMajor + "，主版本不一致");
        }
    }

    /** 运行时 PG 主版本（取自 JDBC 元数据，不额外开进程）。 */
    private int runtimePgMajor() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseMajorVersion();
        } catch (Exception e) {
            throw new BizException("恢复被拒[跨引擎]：无法确定运行时 PG 版本: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  ② schema 高过运行时
    // ────────────────────────────────────────────────────────────

    private void assertSchemaNotNewer(BackupManifest manifest) {
        int backupMax = parseVersionNumber(manifest.flywayMaxVersion());
        if (backupMax <= 0) {
            throw new BizException("恢复被拒[schema]：清单缺少可解析的 flywayMaxVersion"
                    + "（值=" + manifest.flywayMaxVersion() + "）");
        }
        int codeMax = migrationVersionResolver.codeMaxMigrationVersion();
        if (backupMax > codeMax) {
            throw new BizException("恢复被拒[schema]：备份的 schema 版本 V" + backupMax
                    + " 高于当前运行时代码已知的最高迁移 V" + codeMax
                    + "（这份备份来自更新的版本，先升级平台再恢复）");
        }
    }

    private static int parseVersionNumber(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  ③ 在线恢复
    // ────────────────────────────────────────────────────────────

    private void assertQuiescent() {
        int active = activeQueryCount();
        if (active > 0) {
            throw new BizException("恢复被拒[在线]：当前库上有 " + active
                    + " 个活动查询，恢复要求停机（REF-2.4）");
        }
        int inFlight = quiescencePort.inFlightSubTaskCount();
        if (inFlight > 0) {
            throw new BizException("恢复被拒[在线]：平台仍有 " + inFlight
                    + " 个在飞子任务（口径 ASSIGNED/IN_PROGRESS/REWORK），恢复要求停机（REF-2.4）");
        }
    }

    /**
     * 库上**活动查询**数（排除本连接自身）。
     *
     * <p>用 {@code state='active'} 而非"连接数"：应用自身的 Hikari 池常有若干 **idle** 连接，
     * 按连接数判会把正常运行误判为"在线"，让这条守卫每次都拦。</p>
     */
    private int activeQueryCount() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_stat_activity "
                             + "WHERE datname = current_database() AND state = 'active' "
                             + "AND pid <> pg_backend_pid()")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            // 判不出来就不放行：恢复是破坏性操作，宁可拒绝
            throw new BizException("恢复被拒[在线]：无法探测活动查询: " + e.getMessage());
        }
    }
}
