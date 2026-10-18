package com.helloai.core.system.service;

import com.helloai.common.constant.ActivityAction;
import com.helloai.common.constant.InboxEventType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举字典服务（2026-10-18，**服务端单一事实源**）。
 *
 * <p><b>为什么集中出字典</b>：前端多处把后端枚举/码值硬编码成本地映射
 * （如 {@code types/dialog.ts} 的 {@code INBOX_EVENT_TYPE_MAP}），必然漂移 ——
 * 实测后端发 10 种收件箱事件、前端只映射 4 个，页面直接裸露 {@code sub_task.approved}。
 * 集中下发后，新增枚举只需改后端一处 + 前端自动可见。</p>
 *
 * <p><b>与既有目录的关系</b>：
 * {@link com.helloai.common.constant.TimelineEventCode}（时间线事件）、
 * {@link com.helloai.common.constant.ToolDisplayName}（工具名）、
 * {@link InboxEventType}（收件箱事件）、{@link ActivityAction}（活动动作）
 * 各自仍是"该领域唯一事实源"，本服务只做**聚合下发**，不复制标签。</p>
 */
@Service
public class EnumCatalogService {

    /** 一组枚举项的通用形态：code → label/desc。 */
    public record EnumItem(String code, String label, String desc) {
    }

    /**
     * 全量枚举字典（按分组）。
     *
     * @return key = 分组名（前端按此取用），value = 该组枚举项列表
     */
    public Map<String, List<EnumItem>> catalog() {
        Map<String, List<EnumItem>> out = new LinkedHashMap<>();
        out.put("inboxEventType", inboxEventTypes());
        out.put("activityAction", activityActions());
        return out;
    }

    private List<EnumItem> inboxEventTypes() {
        List<EnumItem> items = new ArrayList<>();
        for (InboxEventType e : InboxEventType.values()) {
            items.add(new EnumItem(e.code(), e.label(), e.desc()));
        }
        return items;
    }

    private List<EnumItem> activityActions() {
        List<EnumItem> items = new ArrayList<>();
        for (ActivityAction a : ActivityAction.values()) {
            items.add(new EnumItem(a.code(), a.label(), a.desc()));
        }
        return items;
    }
}
