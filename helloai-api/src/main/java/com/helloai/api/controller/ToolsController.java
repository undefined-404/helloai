package com.helloai.api.controller;

import com.helloai.common.base.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolsController {

    private static final String CLI_VERSION = "2";

    /**
     * 列出可用工具
     */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(List.of(
                Map.of(
                        "name", "task-cli",
                        "type", "python",
                        "version", CLI_VERSION,
                        "description", "HelloAI Agent 命令行工具，支持子任务轮询、提交、状态查看、SKILL 下载等",
                        "downloadUrl", "/api/tools/cli",
                        "usage", "python task-cli.py --key <API_KEY> <poll|submit|status|skill|update>"
                )
        ));
    }

    /**
     * 下载 CLI 工具
     */
    @GetMapping(value = "/cli", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> downloadCli() {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/task-cli.py");
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            String content = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);

            // 替换版本号占位符
            content = content.replace("CLI_VERSION = 1", "CLI_VERSION = " + CLI_VERSION);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=task-cli.py")
                    .header("X-CLI-Version", CLI_VERSION)
                    .body(content);
        } catch (IOException e) {
            log.error("读取 task-cli.py 失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取 CLI 版本号
     */
    @GetMapping("/cli/version")
    public R<Map<String, String>> cliVersion() {
        return R.ok(Map.of(
                "version", CLI_VERSION,
                "latest", CLI_VERSION,
                "downloadUrl", "/api/tools/cli"
        ));
    }

    /**
     * CLI 在线更新告知（版本检查）
     */
    @GetMapping("/cli/check-update")
    public R<Map<String, Object>> checkUpdate(@RequestParam(value = "currentVersion", defaultValue = "0") int currentVersion) {
        int latest = Integer.parseInt(CLI_VERSION);
        boolean hasUpdate = currentVersion < latest;
        return R.ok(Map.of(
                "hasUpdate", hasUpdate,
                "currentVersion", currentVersion,
                "latestVersion", latest,
                "downloadUrl", "/api/tools/cli"
        ));
    }
}
