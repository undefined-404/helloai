package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.backup.BackupResponse;
import com.helloai.api.support.AdminOperatorResolver;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.common.constant.PlatformBackupType;
import com.helloai.core.system.backup.BackupAsyncRunner;
import com.helloai.core.system.backup.DatabaseBackupService;
import com.helloai.core.system.backup.DatabaseRestoreService;
import com.helloai.core.system.entity.PlatformBackup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台备份 / 恢复的管理端接口（REF-2.3 / 2.3b）。
 *
 * <p><b>异步形态</b>：手动触发**只提交不等待** —— 建台账行后立即返回 id，
 * 实际执行交给 {@link BackupAsyncRunner}。调用方轮询 {@code GET /api/backup/{id}}
 * 的 {@code state} 字段。这与仓库既有拆解链路同款（HTTP 线程提交即返回、
 * 前端轮询另一个查询端点；仓库**没有**"返回 taskId 让前端轮询"的通用范式）。</p>
 *
 * <p><b>恢复为什么分成两个端点</b>：{@code preflight} 是只读的（反复问都无副作用），
 * {@code restore} 是不可逆的破坏性动作（要求确认词，且三门全过才执行）。
 * 把"能不能"与"动手"分开，运维可以先确认再选停机窗口（REF-2.4）。</p>
 *
 * <p><b>分层</b>：本类只做接收 / 校验 / 转换 / 调用 / 返回（CODE_STYLE §10）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {

    private final DatabaseBackupService backupService;
    private final DatabaseRestoreService restoreService;
    private final BackupAsyncRunner backupAsyncRunner;
    private final AdminOperatorResolver adminOperatorResolver;

    /** 手动触发一次备份（提交即返回，进度轮询 {@code GET /api/backup/{id}}）。 */
    @SaCheckPermission("backup:run")
    @PostMapping
    public R<BackupResponse> trigger() {
        String operator = adminOperatorResolver.current().name();
        Long id = backupService.submit(PlatformBackupType.MANUAL, operator);
        backupAsyncRunner.runAsync(id);
        return R.ok(toResponse(backupService.detail(id)));
    }

    /** 备份列表（按开始时刻倒序）。 */
    @SaCheckPermission("backup:view")
    @GetMapping
    public R<List<BackupResponse>> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return R.ok(backupService.list(limit).stream().map(BackupController::toResponse).toList());
    }

    /** 备份详情 —— **前端轮询点**。 */
    @SaCheckPermission("backup:view")
    @GetMapping("/{id}")
    public R<BackupResponse> detail(@PathVariable("id") Long id) {
        PlatformBackup row = backupService.detail(id);
        if (row == null) {
            return R.fail(404, "备份不存在: " + id);
        }
        return R.ok(toResponse(row));
    }

    /**
     * 恢复预检（**只读**）：跑三门并返回判定结论，不触碰任何数据。
     *
     * <p>三门拒绝属**判定结论**（{@code restorable=false} + 可读原因），不是调用错误，
     * 故以 200 + 结论返回，便于界面直接展示。</p>
     */
    @SaCheckPermission("backup:run")
    @PostMapping("/{id}/restore/preflight")
    public R<DatabaseRestoreService.RestoreVerdict> preflight(@PathVariable("id") Long id) {
        return R.ok(restoreService.preflight(id));
    }

    /**
     * 执行恢复（**破坏性、不可逆**）。
     *
     * <p>请求体须带 {@code {"confirm":"RESTORE"}} —— 一个拼错的 id 就够把库恢复成
     * 另一份状态，故要求调用方显式写下确认词。</p>
     */
    @SaCheckPermission("backup:run")
    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new BizException("恢复被拒：缺少请求体（需 confirm 字段）");
        }
        Object confirm = body.get("confirm");
        restoreService.restore(id, confirm == null ? null : String.valueOf(confirm),
                adminOperatorResolver.current().name());
        return R.ok();
    }

    // ────────────────────────────────────────────────────────────

    private static BackupResponse toResponse(PlatformBackup row) {
        BackupResponse r = new BackupResponse();
        r.setId(row.getId());
        // 枚举 → 字符串：对外契约稳定，实体侧的类型演进不外溢（见 DTO javadoc）
        r.setBackupType(row.getBackupType() == null ? null : row.getBackupType().name());
        r.setState(row.getState() == null ? null : row.getState().name());
        r.setObjectPrefix(row.getObjectPrefix());
        r.setPgVersion(row.getPgVersion());
        r.setFlywayMaxVersion(row.getFlywayMaxVersion());
        r.setArtifactCount(row.getArtifactCount());
        r.setArtifactBytes(row.getArtifactBytes());
        r.setDumpBytes(row.getDumpBytes());
        r.setTotalBytes(row.getTotalBytes());
        r.setChecksumSha256(row.getChecksumSha256());
        r.setFailureReason(row.getFailureReason());
        r.setStartedAt(row.getStartedAt());
        r.setFinishedAt(row.getFinishedAt());
        return r;
    }
}
