package com.helloai.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.DashboardOverview;
import com.helloai.api.dto.admin.DashboardTrend;
import com.helloai.api.dto.attachment.AttachmentVO;
import com.helloai.api.dto.duty.DutyAgentLatestResponse;
import com.helloai.api.dto.review.ReviewResponse;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.core.agent.service.McpToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JacksonConfig} 的 Long 序列化两层口径单测。
 *
 * <p><b>要守住的四件事</b>：</p>
 * <ol>
 *   <li><b>非 ID 数值 ⇒ JSON 数字</b>：分页元数据、仪表盘计数、耗时、字节数——它们被旧的
 *       「按类型一刀切」写成字符串，与前端 TS 类型（{@code number}）不符，并曾使 Element Plus
 *       分页组件校验失败。</li>
 *   <li><b>ID ⇒ JSON 字符串</b>：含<b>命名不符 {@code *Id} 约定</b>的 ID 字段（{@code reviewerAgent}
 *       / {@code assignedAgent}）——它们靠显式 {@code @JsonSerialize} 声明，属性修饰器必须<b>尊重
 *       而不覆盖</b>（否则雪花 ID 在前端静默丢精度）。</li>
 *   <li><b>修饰器的盲区保持字符串（无精度回归）</b>：顶层 {@code List<Long>}（{@code R<List<Long>>}）、
 *       {@code Map<String,Object>} 中的 Long 值——修饰器看不见这些位置，默认层必须兜住。</li>
 *   <li><b>命名判定的边界</b>：{@code valid} / {@code idleMinutes} 不得被误判为 ID。</li>
 * </ol>
 */
class JacksonConfigLongSerializationTest {

