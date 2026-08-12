package com.helloai.core.task.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionRecordParser 单测——围栏协议 VERIFICATION 段解析 + SKILL.md 官方示例绑定（A0-9）。
 *
 * <p>覆盖三态：携带 VERIFICATION / 缺失 VERIFICATION（仅检测不拦截）/ 格式畸形（返回 null）；
 * 另将 SKILL.md §4.4 两个官方示例原文作为解析输入（文档-解析器绑定，防示例漂移）。</p>
 */
@DisplayName("ExecutionRecordParser")
class ExecutionRecordParserTest {

    private static final Long SUB_TASK_ID = 21L;
    private static final Long AGENT_ID = 7L;

    @Test
    @DisplayName("携带 VERIFICATION 段：证据原文完整解析，hasVerification=true")
    void shouldParseVerificationWhenPresent() {
        String raw = """
                实现了用户管理接口。

                ## EXECUTION_RECORD
                SUMMARY: 实现了 RESTful 用户管理接口
                KEY_DECISIONS:
                - 分页默认 20 条/页
                DELIVERABLES:
                - src/main/java/UserController.java
                VERIFICATION:
                - 命令: mvn -pl helloai-core -am compile
                - 输出: BUILD SUCCESS
                - 结论: 通过
                """;

        ExecutionRecord record = ExecutionRecordParser.parse(raw, SUB_TASK_ID, "用户管理", AGENT_ID);

        assertThat(record).isNotNull();
        assertThat(record.summary()).isEqualTo("实现了 RESTful 用户管理接口");
        assertThat(record.hasVerification()).isTrue();
        assertThat(record.verification())
                .contains("命令: mvn -pl helloai-core -am compile")
                .contains("输出: BUILD SUCCESS")
                .contains("结论: 通过");
    }

    @Test
    @DisplayName("缺失 VERIFICATION 段：解析仍成功（仅检测不拦截），证据为空串")
    void shouldSucceedWithEmptyVerificationWhenAbsent() {
        String raw = """
                ## EXECUTION_RECORD
                SUMMARY: 修复了分页参数校验
                DELIVERABLES:
                - src/main/java/UserService.java
                """;

        ExecutionRecord record = ExecutionRecordParser.parse(raw, SUB_TASK_ID, "分页修复", AGENT_ID);

        assertThat(record).isNotNull();
        assertThat(record.summary()).isEqualTo("修复了分页参数校验");
        assertThat(record.hasVerification()).isFalse();
        assertThat(record.verification()).isEmpty();
    }

    @Test
    @DisplayName("格式畸形（缺 SUMMARY）：返回 null，维持既有 fallback 语义")
    void shouldReturnNullWhenSummaryMissing() {
        String raw = """
                ## EXECUTION_RECORD
                DELIVERABLES:
                - src/main/java/UserService.java
                VERIFICATION:
                - 命令: mvn compile
                """;

        ExecutionRecord record = ExecutionRecordParser.parse(raw, SUB_TASK_ID, "畸形提交", AGENT_ID);

        assertThat(record).isNull();
    }

    @Test
    @DisplayName("JSONB 序列化边界：verification 经 toMap/fromMap 往返不丢失")
    void shouldRoundTripVerificationThroughMap() {
        ExecutionRecord record = ExecutionRecord.builder()
                .subTaskId(SUB_TASK_ID)
                .summary("带证据的记录")
                .verification("- 命令: mvn test\n- 输出: Tests run: 6\n- 结论: 通过")
                .build();

        Map<String, Object> map = record.toMap();
        ExecutionRecord restored = ExecutionRecord.fromMap(map);

        assertThat(restored).isNotNull();
        assertThat(restored.verification()).isEqualTo(record.verification());
        assertThat(restored.hasVerification()).isTrue();
    }

    @Test
    @DisplayName("JSONB 序列化边界：无证据时 toMap 不写 verification 键")
    void shouldOmitVerificationKeyWhenAbsent() {
        ExecutionRecord record = ExecutionRecord.builder()
                .subTaskId(SUB_TASK_ID)
                .summary("无证据的记录")
                .build();

        assertThat(record.toMap()).doesNotContainKey("verification");
    }

