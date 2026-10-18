package com.helloai.core.event;

import com.helloai.common.constant.ToolDisplayName;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具名目录服务（2026-10-18，工具名 → 中文展示名的服务端单一来源）。
 *
 * <p>与 {@link EventCatalogService} 同范式：目录内容驱动自 {@link ToolDisplayName} 枚举
 * （唯一事实源），前端不保留硬编码副本，经 {@code /api/tools/catalog} 拉取。
 * 只读、无 DB：遍历枚举下发。</p>
 *
 * <p><b>为什么要服务端下发而不是前端硬编码</b>：工具清单随 {@code InternalLlmExecutorTools}
 * 与 MCP 工具注册变化，前端本地维护一份映射必然漂移；且「internal_run_in_sandbox 叫什么」
 * 属于产品口径，应在后端一处定义。</p>
 */
@Service
public class ToolCatalogService {

    /** 全量工具展示名目录（按枚举声明序：内部执行链 → 对外 MCP）。 */
    public List<ToolCatalogItem> catalog() {
        ToolDisplayName[] values = ToolDisplayName.values();
        List<ToolCatalogItem> items = new ArrayList<>(values.length);
        for (ToolDisplayName name : values) {
            items.add(new ToolCatalogItem(name.toolName(), name.label(), name.desc()));
        }
        return items;
    }
}
