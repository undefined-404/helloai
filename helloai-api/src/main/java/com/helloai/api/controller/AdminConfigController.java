package com.helloai.api.controller;

import com.helloai.api.dto.admin.ConfigBatchRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.search.WebSearchCredentialKeyStore;
import com.helloai.core.system.entity.SysConfig;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    /** 凭证类配置键后缀：读取端点对外脱敏，与 SysConfigServiceImpl 口径一致。 */
    private static final String SENSITIVE_KEY_SUFFIX = ".api-key";

    private static final String SENSITIVE_MASKED = "********";

    private final SysConfigService sysConfigService;
    private final WebSearchCredentialKeyStore webSearchCredentialKeyStore;

    /**
     * 获取所有配置
     */
    @GetMapping
    public R<Map<String, String>> getAll() {
        return R.ok(sysConfigService.getAllAsMap());
    }

    /**
     * 获取单个配置
     */
    @GetMapping("/getByKey/{key}")
    public R<Map<String, String>> getByKey(@PathVariable("key") String key) {
        String value = sysConfigService.getValue(key);
        if (key.endsWith(SENSITIVE_KEY_SUFFIX) && value != null && !value.isBlank()) {
            value = SENSITIVE_MASKED;
        }
        return R.ok(Map.of(key, value != null ? value : ""));
    }

    /**
     * 保存博查联网搜索 API Key（系统设置页「联网搜索」区）。
     * <p>明文仅入参态存在，落库前经 AES-GCM 加密写 sys_config；
     * blank 视为清除。实时生效，无需重启。</p>
     */
    @PutMapping("/webSearchApiKey")
    public R<Void> saveWebSearchApiKey(@RequestBody Map<String, String> body) {
        webSearchCredentialKeyStore.saveBochaApiKey(body.get("value"));
        return R.ok();
    }

    /**
     * 更新单个配置
     */
    @PutMapping("/updateByKey/{key}")
    public R<Void> updateByKey(@PathVariable("key") String key, @RequestBody Map<String, String> body) {
        sysConfigService.setValue(key, body.get("value"));
        return R.ok();
    }

    /**
     * 批量更新配置
     */
    @PutMapping("/batch")
    public R<Void> batchUpdate(@RequestBody ConfigBatchRequest req) {
        sysConfigService.batchUpdate(req.getConfig());
        log.info("管理员批量更新配置: {} 项", req.getConfig().size());
        return R.ok();
    }
}