    /** 19 位雪花 ID（> JS Number.MAX_SAFE_INTEGER），用于精度相关的断言。 */
    private static final long SNOWFLAKE_ID = 2088624140014718977L;

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonLongSerializationCustomizer().customize(builder);
        mapper = builder.build();
    }

    private JsonNode json(Object value) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    // ──────────────── 1. 非 ID 数值 ⇒ 数字 ────────────────

    @Test
    @DisplayName("分页元数据：total/pages/current 下发 JSON 数字")
    void pageMetadataAreNumbers() throws Exception {
        PageResult<String> page = new PageResult<>();
        page.setList(List.of("a"));
        page.setTotal(123L);
        page.setPages(7L);
        page.setCurrent(3L);

        JsonNode node = json(page);

        assertTrue(node.get("total").isNumber(), "total 应为数字，实际=" + node.get("total"));
        assertTrue(node.get("pages").isNumber(), "pages 应为数字，实际=" + node.get("pages"));
        assertTrue(node.get("current").isNumber(), "current 应为数字，实际=" + node.get("current"));
        assertEquals(123L, node.get("total").asLong());
    }

    @Test
    @DisplayName("仪表盘计数：DashboardOverview 全部计数为 JSON 数字")
    void dashboardOverviewCountsAreNumbers() throws Exception {
        DashboardOverview overview = new DashboardOverview();
        overview.setTotalTasks(11L);
        overview.setActiveAgents(2L);
        overview.setPendingReviews(3L);

        JsonNode node = json(overview);

        assertTrue(node.get("totalTasks").isNumber(), "totalTasks 应为数字");
        assertTrue(node.get("activeAgents").isNumber(), "activeAgents 应为数字");
        assertTrue(node.get("pendingReviews").isNumber(), "pendingReviews 应为数字");
        assertEquals(11L, node.get("totalTasks").asLong());
    }

    @Test
    @DisplayName("计数与字节数：leaseCount / fileSize 为 JSON 数字，id 类字段仍为字符串")
    void countAndSizeAreNumbersWhileIdsStayStrings() throws Exception {
        DutyAgentLatestResponse duty = new DutyAgentLatestResponse();
        duty.setLeaseCount(42L);
        JsonNode dutyNode = json(duty);
        assertTrue(dutyNode.get("leaseCount").isNumber(), "leaseCount 应为数字");

        AttachmentVO attachment = new AttachmentVO();
        attachment.setId(SNOWFLAKE_ID);
        attachment.setSubTaskId(SNOWFLAKE_ID);
        attachment.setTaskId(SNOWFLAKE_ID);
        attachment.setFileSize(2048L);
        JsonNode attNode = json(attachment);
        assertTrue(attNode.get("fileSize").isNumber(), "fileSize 应为数字（非 ID 数值）");
        assertTrue(attNode.get("id").isTextual(), "id 必须仍是字符串（雪花 ID 精度）");
        assertTrue(attNode.get("subTaskId").isTextual(), "subTaskId 必须仍是字符串");
        assertTrue(attNode.get("taskId").isTextual(), "taskId 必须仍是字符串");
        assertEquals(String.valueOf(SNOWFLAKE_ID), attNode.get("id").asText());
    }

    @Test
    @DisplayName("List<Long> 计数序列：DashboardTrend 三序列元素为 JSON 数字（contentUsing 生效）")
    void dashboardTrendCountSeriesAreNumbers() throws Exception {
        DashboardTrend trend = new DashboardTrend();
        trend.setDates(List.of("2026-10-06", "2026-10-07"));
        trend.setCreatedCounts(List.of(3L, 5L));
        trend.setCompletedCounts(List.of(1L, 2L));
        trend.setReviewedCounts(List.of(4L, 6L));

        JsonNode node = json(trend);

        for (String field : List.of("createdCounts", "completedCounts", "reviewedCounts")) {
            assertTrue(node.get(field).isArray(), field + " 应为数组");
            assertTrue(node.get(field).get(0).isNumber(), field + " 元素应为数字，实际=" + node.get(field));
        }
    }

    // ──────────────── 2. ID ⇒ 字符串（含显式声明者不被覆盖） ────────────────

    @Test
    @DisplayName("显式声明的 ID：reviewerAgent 不被属性修饰器覆盖，仍为字符串")
    void explicitlyAnnotatedReviewerAgentStaysString() throws Exception {
        ReviewResponse review = new ReviewResponse();
        review.setId(SNOWFLAKE_ID);
        review.setSubTaskId(SNOWFLAKE_ID);
        review.setReviewerAgent(SNOWFLAKE_ID);

        JsonNode node = json(review);

        assertTrue(node.get("id").isTextual(), "id 应为字符串");
        assertTrue(node.get("subTaskId").isTextual(), "subTaskId 应为字符串");
        assertTrue(node.get("reviewerAgent").isTextual(),
                "reviewerAgent 语义是 Agent ID（命名不符 *Id），必须保持字符串");
        assertEquals(String.valueOf(SNOWFLAKE_ID), node.get("reviewerAgent").asText());
    }

    @Test
    @DisplayName("ID 语义命名：assignedAgent 显式声明为字符串，dependsOn 的元素（ID 列表）亦为字符串")
    void subTaskResponseIdsStayStrings() throws Exception {
        SubTaskResponse response = new SubTaskResponse();
        response.setId(SNOWFLAKE_ID);
        response.setTaskId(SNOWFLAKE_ID);
        response.setAssignedAgent(SNOWFLAKE_ID);
        response.setDependsOn(List.of(SNOWFLAKE_ID));

        JsonNode node = json(response);

        assertTrue(node.get("id").isTextual(), "id 应为字符串");
        assertTrue(node.get("taskId").isTextual(), "taskId 应为字符串");
        assertTrue(node.get("assignedAgent").isTextual(), "assignedAgent 应为字符串");
        assertTrue(node.get("dependsOn").get(0).isTextual(),
                "dependsOn 是 ID 列表（contentUsing=ToString），元素必须保持字符串");
    }

    @Test
    @DisplayName("MCP 外部契约：ClaimSubTaskResult.assignedAgent/subTaskId 均为字符串")
    void mcpClaimResultIdsStayStrings() throws Exception {
        McpToolService.ClaimSubTaskResult result = new McpToolService.ClaimSubTaskResult();
        result.setOk(true);
        result.setClaimed(true);
        result.setAssignedAgent(SNOWFLAKE_ID);
        result.setSubTaskId(SNOWFLAKE_ID);

        JsonNode node = json(result);

        assertTrue(node.get("assignedAgent").isTextual(), "MCP assignedAgent 必须为字符串");
        assertTrue(node.get("subTaskId").isTextual(), "MCP subTaskId 必须为字符串");
    }

    // ──────────────── 3. 修饰器盲区：保持旧行为（无精度回归） ────────────────

    @Test
    @DisplayName("盲区 1：顶层 List<Long>（R<List<Long>> 形如 ID 集合）元素仍为字符串")
    void topLevelLongListElementsStayStrings() throws Exception {
        JsonNode node = json(List.of(SNOWFLAKE_ID));

        assertTrue(node.get(0).isTextual(), "顶层 List<Long> 无属性名可判语义，必须保持字符串");
        assertEquals(String.valueOf(SNOWFLAKE_ID), node.get(0).asText());
    }

    @Test
    @DisplayName("盲区 2：Map<String,Object> 中的 Long 值仍为字符串")
    void longInsideUntypedMapStaysString() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", SNOWFLAKE_ID);
        body.put("count", 5L);

        JsonNode node = json(body);

        assertTrue(node.get("agentId").isTextual(), "Map 中 Long 值无法判定语义，必须保持字符串");
        assertEquals(String.valueOf(SNOWFLAKE_ID), node.get("agentId").asText());
        assertTrue(node.get("count").isTextual(), "Map 中非 ID 数值亦为字符串（保守：宁可不变形）");
    }

    // ──────────────── 4. 命名判定边界 ────────────────

    @Test
    @DisplayName("ID 命名判定：id/*Id 命中；valid/idleMinutes/total/fileSize 不误判")
    void idNamePredicate() {
        assertTrue(JacksonConfig.isIdName("id"), "恰为 id");
        assertTrue(JacksonConfig.isIdName("ID"), "恰为 ID（忽略大小写）");
        assertTrue(JacksonConfig.isIdName("taskId"), "Id 后缀");
        assertTrue(JacksonConfig.isIdName("reviewerAgentId"), "Id 后缀");
        assertTrue(JacksonConfig.isIdName("taskID"), "ID 后缀");

        assertFalse(JacksonConfig.isIdName("valid"), "valid 以 id 结尾但不是 ID（大小写敏感）");
        assertFalse(JacksonConfig.isIdName("idleMinutes"), "idleMinutes 以 id 开头但不是 ID");
        assertFalse(JacksonConfig.isIdName("total"), "普通计数");
        assertFalse(JacksonConfig.isIdName("fileSize"), "字节数");
        assertFalse(JacksonConfig.isIdName("createdBy"), "命名不符，需显式注解");
        assertFalse(JacksonConfig.isIdName("assignedAgent"), "命名不符，需显式注解");
        assertFalse(JacksonConfig.isIdName(null), "null 安全");
    }
}
