package com.helloai.api.controller;

import com.helloai.api.dto.admin.SetupInitializeRequest;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.core.system.service.AuthService;
import com.helloai.core.system.service.SysConfigService;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SysConfigService sysConfigService;
    private final SysUserService sysUserService;
    private final AuthService authService;
    private final AgentConfigProperties agentConfig;

    /**
     * 检查初始化状态
     */
    @GetMapping("/getStatus")
    public R<Map<String, Object>> getStatus() {
        boolean finished = sysConfigService.isSetupFinished();
        long userCount = sysUserService.count();
        return R.ok(Map.of(
                "setupFinished", finished,
                "hasUsers", userCount > 0,
                "userCount", userCount
        ));
    }

    /**
     * 执行初始化
     */
    @PostMapping("/initialize")
    public R<Void> initialize(@RequestBody SetupInitializeRequest req) {
        if (sysConfigService.isSetupFinished()) {
            return R.fail("系统已完成初始化，不能重复执行");
        }

        // 创建管理员用户
        if (req.getAdminUsername() != null && req.getAdminPassword() != null) {
            sysUserService.create(req.getAdminUsername(), req.getAdminPassword(),
                    "系统管理员", "SUPER_ADMIN");
        }

        // 更新系统配置
        if (req.getSystemName() != null) {
            sysConfigService.setValue("system.name", req.getSystemName());
        }
        if (req.getSystemDescription() != null) {
            sysConfigService.setValue("system.description", req.getSystemDescription());
        }

        // 标记向导完成
        sysConfigService.setValue("system.setup_finished", "1");

        log.info("初始化向导完成: systemName={}, adminUser={}",
                req.getSystemName(), req.getAdminUsername());
        return R.ok();
    }

    /**
     * 重新检查
     */
    @GetMapping("/check")
    public R<Map<String, Object>> check() {
        return getStatus();
    }
}
