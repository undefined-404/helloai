package com.helloai.core.agent.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import com.helloai.core.agent.mapper.InstalledSkillPackageMapper;
import com.helloai.core.agent.skill.SkillPackageAuditService.Operator;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 技能包安装 / 激活（回滚、降版）/ 卸载（REF-1.6）。
 *
 * <p><b>存储形态</b>（`D-2026-10-10-1①②`）：元数据与<b>原始正文</b>落 PG，
 * 原始上传 zip 落 MinIO（经 {@link ArtifactStorage}，仅作溯源 / 完整性 / 再分发）。
 * <b>不写宿主文件系统</b> —— 故不构成 {@code G-005} 触发条件①，REF-3 沙箱维持「条件触发」。</p>
 *
 * <p><b>版本策略</b>（`D-2026-10-10-1⑦⑧`）：唯一键 {@code (name, version)} 多版本共存；
 * 安装时同名且版本更高 ⇒ 需 {@code confirmUpgrade}、更低或相同 ⇒ 拒绝；
 * 回滚 / 降版<b>不复用安装入口</b>，走 {@link #activate}。同 name 至多一行 {@code ACTIVE}。</p>
 *
 * <p><b>内置保护</b>（`D-2026-10-10-1⑨`）：与 classpath 内置包同名一律拒绝，不论版本高低。</p>
 *
 * <p><b>缓存失效必须在提交后</b>：事务内 {@code catalog.refresh()} 会让并发读在提交前重查 DB、
 * 把未提交状态缓存下来，故经 {@code afterCommit} 回调触发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackageInstallService {

    /** 原始 zip 在受控存储里的归属目录名。 */
    private static final String STORAGE_OWNER = "skill-packages";

    private static final String ORIGIN_INSTALLED = "INSTALLED";

    private final SkillPackageZipGate gate;
    private final SkillPackageCatalog catalog;
    private final SkillPackageAuditService auditService;
    private final InstalledSkillPackageMapper packageMapper;
    private final ArtifactStorage artifactStorage;

    // 说明：install / activate 直接返回落库行（而非精简的「结果 record」）——响应要带
    // description / checksumSha256 / originZipUrl / createTime 等字段，精简 record 会让
    // 调用方拿到一串 null。Controller 负责把实体映射成 DTO（CODE_STYLE §11.3：API 层不暴露实体）。

    // ────────────────────────────────────────────────────────────
    //  安装（安装入口）
    // ────────────────────────────────────────────────────────────

    /**
     * 安装一个技能包（zip）。
     *
     * @param zipBytes       上传的技能包 zip
     * @param confirmUpgrade 同名且新版本更高时为 false 会返回 409 要求确认；确认为 true 才覆盖
     * @throws BizException(400) 闸门 / 清单解析失败
     * @throws BizException(409) 与内置同名 / 版本不高于现有 / 需要确认
     */
    @Transactional(rollbackFor = Exception.class)
    public InstalledSkillPackage install(byte[] zipBytes, boolean confirmUpgrade, Operator operator) {
        Prepared prepared = prepare(zipBytes, operator);
        String name = prepared.spec().name();
        String version = prepared.spec().version();

        InstalledSkillPackage active = findActive(name);
        if (active != null) {
            int cmp = compareVersion(version, active.getVersion());
            if (cmp <= 0) {
                auditService.record(operator, active.getId(), name, version,
                        SkillPackageAuditService.ACTION_REJECT, SkillPackageAuditService.RESULT_FAIL,
                        "版本不高于当前生效版本 " + active.getVersion() + "，安装入口只接受更高版本",
                        prepared.checksum());
                throw new BizException(409, "[VERSION_NOT_NEWER] 当前生效版本 " + active.getVersion()
                        + "，新版本 " + version + " 不更高；如需回滚 / 降版请用 activate 接口");
            }
            if (!confirmUpgrade) {
                // 不入审计：这不是"被拒绝"，而是"待确认"，用户带上 confirmUpgrade 重发即可
                throw new BizException(409, "[NEEDS_CONFIRM] 同名技能包当前生效版本 " + active.getVersion()
                        + "，新版本 " + version + " 更高；确认更新请重发并带 confirmUpgrade=true");
            }
        }

        InstalledSkillPackage row = upsert(prepared, active);
        auditService.record(operator, row.getId(), name, version,
                SkillPackageAuditService.ACTION_INSTALL, SkillPackageAuditService.RESULT_SUCCESS,
                null, prepared.checksum());
        log.info("技能包安装成功: name={}, version={}, id={}, operator={}", name, version, row.getId(), operator);
        return row;
    }

    // ────────────────────────────────────────────────────────────
    //  激活（回滚 / 降版）
    // ────────────────────────────────────────────────────────────

    /**
     * 激活指定版本（回滚历史版本；或带包体做低版本覆盖安装）。
     *
     * @param name          技能包标签
     * @param version       目标版本（三段式）
     * @param zipBytesOrNull 目标版本不在库时所需的上传包；已在库时传 null 即可
     */
    @Transactional(rollbackFor = Exception.class)
    public InstalledSkillPackage activate(String name, String version, byte[] zipBytesOrNull, Operator operator) {
        InstalledSkillPackage target = findByNameVersion(name, version);
        if (target == null) {
            if (zipBytesOrNull == null || zipBytesOrNull.length == 0) {
                throw new BizException(404, "[VERSION_NOT_FOUND] 已安装列表中不存在 " + name + "@" + version
                        + "；若要做低版本覆盖，请在请求中带上包体");
            }
            // 低版本覆盖：走与安装相同的闸门与解析，但**不经**版本提升检查（这正是降版通道）
            Prepared prepared = prepare(zipBytesOrNull, operator);
            if (!name.equals(prepared.spec().name()) || !version.equals(prepared.spec().version())) {
                throw new BizException(400, "[NAME_MISMATCH] 包体声明的 "
                        + prepared.spec().name() + "@" + prepared.spec().version() + " 与请求的 "
                        + name + "@" + version + " 不一致");
            }
            target = upsert(prepared, findActive(name));
        }
        flipActive(name, target);
        auditService.record(operator, target.getId(), name, version,
                SkillPackageAuditService.ACTION_ACTIVATE, SkillPackageAuditService.RESULT_SUCCESS,
                null, target.getChecksumSha256());
        log.info("技能包激活: name={}, version={}, operator={}", name, version, operator);
        return target;
    }

    // ────────────────────────────────────────────────────────────
    //  卸载
    // ────────────────────────────────────────────────────────────

    /**
     * 卸载（停用）一个技能包版本。行<b>保留</b>（置 DISABLED）——审计与回滚都需要它。
     */
    @Transactional(rollbackFor = Exception.class)
    public void uninstall(Long id, Operator operator) {
        InstalledSkillPackage row = packageMapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "[NOT_FOUND] 技能包不存在: " + id);
        }
        row.setState(InstalledSkillPackage.STATE_DISABLED);
        packageMapper.updateById(row);
        refreshCatalogAfterCommit();
        auditService.record(operator, row.getId(), row.getName(), row.getVersion(),
                SkillPackageAuditService.ACTION_UNINSTALL, SkillPackageAuditService.RESULT_SUCCESS,
                null, row.getChecksumSha256());
        log.info("技能包卸载: name={}, version={}, operator={}", row.getName(), row.getVersion(), operator);
    }

    /** 列出某标签的全部版本（含历史与已停用），按版本倒序，供界面回滚选择。 */
    public java.util.List<InstalledSkillPackage> listVersions(String name) {
        return packageMapper.selectList(new LambdaQueryWrapper<InstalledSkillPackage>()
                .eq(InstalledSkillPackage::getName, name)
                .orderByDesc(InstalledSkillPackage::getVersion));
    }

    /** 列出全部已安装行（含历史与已停用），供列表接口。 */
    public java.util.List<InstalledSkillPackage> listAll() {
        return packageMapper.selectList(new LambdaQueryWrapper<InstalledSkillPackage>()
                .orderByAsc(InstalledSkillPackage::getName)
                .orderByDesc(InstalledSkillPackage::getVersion));
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    /** 闸门 + 解析 + 内置保护 + 原始 zip 落受控存储的结果。 */
    private record Prepared(SkillPackage spec, String content, String checksum, String originZipUrl) {
    }

    private Prepared prepare(byte[] zipBytes, Operator operator) {
        byte[] manifestBytes = gate.inspectAndExtractManifest(zipBytes);   // 失败即 400，不落任何数据
        String content = new String(manifestBytes, StandardCharsets.UTF_8);

        SkillFrontMatter.Split split = SkillFrontMatter.split(content);
        if (split.error() != null) {
            throw new BizException(400, "[CORRUPT] 清单 " + SkillPackageZipGate.MANIFEST_NAME
                    + " frontmatter 非法: " + split.error());
        }
        SkillPackage spec;
        try {
            spec = SkillPackageParser.parse(SkillPackageZipGate.MANIFEST_NAME, split.yaml());
        } catch (SkillCorruptException e) {
            throw new BizException(400, "[CORRUPT] 清单 frontmatter 非法: " + e.getMessage());
        }

        if (catalog.isBuiltinName(spec.name())) {
            auditService.record(operator, null, spec.name(), spec.version(),
                    SkillPackageAuditService.ACTION_REJECT, SkillPackageAuditService.RESULT_FAIL,
                    "与内置技能包同名；内置包随发版走、不可替换",
                    sha256Hex(zipBytes));
            throw new BizException(409, "[BUILTIN_CONFLICT] 与内置技能包同名，禁止安装: " + spec.name());
        }

        // 原始 zip 落受控存储（溯源 / 完整性 / 再分发）；失败即整体回滚
        StoredArtifact stored = artifactStorage.store(STORAGE_OWNER,
                spec.name() + "-" + spec.version() + ".zip", zipBytes);
        return new Prepared(spec, content, sha256Hex(zipBytes), stored.storageUrl());
    }

    /** 落库：同 (name, version) 已存在则复用该行（避免撞唯一键），否则新建；并保证目标行成为唯一 ACTIVE。 */
    private InstalledSkillPackage upsert(Prepared prepared, InstalledSkillPackage currentActive) {
        SkillPackage spec = prepared.spec();
        InstalledSkillPackage row = findByNameVersion(spec.name(), spec.version());
        boolean created = row == null;
        if (created) {
            row = new InstalledSkillPackage();
            row.setName(spec.name());
            row.setVersion(spec.version());
            row.setOrigin(ORIGIN_INSTALLED);
            row.setLocked(0);
        }
        row.setDescription(spec.description());
        row.setRequiredTools(spec.requiredTools());
        row.setDependencies(spec.dependencies());
        row.setInputSchema(spec.inputSchema());
        row.setOutputSchema(spec.outputSchema());
        row.setValidationRules(spec.validationRules());
        row.setBody(prepared.content());
        row.setManifestName(SkillPackageZipGate.MANIFEST_NAME);
        row.setChecksumSha256(prepared.checksum());
        row.setOriginZipUrl(prepared.originZipUrl());

        // 先把现有 ACTIVE 让位（partial unique index 要求同 name 至多一行 ACTIVE）
        if (currentActive != null && !currentActive.getId().equals(row.getId())) {
            currentActive.setState(InstalledSkillPackage.STATE_HISTORICAL);
            packageMapper.updateById(currentActive);
        }
        row.setState(InstalledSkillPackage.STATE_ACTIVE);
        if (created) {
            packageMapper.insert(row);
        } else {
            packageMapper.updateById(row);
        }
        refreshCatalogAfterCommit();
        return row;
    }

    /** 把 target 置为唯一 ACTIVE，原 ACTIVE 置 HISTORICAL。 */
    private void flipActive(String name, InstalledSkillPackage target) {
        InstalledSkillPackage active = findActive(name);
        if (active != null && !active.getId().equals(target.getId())) {
            active.setState(InstalledSkillPackage.STATE_HISTORICAL);
            packageMapper.updateById(active);
        }
        target.setState(InstalledSkillPackage.STATE_ACTIVE);
        packageMapper.updateById(target);
        refreshCatalogAfterCommit();
    }

    private InstalledSkillPackage findActive(String name) {
        return packageMapper.selectOne(new LambdaQueryWrapper<InstalledSkillPackage>()
                .eq(InstalledSkillPackage::getName, name)
                .eq(InstalledSkillPackage::getState, InstalledSkillPackage.STATE_ACTIVE));
    }

    private InstalledSkillPackage findByNameVersion(String name, String version) {
        return packageMapper.selectOne(new LambdaQueryWrapper<InstalledSkillPackage>()
                .eq(InstalledSkillPackage::getName, name)
                .eq(InstalledSkillPackage::getVersion, version));
    }

    /**
     * 提交后再失效目录缓存。
     *
     * <p>事务内失效会让并发读在提交前重查 DB、把**未提交状态**缓存下来（脏缓存）；
     * 故注册 {@code afterCommit} 回调。</p>
     */
    private void refreshCatalogAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    catalog.refresh();
                }
            });
        } else {
            catalog.refresh();
        }
    }

    /**
     * 三段式数字版本比较（逐段按数值）。
     *
     * <p>与 {@code verify-skill-packages.ps1} 的「三段式数字」断言同口径——不引入 pre-release
     * 语义（`D-2026-10-10-1⑦`）。缺段按 0 计（{@code 1.2} 等价 {@code 1.2.0}）。</p>
     *
     * @return 负数 = left 更小；0 = 相等；正数 = left 更大
     */
    static int compareVersion(String left, String right) {
        String[] l = (left == null ? "" : left).split("\\.");
        String[] r = (right == null ? "" : right).split("\\.");
        for (int i = 0; i < 3; i++) {
            int lv = i < l.length ? parseSegment(l[i]) : 0;
            int rv = i < r.length ? parseSegment(r[i]) : 0;
            if (lv != rv) {
                return Integer.compare(lv, rv);
            }
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            // 非数字段（格式非法）按 0 处理：格式校验归解析器 / 闸门，这里不重复报错
            return 0;
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hex[i * 2] = digits[v >>> 4];
                hex[i * 2 + 1] = digits[v & 0x0F];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
