package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.admin.ConfigBatchRequest;
import com.helloai.common.base.R;
import com.helloai.core.planner.search.WebSearchCredentialKeyStore;
import com.helloai.core.planner.service.WebSearchService;
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
    private final WebSearchService webSearchService;

    /**
     * 获取所有配置
     */
    @SaCheckPermission("config:view")
    @GetMapping
    public R<Map<String, String>> getAll() {
        return R.ok(sysConfigService.getAllAsMap());
    }

    /**
     * 获取单个配置
     */
    @SaCheckPermission("config:view")
    @GetMapping("/getByKey/{key}")
    public R<Map<String, String>> getByKey(@PathVariable("key") String key) {
        String value = sysConfigService.getValue(key);
        if (key.endsWith(SENSITIVE_KEY_SUFFIX) && value != null && !value.isBlank()) {
            value = SENSITIVE_MASKED;
        }
        return R.ok(Map.of(key, value != null ? value : ""));
    }

    /**
     * 博查联网搜索 API Key 的配置状态（脱敏回显，不含明文）。
     *
     * <p>{@code getAll} 对 {@code .api-key} 后缀键统一回显 {@code ********}，无法展示
     * 「前 4 + 后 4」的形态；设置页需要与 LLM Provider 区块同形的脱敏串，故单列本端点，
     * 由 {@link WebSearchCredentialKeyStore} 解密后按 {@code SecretMask} 口径脱敏。</p>
     */
    @SaCheckPermission("config:view")
    @GetMapping("/webSearchApiKey")
    public R<Map<String, Object>> getWebSearchApiKey() {
        String masked = webSearchCredentialKeyStore.maskedBochaApiKey();
        return R.ok(Map.of(
                "configured", masked != null,
                "masked", masked != null ? masked : ""
        ));
    }

    /**
     * 保存博查联网搜索 API Key（系统设置页「联网搜索」区）。
     * <p>明文仅入参态存在，落库前经 AES-GCM 加密写 sys_config；
     * blank 视为清除。实时生效，无需重启。</p>
     */
    @SaCheckPermission("config:edit")
    @PutMapping("/webSearchApiKey")
    public R<Void> saveWebSearchApiKey(@RequestBody Map<String, String> body) {
        webSearchCredentialKeyStore.saveBochaApiKey(body.get("value"));
        return R.ok();
    }

    /**
     * 验证联网搜索（博查）API Key：发送最小搜索请求（query=ping、count=1）探测。
     *
     * <p>返回 {@code success / message / supported / elapsedMs}；验证失败不抛异常，
     * success=false + 可读 message，前端直接展示。</p>
     */
    @SaCheckPermission("config:edit")
    @PostMapping("/verifyWebSearchApiKey")
    public R<Map<String, Object>> verifyWebSearchApiKey() {
        return R.ok(webSearchService.verifyApiKey());
    }

    /**
     * 更新单个配置
     */
    @SaCheckPermission("config:edit")
    @PutMapping("/updateByKey/{key}")
    public R<Void> updateByKey(@PathVariable("key") String key, @RequestBody Map<String, String> body) {
        sysConfigService.setValue(key, body.get("value"));
        return R.ok();
    }

    /**
     * 批量更新配置
     */
    @SaCheckPermission("config:edit")
    @PutMapping("/batch")
    public R<Void> batchUpdate(@RequestBody ConfigBatchRequest req) {
        sysConfigService.batchUpdate(req.getConfig());
        log.info("管理员批量更新配置: {} 项", req.getConfig().size());
        return R.ok();
    }
}
