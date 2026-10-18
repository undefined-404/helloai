package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.event.ToolCatalogItem;
import com.helloai.core.event.ToolCatalogService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具名目录端点（2026-10-18）：把 {@code internal_run_in_sandbox} 这类英文标识
 * 翻译成人话短名，供时间线/时序图渲染。
 *
 * <p>与 {@code /api/events/catalog} 同范式：只读目录、不建菜单、不新增权限面——
 * 前端拉取后本地缓存（与 eventCatalog store 一致）。</p>
 */
@RestController
@RequestMapping("/api/tools")
public class ToolCatalogController {

    private final ToolCatalogService toolCatalogService;

    public ToolCatalogController(ToolCatalogService toolCatalogService) {
        this.toolCatalogService = toolCatalogService;
    }

    /** 全量工具展示名目录（toolName → label/desc）。 */
    @SaCheckPermission("event:view")
    @GetMapping("/catalog")
    public R<List<ToolCatalogItem>> catalog() {
        return R.ok(toolCatalogService.catalog());
    }
}
