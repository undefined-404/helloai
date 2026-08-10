package com.helloai.core.task.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionRecordParser 单测——围栏协议 VERIFICATION 段解析。
 *
 * <p>覆盖三态：携带 VERIFICATION / 缺失 VERIFICATION（仅检测不拦截）/ 格式畸形（返回 null）。</p>
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
}
