package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.system.service.EnumCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 枚举字典端点（2026-10-18）：把 {@code sub_task.approved} / {@code coding} 这类后端码值
 * 统一翻译成人话，供前端渲染。
 *
 * <p>与 {@code /api/events/catalog}、{@code /api/tools/catalog} 同范式：只读目录、
 * 不建菜单、不新增权限面。前端懒加载 + 本地缓存（失败静默回退原码）。</p>
 */
@RestController
@RequestMapping("/api/enums")
@RequiredArgsConstructor
public class EnumCatalogController {

    private final EnumCatalogService enumCatalogService;

    /** 全量枚举字典：{@code {分组名: [{code,label,desc}]}}。 */
    @GetMapping("/catalog")
    public R<Map<String, List<EnumCatalogService.EnumItem>>> catalog() {
        return R.ok(enumCatalogService.catalog());
    }
}
