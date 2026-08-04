package com.helloai.api.controller;

import com.helloai.api.dto.admin.ConfigBatchRequest;
import com.helloai.common.base.R;
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

    private final SysConfigService sysConfigService;

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
        return R.ok(Map.of(key, value != null ? value : ""));
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