    @Test
    @DisplayName("SKILL.md §4.4 官方示例（Java 交付场景）五块完整解析，字段与示例一致")
    void shouldParseSkillDocJavaExample() {
        String raw = """
                ## EXECUTION_RECORD
                SUMMARY: 实现了 RESTful 用户管理接口，含分页查询、新增、删除、参数校验
                KEY_DECISIONS:
                - 分页默认 20 条/页，最大允许 100
                - 密码用 BCrypt 加密，盐值自动生成
                DOWNSTREAM_NOTES:
                - 接口 Base URL: POST/GET /api/users
                - 前端适配时注意 Long 型 ID 精度，需用字符串接收
                DELIVERABLES:
                - src/main/java/.../UserController.java
                - src/main/java/.../UserService.java
                VERIFICATION:
                - 命令: mvn -pl helloai-core -am compile && mvn test -Dtest=UserControllerTest
                - 输出: BUILD SUCCESS / Tests run: 6, Failures: 0, Errors: 0
                - 结论: 通过
                """;

        ExecutionRecord record = ExecutionRecordParser.parse(raw, SUB_TASK_ID, "用户管理", AGENT_ID);

        assertThat(record).isNotNull();
        assertThat(record.summary()).startsWith("实现了 RESTful 用户管理接口");
        assertThat(record.keyDecisions()).containsExactly(
                "分页默认 20 条/页，最大允许 100",
                "密码用 BCrypt 加密，盐值自动生成");
        assertThat(record.downstreamNotes()).hasSize(2);
        assertThat(record.deliverables()).containsExactly(
                "src/main/java/.../UserController.java",
                "src/main/java/.../UserService.java");
        assertThat(record.hasVerification()).isTrue();
        assertThat(record.verification())
                .contains("命令: mvn -pl helloai-core -am compile && mvn test -Dtest=UserControllerTest")
                .contains("输出: BUILD SUCCESS / Tests run: 6, Failures: 0, Errors: 0")
                .contains("结论: 通过");
    }

    @Test
    @DisplayName("SKILL.md §4.4 官方示例（PowerShell 交付场景）五块完整解析，字段与示例一致")
    void shouldParseSkillDocPowerShellExample() {
        String raw = """
                ## EXECUTION_RECORD
                SUMMARY: 编写了验证脚本 verify-x.ps1 并实测通过，六场景 ALL PASSED
                KEY_DECISIONS:
                - 遵循编码约定：脚本存 UTF-8 with BOM，运行时输出用单引号拼接保持 ASCII
                DOWNSTREAM_NOTES:
                - 运行前需后端已启动（端口 6565）且 docker postgres 就绪
                DELIVERABLES:
                - scripts/powershell/verify-x.ps1
                VERIFICATION:
                - 命令: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/powershell/verify-x.ps1
                - 输出: 六场景全 PASS，末行 ALL PASSED
                - 结论: 通过
                """;

        ExecutionRecord record = ExecutionRecordParser.parse(raw, SUB_TASK_ID, "交付验证脚本", AGENT_ID);

        assertThat(record).isNotNull();
        assertThat(record.summary()).isEqualTo("编写了验证脚本 verify-x.ps1 并实测通过，六场景 ALL PASSED");
        assertThat(record.keyDecisions()).containsExactly(
                "遵循编码约定：脚本存 UTF-8 with BOM，运行时输出用单引号拼接保持 ASCII");
        assertThat(record.downstreamNotes()).hasSize(1);
        assertThat(record.deliverables()).containsExactly("scripts/powershell/verify-x.ps1");
        assertThat(record.hasVerification()).isTrue();
        assertThat(record.verification())
                .contains("命令: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/powershell/verify-x.ps1")
                .contains("输出: 六场景全 PASS，末行 ALL PASSED")
                .contains("结论: 通过");
    }
}
