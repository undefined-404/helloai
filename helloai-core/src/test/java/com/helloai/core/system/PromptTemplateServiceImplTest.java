package com.helloai.core.system;

import com.helloai.core.system.service.RuleService;
import com.helloai.core.system.service.impl.PromptTemplateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能包 ZIP 构建测试：SKILL.md 占位符渲染、scripts/ 全量脚本打包、config.example.json baseUrl 预填且 apiKey 保留占位。
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceImplTest {

    @Mock
    private RuleService ruleService;

    @InjectMocks
    private PromptTemplateServiceImpl service;

    private Map<String, byte[]> readZipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void buildSkillPackageZip_containsRenderedSkillAndAllScripts() throws Exception {
        byte[] zip = service.buildSkillPackageZip(
                "EXECUTOR", "ak_test_key_123", "http://example.test:8080", "测试执行器", 42L);

        Map<String, byte[]> entries = readZipEntries(zip);

        // 完整结构：顶层 <role>-skill/ 目录下 SKILL.md + scripts/ 全量（Trae 要求的整体复制形态）
        assertEquals(8, entries.size());
        String top = "executor-skill/";
        assertNotNull(entries.get(top + "SKILL.md"));
        assertNotNull(entries.get(top + "scripts/clock.ps1"));
        assertNotNull(entries.get(top + "scripts/clock.sh"));
        assertNotNull(entries.get(top + "scripts/pull_tasks.ps1"));
        assertNotNull(entries.get(top + "scripts/pull_tasks.sh"));
        assertNotNull(entries.get(top + "scripts/process_one.ps1"));
        assertNotNull(entries.get(top + "scripts/process_one.sh"));
        assertNotNull(entries.get(top + "scripts/config.example.json"));

        // SKILL.md 占位符已渲染：apiKey / baseUrl / agentId
        String skill = new String(entries.get(top + "SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(skill.contains("ak_test_key_123"), "SKILL.md 应渲染 apiKey");
        assertTrue(skill.contains("http://example.test:8080"), "SKILL.md 应渲染 baseUrl");
        assertTrue(skill.contains("42"), "SKILL.md 应渲染 agentId");
        assertFalse(skill.contains("<注册后填入>"), "SKILL.md 不应残留 apiKey 占位");

        // config.example.json：baseUrl 预填为实际服务地址，apiKey 保留模板占位（由下载者填写）
        String config = new String(entries.get(top + "scripts/config.example.json"), StandardCharsets.UTF_8);
        assertTrue(config.contains("\"baseUrl\": \"http://example.test:8080\""), "config 应预填 baseUrl");
        assertTrue(config.contains("ak_xxxxxxxx"), "config 应保留 apiKey 模板占位");
        // 仅断言 baseUrl 字段被替换（注释文本中的 localhost 示例说明属模板固有内容，保留合理）
        assertFalse(config.contains("\"baseUrl\": \"http://localhost:6565\""), "config 的 baseUrl 字段不应残留 localhost 占位");
    }

    @Test
    void buildSkillPackageZip_unknownRole_throws() {
        try {
            service.buildSkillPackageZip("UNKNOWN_ROLE", "k", "http://x", "n", 1L);
            // 未抛异常则失败
            throw new AssertionError("未知角色应抛出 BizException");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("未找到角色"), "异常信息应指明角色缺失，实际: " + e.getMessage());
        }
    }
}