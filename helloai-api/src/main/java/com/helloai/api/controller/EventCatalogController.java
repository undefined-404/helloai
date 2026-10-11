package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.common.base.R;
import com.helloai.core.event.EventCatalogItem;
import com.helloai.core.event.EventCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 事件码目录接口（REF-6.8，事件码服务端单一来源）。
 *
 * <p>前端事件时间线（sequenceFlow / eventMeta）从本端点拉取 code → label/desc 字典，
 * 不再保留硬编码副本（与技能目录 REF-1.2c 同一范式）。</p>
 *
 * <p>权限复用既有 {@code event:view}（已绑 ADMIN / NORMAL_USER / GUEST，纯只读展示元数据），
 * 不新增权限面、不建菜单（V103 同款红线：目录是弹窗/渲染数据源，非独立页面）。</p>
 */
@RestController
@RequestMapping("/api/events")
public class EventCatalogController {

    private final EventCatalogService eventCatalogService;

    public EventCatalogController(EventCatalogService eventCatalogService) {
        this.eventCatalogService = eventCatalogService;
    }

    /** 全量用户可见事件码目录（code → label/desc）。 */
    @SaCheckPermission("event:view")
    @GetMapping("/catalog")
    public R<List<EventCatalogItem>> catalog() {
        return R.ok(eventCatalogService.catalog());
    }
}