package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.helloai.api.dto.skill.InstalledSkillPackageResponse;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import com.helloai.core.agent.skill.SkillPackageAuditService;
import com.helloai.core.agent.skill.SkillPackageAuditService.Operator;
import com.helloai.core.agent.skill.SkillPackageInstallService;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 技能包安装 / 激活 / 卸载（REF-1.6）。
 *
 * <p><b>术语纪律</b>：《文档体系分类与治理规则》§3.7 禁止混用 SKILL 一词——本控制器管的是
 * <b>技能包（Skill Package）</b>，与 {@code AdminAgentController#getMySkillZipByAgentId}
 * 交付的<b>外部 Agent 接入手册</b>（ZIP 内名 {@code SKILL.md}）是两件事。路径用
 * {@code /api/skills/packages}，与只读目录 {@code /api/skills/catalog} 各司其职。</p>
 *
 * <p><b>分层</b>：本类只做「接收请求 / 校验转换 / 调 Service / 返回结果」（CODE_STYLE §10）；
 * 闸门、解析、版本策略、落库与审计都在 {@link SkillPackageInstallService} 的一个事务里。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/skills/packages")
@RequiredArgsConstructor
public class SkillPackageController {

    private final SkillPackageInstallService installService;
    private final SysUserService sysUserService;

    /**
     * 安装技能包（上传 zip）。
     *
     * @param confirmUpgrade 同名且新版本更高时，不带本参数会返回 409 要求确认（`D-2026-10-10-1⑦`）
     */
    @SaCheckPermission("skill:install")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<InstalledSkillPackageResponse> install(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "confirmUpgrade", defaultValue = "false") boolean confirmUpgrade) {
        InstalledSkillPackage row =
                installService.install(readBytes(file), confirmUpgrade, currentOperator());
        return R.ok(toResponse(row));
    }

    /**
     * 激活指定版本：已在库 ⇒ 直接回滚（不传包体）；不在库 ⇒ 带包体做低版本覆盖。
     */
    @SaCheckPermission("skill:install")
    @PostMapping(value = "/{name}/activate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<InstalledSkillPackageResponse> activate(
            @PathVariable("name") String name,
            @RequestParam("version") String version,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        byte[] zip = (file == null || file.isEmpty()) ? null : readBytes(file);
        InstalledSkillPackage row =
                installService.activate(name, version, zip, currentOperator());
        return R.ok(toResponse(row));
    }

    /** 列出全部已安装技能包（含历史版本与已卸载行，按 name 升序、版本倒序）。 */
    @SaCheckPermission("skill:view")
    @GetMapping
    public R<List<InstalledSkillPackageResponse>> list() {
        return R.ok(installService.listAll().stream().map(SkillPackageController::toResponse).toList());
    }

    /** 卸载（停用）指定版本；行保留（DISABLED），审计与回滚都需要它。 */
    @SaCheckPermission("skill:uninstall")
    @DeleteMapping("/{id}")
    public R<Void> uninstall(@PathVariable("id") Long id) {
        installService.uninstall(id, currentOperator());
        return R.ok();
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "[EMPTY] 上传文件为空");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException(400, "[READ_FAIL] 读取上传内容失败: " + e.getMessage());
        }
    }

    /**
     * 取当前操作人：Sa-Token 只存了 userId，显示名要回查 {@code sys_user}。
     *
     * <p>取不到时**不阻断**——审计只丢显示名，业务照常（审计不得反向卡业务）。</p>
     */
    private Operator currentOperator() {
        try {
            Long userId = Long.valueOf(StpUtil.getLoginId().toString());
            SysUser user = sysUserService.getById(userId);
            String displayName = "";
            if (user != null) {
                displayName = (user.getNickname() != null && !user.getNickname().isBlank())
                        ? user.getNickname() : user.getUsername();
            }
            return Operator.of(String.valueOf(userId), displayName);
        } catch (Exception e) {
            log.warn("取当前操作人失败，本条审计只记动作不记人: {}", e.getMessage());
            return Operator.of("", "");
        }
    }

    private static InstalledSkillPackageResponse toResponse(InstalledSkillPackage row) {
        InstalledSkillPackageResponse r = new InstalledSkillPackageResponse();
        r.setId(row.getId());
        r.setName(row.getName());
        r.setVersion(row.getVersion());
        r.setDescription(row.getDescription());
        // DTO 侧保持字符串契约（对外稳定），实体侧是枚举（§12.2）—— 边界处显式转换
        r.setState(row.getState() == null ? null : row.getState().name());
        r.setRequiredTools(row.getRequiredTools());
        r.setChecksumSha256(row.getChecksumSha256());
        r.setOriginZipUrl(row.getOriginZipUrl());
        r.setCreateTime(row.getCreateTime());
        r.setCreateBy(row.getCreateBy());
        return r;
    }

}
