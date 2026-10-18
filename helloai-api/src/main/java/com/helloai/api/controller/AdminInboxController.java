package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端收件箱（2026-10-18）。
 *
 * <p><b>为什么单独一套端点</b>：原 {@code /api/agent/inbox} 的四个接口都以
 * {@code @RequestAttribute("_authId")} 取 Agent 身份，语义是「我（Agent）的收件箱」。
 * admin 不是 Agent、没有 {@code _authId} ⇒ 该接口恒返回空数组，
 * 而前端「收件箱」页面恰恰用的是它 —— 实测 {@code agent_inbox} 有 53 条，
 * 页面却是空的（不是没数据，是**接口语义错配**）。</p>
 *
 * <p>本控制器面向管理端：可看全部、可按 Agent 过滤、可跨 Agent 标记已读/归档。
 * <b>不改动 Agent 侧契约</b>（避免扩大越权面：外部 Agent 依然只能看自己的）。</p>
 *
 * <p>权限：{@code inbox:view}（与菜单一致）；标记类动作同权限——
 * 收件箱消息是「通知」，管理端标记不涉及业务数据变更，无需更细的权限位。</p>
 */
@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class AdminInboxController {

    private final AgentInboxService inboxService;
    private final AgentService agentService;

    /**
     * 分页查询收件箱（管理端）。
     *
     * @param agentId 可选，按 Agent 过滤；不传 = 全部 Agent
     * @param isRead  可选，0=未读 1=已读；不传 = 不限（管理端默认看全部）
     * @param includeArchived 是否含已归档，默认 false
     */
    @SaCheckPermission("inbox:view")
    @GetMapping("/list")
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "isRead", required = false) Integer isRead,
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        IPage<AgentInbox> result = inboxService.pageForAdmin(agentId, isRead, includeArchived, page, pageSize);
        // 批量补 Agent 名字（页面只显示 agentId 无法辨认是谁的收件箱）
        Map<Long, String> agentNames = resolveAgentNames(result.getRecords());
        return R.ok(PageResult.of(result, r -> toRow(r, agentNames)));
    }

    /** 未读数（管理端）：不传 agentId = 全平台未读总数。 */
    @SaCheckPermission("inbox:view")
    @GetMapping("/getUnreadCount")
    public R<Map<String, Object>> getUnreadCount(
            @RequestParam(value = "agentId", required = false) Long agentId) {
        return R.ok(Map.of("total_unread", inboxService.countUnreadForAdmin(agentId)));
    }

    /** 标记已读（管理端，按消息主键；不校验 Agent 归属）。 */
    @SaCheckPermission("inbox:view")
    @PostMapping("/markReadById/{id}")
    public R<Void> markReadById(@PathVariable("id") Long id) {
        inboxService.markReadById(id);
        return R.ok();
    }

    /** 归档（管理端，按消息主键；不校验 Agent 归属）。 */
    @SaCheckPermission("inbox:view")
    @PostMapping("/archiveById/{id}")
    public R<Void> archiveById(@PathVariable("id") Long id) {
        inboxService.markArchivedById(id);
        return R.ok();
    }

    /** 单行投影：Long ID 一律转字符串（前端 LongId 契约，避免 Number 精度丢失）。 */
    private Map<String, Object> toRow(AgentInbox r, Map<Long, String> agentNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", String.valueOf(r.getId()));
        row.put("agentId", r.getAgentId() != null ? String.valueOf(r.getAgentId()) : null);
        row.put("agentName", r.getAgentId() != null ? agentNames.get(r.getAgentId()) : null);
        row.put("eventType", r.getEventType());
        row.put("eventId", r.getEventId());
        row.put("title", r.getTitle());
        row.put("summary", r.getSummary());
        row.put("refType", r.getRefType());
        row.put("refId", r.getRefId() != null ? String.valueOf(r.getRefId()) : null);
        row.put("isRead", r.getIsRead());
        row.put("isArchived", r.getIsArchived());
        row.put("priority", r.getPriority());
        row.put("readTime", r.getReadTime());
        row.put("createTime", r.getCreateTime());
        return row;
    }

    /** 批量取 Agent 名字（一次查询；已删除的 Agent 不出现 ⇒ 前端回退短 ID）。 */
    private Map<Long, String> resolveAgentNames(List<AgentInbox> records) {
        List<Long> ids = records.stream()
                .map(AgentInbox::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return agentService.listProfilesByIds(ids).stream()
                .collect(Collectors.toMap(AgentProfileSnapshot::id,
                        p -> p.name() != null ? p.name() : "",
                        (a, b) -> a));
    }
}
