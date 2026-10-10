package com.helloai.core.system.backup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.config.BackupProperties;
import com.helloai.common.constant.PlatformBackupState;
import com.helloai.common.constant.PlatformBackupType;
import com.helloai.core.system.entity.PlatformBackup;
import com.helloai.core.system.mapper.PlatformBackupMapper;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.system.storage.ArtifactStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 平台备份编排（REF-2.3，`D-2026-10-10-2`）。
 *
 * <p><b>为什么不包一个大事务</b>：备份是**分钟级**操作，`pg_dump` 期间不该占着数据库连接与事务
 * （对照技能包安装那种秒级单事务）。故拆成三段**各自短事务**：建 RUNNING 台账行 →
 * 无事务地跑 dump 与上传 → 更新台账行。中途崩溃留下的 RUNNING 行由 {@link #list} 的
 * 超时判定暴露，不会被当成"进行中"永久卡住。</p>
 *
 * <p><b>单飞锁</b>：用 Redisson {@code RLock}（与 {@code SubTaskReviewServiceImpl} 同范式：
 * {@code waitTime=0} 抢占失败即让位、显式 {@code leaseTime} 禁看门狗、释放走
 * {@code isHeldByCurrentThread()} 防御），而非上游的进程内 {@code asyncio.Lock}
 * —— helloai 是多实例部署，进程内锁不构成互斥。</p>
 *
 * <p><b>硬约束「枚举为空必须显式失败」</b>：{@code ArtifactStorage.listObjects} 的接口默认实现是
 * fail-safe 返回空列表（"实现不支持"），而真空桶也会返回空 —— 两者**从返回值上不可区分**。
 * 故本服务把桶枚举结果与 {@link ArtifactReferencePort} 的库内引用对照：
 * 桶为空但库里有引用 ⇒ 判定为"实现不支持"，**显式失败**而非产出"看起来完整"的备份。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseBackupService {

    private final BackupProperties backupProperties;
    private final ArtifactStorageProperties artifactStorageProperties;
    private final BackupStorage backupStorage;
    private final PgDumpRunner pgRunner;
    private final ArtifactStorage artifactStorage;
    private final ArtifactReferencePort artifactReferencePort;
    private final PlatformBackupMapper backupMapper;
    private final RedissonClient redissonClient;
    private final DbSchemaVersionReader dbSchemaVersionReader;

    /** 单飞锁键（全平台一次只允许一个备份）。 */
    private static final String BACKUP_LOCK_KEY = "backup:lock:platform";

    /** 锁租期：必须**大于**最长备份耗时，否则锁提前过期会让第二个备份并发跑起来。 */
    private static final long LOCK_LEASE_SECONDS = 7200L;

    // ────────────────────────────────────────────────────────────
    //  提交与执行
    // ────────────────────────────────────────────────────────────

    /**
     * 提交一次备份：落一条 RUNNING 台账行并返回 id。
     *
     * <p>**只做提交不做执行** —— HTTP 线程立即返回，实际执行由调用方交给异步执行器
     * （学拆解 {@code PlannerDecomposeAsyncService} 的范式：提交即返回、前端轮询状态）。</p>
     *
     * @param type          {@code MANUAL} / {@code AUTO}
     * @param operatorName  操作者（手动 = 管理端登录名；自动 = {@code system}）
     * @return 台账 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(PlatformBackupType type, String operatorName) {
        // 前置检查放在建行之前：能力不可用时不该留下一条注定失败的台账
        pgRunner.assertDumpAvailable();
        backupStorage.assertAvailable();

        PlatformBackup row = new PlatformBackup();
        row.setBackupType(type);
        row.setState(PlatformBackupState.RUNNING);
        row.setStartedAt(OffsetDateTime.now());
        row.setCreateBy(operatorName == null ? "system" : operatorName);
        backupMapper.insert(row);
        log.info("备份已提交: id={}, type={}, operator={}", row.getId(), type, operatorName);
        return row.getId();
    }

    /**
     * 实际执行一次备份（应由异步执行器或调度器调用）。
     *
     * <p>任何失败都落到台账（{@code FAILED} + 可读原因），**不向调用方抛**
     * —— 异步链路抛异常只会变成一条无人看的日志。</p>
     */
    public void run(Long backupId) {
        RLock lock = redissonClient.getLock(BACKUP_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                markFailed(backupId, "已有备份正在进行（分布式单飞锁未获取到），本次跳过");
                return;
            }
            execute(backupId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(backupId, "备份被中断");
        } catch (Exception e) {
            log.error("备份执行失败: id={}", backupId, e);
            markFailed(backupId, e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void execute(Long backupId) throws Exception {
        PlatformBackup row = backupMapper.selectById(backupId);
        if (row == null) {
            throw new BizException("备份台账行不存在: " + backupId);
        }
        PgDumpRunner.DatabaseTarget target = pgRunner.resolveTarget();
        String artifactBucket = artifactStorageProperties.getMinioBucket();

        // ① 产物枚举 + 硬约束② 判据（对照库内引用）
        List<ArtifactStorage.StoredObject> objects =
                artifactStorage.listObjects(artifactBucket, null);
        List<ArtifactReference> references = artifactReferencePort.listAllIncludingDeleted();
        if (objects.isEmpty() && !references.isEmpty()) {
            throw new BizException("备份中止：产物桶枚举为空，但库内有 " + references.size()
                    + " 条产物引用 —— 更像「存储实现不支持枚举」（fail-safe 默认返回空）而非真空桶；"
                    + "静默通过会产出「看起来完整」的备份，故障直到恢复时才暴露");
        }

        Path dumpFile = Files.createTempFile("helloai-backup-", ".dump");
        try {
            // ② dump + 读归档头（不解档）
            pgRunner.dumpToFile(target, dumpFile);
            PgDumpRunner.DumpHeader header = pgRunner.peekHeader(dumpFile);
            if (header.tocEntries() <= 0) {
                throw new BizException("备份中止：归档 TOC 条目为 0，dump 可能未真正产出内容");
            }

            // ③ 组装并上传（dump + 产物清单 + manifest）
            String prefix = buildPrefix(row);
            String dumpKey = prefix + "database.dump";
            String inventoryKey = prefix + "artifacts.json";
            String manifestKey = prefix + BackupManifest.OBJECT_NAME;

            backupStorage.putFile(dumpKey, dumpFile, "application/octet-stream");
            byte[] inventory = buildInventoryJson(objects);
            backupStorage.put(inventoryKey, inventory, "application/json");

            BackupManifest manifest = new BackupManifest(
                    row.getId(), row.getBackupType().name(), OffsetDateTime.now(),
                    header.pgVersion(), header.pgDumpVersion(),
                    dbSchemaVersionReader.dbMaxMigrationVersion(), target.database(),
                    dumpKey, Files.size(dumpFile), sha256Hex(dumpFile),
                    objects.size(), sumSizes(objects),
                    artifactBucket, row.getCreateBy());
            backupStorage.put(manifestKey, manifest.toJson().getBytes(StandardCharsets.UTF_8),
                    "application/json");

            markSuccess(row, prefix, manifestKey, dumpKey, manifest, header.tocEntries());
            pruneAutoBackups();
        } finally {
            Files.deleteIfExists(dumpFile);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  查询
    // ────────────────────────────────────────────────────────────

    /** 备份列表（按开始时刻倒序）。 */
    public List<PlatformBackup> list(int limit) {
        int n = limit <= 0 ? 50 : Math.min(limit, 200);
        return backupMapper.selectList(new LambdaQueryWrapper<PlatformBackup>()
                .orderByDesc(PlatformBackup::getStartedAt)
                .last("LIMIT " + n));
    }

    /** 备份详情；不存在返回 null。 */
    public PlatformBackup detail(Long id) {
        return backupMapper.selectById(id);
    }

    // ────────────────────────────────────────────────────────────
    //  保留淘汰
    // ────────────────────────────────────────────────────────────

    /**
     * 仅淘汰**自动**备份：保留最新 N 份，更旧的连同对象一并删除。
     *
     * <p>手动备份**永不参与**（`D-2026-10-10-2⑤`）—— 那是人的产物，不该被机器回收。</p>
     */
    void pruneAutoBackups() {
        int keep = backupProperties.getAutoRetentionCount();
        if (keep <= 0) {
            return;
        }
        List<PlatformBackup> autos = backupMapper.selectList(new LambdaQueryWrapper<PlatformBackup>()
                .eq(PlatformBackup::getBackupType, PlatformBackupType.AUTO)
                .eq(PlatformBackup::getState, PlatformBackupState.SUCCESS)
                .orderByDesc(PlatformBackup::getStartedAt));
        if (autos.size() <= keep) {
            return;
        }
        for (PlatformBackup old : autos.subList(keep, autos.size())) {
            try {
                for (BackupStorage.BackupObject o : backupStorage.list(old.getObjectPrefix())) {
                    backupStorage.delete(o.key());
                }
                backupMapper.deleteById(old.getId());
                log.info("保留淘汰：已删除自动备份 id={}, prefix={}", old.getId(), old.getObjectPrefix());
            } catch (Exception e) {
                // 淘汰失败不阻断备份本身；下一轮重试
                log.warn("保留淘汰失败（下一轮重试）: id={}, err={}", old.getId(), e.getMessage());
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    private String buildPrefix(PlatformBackup row) {
        OffsetDateTime t = row.getStartedAt() == null ? OffsetDateTime.now() : row.getStartedAt();
        String uuid8 = UUID.randomUUID().toString().substring(0, 8);
        return backupProperties.getKeyPrefix() + "/" + row.getBackupType().name().toLowerCase()
                + "/" + String.format("%04d/%02d/%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth())
                + "/" + uuid8 + "/";
    }

    private static byte[] buildInventoryJson(List<ArtifactStorage.StoredObject> objects) {
        // 手写 JSON 而非引 ObjectMapper：字段固定且需保持稳定顺序（jar 体积/依赖也更小）
        StringBuilder sb = new StringBuilder("{\"count\":").append(objects.size()).append(",\"objects\":[");
        for (int i = 0; i < objects.size(); i++) {
            ArtifactStorage.StoredObject o = objects.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"key\":\"").append(escape(o.objectKey()))
              .append("\",\"size\":").append(o.size()).append('}');
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long sumSizes(List<ArtifactStorage.StoredObject> objects) {
        long sum = 0;
        for (ArtifactStorage.StoredObject o : objects) {
            sum += o.size();
        }
        return sum;
    }

    /** 归档文件的 SHA-256 摘要（十六进制小写）。 */
    private static String sha256Hex(Path file) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file));
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hex[i * 2] = digits[v >>> 4];
                hex[i * 2 + 1] = digits[v & 0x0F];
            }
            return new String(hex);
        } catch (Exception e) {
            throw new BizException("归档摘要计算失败: " + e.getMessage());
        }
    }

    // 注意：本方法与 markFailed 都是**类内自调用**，@Transactional 在此不生效
    // （自调用不走代理，同 AgentServiceImpl#registerWithExtras 的 javadoc 所述陷阱）。
    // 刻意不加注解：整段 `run` 必须在**无事务**下执行 —— `pg_dump` 是分钟级操作，
    // 挂一个横跨它的事务会把数据库连接占住整段时间。单条 updateById 走 auto-commit 即可。
    void markSuccess(PlatformBackup row, String prefix, String manifestKey, String dumpKey,
                     BackupManifest manifest, int tocEntries) {
        row.setState(PlatformBackupState.SUCCESS);
        row.setObjectPrefix(prefix);
        row.setManifestKey(manifestKey);
        row.setDumpKey(dumpKey);
        row.setPgVersion(manifest.pgVersion());
        row.setFlywayMaxVersion(manifest.flywayMaxVersion());
        row.setArtifactCount(manifest.artifactCount());
        row.setArtifactBytes(manifest.artifactBytes());
        row.setDumpBytes(manifest.dumpBytes());
        row.setTotalBytes(manifest.dumpBytes() + manifest.artifactBytes());
        row.setChecksumSha256(manifest.dumpSha256());
        row.setFinishedAt(OffsetDateTime.now());
        backupMapper.updateById(row);
        log.info("备份成功: id={}, toc={}, dumpBytes={}, artifacts={}",
                row.getId(), tocEntries, manifest.dumpBytes(), manifest.artifactCount());
    }

    void markFailed(Long backupId, String reason) {
        PlatformBackup row = backupMapper.selectById(backupId);
        if (row == null) {
            return;
        }
        row.setState(PlatformBackupState.FAILED);
        row.setFailureReason(reason == null ? "未知原因" : truncate(reason));
        row.setFinishedAt(OffsetDateTime.now());
        backupMapper.updateById(row);
        log.warn("备份失败: id={}, reason={}", backupId, reason);
    }

    private static String truncate(String s) {
        return s.length() <= 512 ? s : s.substring(0, 512);
    }
}
