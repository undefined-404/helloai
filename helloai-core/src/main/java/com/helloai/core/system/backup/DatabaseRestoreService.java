package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.PlatformBackupState;
import com.helloai.core.system.entity.PlatformBackup;
import com.helloai.core.system.mapper.PlatformBackupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 平台恢复（REF-2.3b / REF-2.4，`D-2026-10-10-2⑦`）。
 *
 * <p><b>两条路径刻意分开</b>：</p>
 * <ul>
 *   <li>{@link #preflight} —— **只读**，把 manifest 与归档头拉下来跑三门，返回判定结论。
 *       供界面/运维在动手前确认"这份备份能不能恢复"。</li>
 *   <li>{@link #restore} —— **破坏性**，三门全过之后才执行 {@code pg_restore}。</li>
 * </ul>
 * 分开的意义：恢复是**停机操作**（REF-2.4），人的正常流程是"先问能不能、再决定何时动手"；
 * 把预检做成只读接口，可以反复问而不产生任何副作用。
 *
 * <p><b>为什么必须带确认词</b>：{@code pg_restore} 会覆盖现有数据，属不可逆动作。
 * 一个拼错的 id 就够把生产库恢复成另一份状态，故要求调用方显式写下确认词，
 * 把"手滑"挡在门外（同 REF-1.6 安装入口 {@code confirmUpgrade} 的思路，但代价更高）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseRestoreService {

    /** 破坏性动作的确认词（大小写不敏感）。 */
    public static final String CONFIRM_WORD = "RESTORE";

    private final BackupStorage backupStorage;
    private final PgDumpRunner pgRunner;
    private final RestoreGate restoreGate;
    private final PlatformBackupMapper backupMapper;

    /** 预检结论：{@code restorable=false} 时 {@code reason} 给出三门中的具体拒绝原因。 */
    public record RestoreVerdict(boolean restorable, String reason, String pgVersion,
                                 String flywayMaxVersion, String database) {
    }

    /**
     * 只读预检：下载 manifest 与归档，跑三门，**不触碰任何数据**。
     *
     * @throws BizException 备份不存在 / 不是成功态 / 制品缺失（这些属"问不出结论"，与三门的"判定为不可恢复"不同）
     */
    public RestoreVerdict preflight(Long backupId) {
        PlatformBackup row = requireRestorableBackup(backupId);
        BackupManifest manifest = loadManifest(row);
        Path dump = downloadDump(row);
        try {
            restoreGate.assertRestorable(manifest, dump);
            return new RestoreVerdict(true, null, manifest.pgVersion(),
                    manifest.flywayMaxVersion(), manifest.database());
        } catch (BizException e) {
            // 三门拒绝是**判定结论**，不是调用错误 —— 以结论返回，便于界面直接展示
            return new RestoreVerdict(false, e.getMessage(), manifest.pgVersion(),
                    manifest.flywayMaxVersion(), manifest.database());
        } finally {
            deleteQuietly(dump);
        }
    }

    /**
     * 执行恢复（**破坏性**）：三门全过之后才下载归档并 {@code pg_restore}。
     *
     * @param confirm 必须等于 {@value #CONFIRM_WORD}（大小写不敏感）
     */
    public void restore(Long backupId, String confirm, String operatorName) {
        if (confirm == null || !CONFIRM_WORD.equalsIgnoreCase(confirm.trim())) {
            throw new BizException("恢复被拒：这是不可逆的破坏性操作，请在 confirm 字段显式写下 "
                    + CONFIRM_WORD + " 以确认");
        }
        PlatformBackup row = requireRestorableBackup(backupId);
        pgRunner.assertRestoreAvailable();

        BackupManifest manifest = loadManifest(row);
        Path dump = downloadDump(row);
        try {
            // 三门：任一不过即抛，且**先于**任何 pg_restore
            restoreGate.assertRestorable(manifest, dump);
            log.warn("开始恢复（破坏性）: backupId={}, pgVersion={}, operator={}",
                    backupId, manifest.pgVersion(), operatorName);
            pgRunner.restoreFromFile(pgRunner.resolveTarget(), dump);
            log.warn("恢复完成: backupId={}, operator={}", backupId, operatorName);
        } finally {
            deleteQuietly(dump);
        }
    }

    // ────────────────────────────────────────────────────────────

    private PlatformBackup requireRestorableBackup(Long backupId) {
        PlatformBackup row = backupMapper.selectById(backupId);
        if (row == null) {
            throw new BizException(404, "[NOT_FOUND] 备份不存在: " + backupId);
        }
        if (row.getState() != PlatformBackupState.SUCCESS) {
            throw new BizException("恢复被拒：该备份状态为 " + row.getState() + "，只有 SUCCESS 的备份可恢复");
        }
        if (row.getManifestKey() == null || row.getDumpKey() == null) {
            throw new BizException("恢复被拒：该备份缺少 manifest / dump 制品键（台账与对象不一致）");
        }
        return row;
    }

    private BackupManifest loadManifest(PlatformBackup row) {
        byte[] raw = backupStorage.get(row.getManifestKey());
        return BackupManifest.fromJson(new String(raw, StandardCharsets.UTF_8));
    }

    private Path downloadDump(PlatformBackup row) {
        try {
            Path tmp = Files.createTempFile("helloai-restore-", ".dump");
            Files.write(tmp, backupStorage.get(row.getDumpKey()));
            return tmp;
        } catch (Exception e) {
            throw new BizException("恢复被拒：归档下载失败: " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 临时文件清理失败不影响结论
        }
    }
}
