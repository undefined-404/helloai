package com.helloai.api.controller;

import com.helloai.api.dto.admin.PromptTemplateResponse;
import com.helloai.common.base.R;
import com.helloai.core.entity.PromptTemplate;
import com.helloai.core.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
public class AdminPromptController {

    private final PromptTemplateService promptTemplateService;

    /**
     * 获取模板列表（可按角色、分类筛选）
     */
    @GetMapping
    public R<List<PromptTemplateResponse>> list(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "category", required = false) String category) {
        if (category != null && !category.isBlank()) {
            return R.ok(promptTemplateService.getByCategory(category).stream().map(this::toResponse).toList());
        }
        if (role != null && !role.isBlank()) {
            return R.ok(promptTemplateService.getByRole(role).stream().map(this::toResponse).toList());
        }
        return R.ok(promptTemplateService.list().stream().map(this::toResponse).toList());
    }

    /**
     * 获取单个模板
     */
    @GetMapping("/{id}")
    public R<PromptTemplateResponse> getById(@PathVariable Long id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) return R.fail("模板不存在");
        return R.ok(toResponse(template));
    }

    /**
     * 创建模板
     */
    @PostMapping
    public R<PromptTemplateResponse> create(@RequestBody PromptTemplate template) {
        return R.ok(toResponse(promptTemplateService.create(template)));
    }

    /**
     * 更新模板
     */
    @PutMapping("/{id}")
    public R<PromptTemplateResponse> update(@PathVariable Long id, @RequestBody PromptTemplate template) {
        template.setId(id);
        return R.ok(toResponse(promptTemplateService.update(template)));
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        promptTemplateService.removeById(id);
        return R.ok();
    }

    /**
     * 获取角色的默认模板
     */
    @GetMapping("/default")
    public R<PromptTemplateResponse> getDefault(@RequestParam("role") String role) {
        PromptTemplate template = promptTemplateService.getDefaultByRole(role);
        if (template == null) return R.fail("未找到角色 " + role + " 的默认模板");
        return R.ok(toResponse(template));
    }

    /**
     * 组合提示词（默认模板 + Agent 特定内容）
     */
    @PostMapping("/compose")
    public R<Map<String, String>> compose(@RequestBody Map<String, String> body) {
        String role = body.get("role");
        String agentContent = body.getOrDefault("agentContent", "");
        String composed = promptTemplateService.compose(role, agentContent);
        return R.ok(Map.of("content", composed));
    }

    private PromptTemplateResponse toResponse(PromptTemplate template) {
        PromptTemplateResponse response = new PromptTemplateResponse();
        response.setId(template.getId());
        response.setRole(template.getRole());
        response.setCategory(template.getCategory());
        response.setSlug(template.getSlug());
        response.setName(template.getName());
        response.setDescription(template.getDescription());
        response.setContent(template.getContent());
        response.setIsDefault(template.getIsDefault());
        response.setIsExample(template.getIsExample());
        response.setVersion(template.getVersion());
        response.setRemark(template.getRemark());
        response.setCreateTime(template.getCreateTime());
        response.setUpdateTime(template.getUpdateTime());
        return response;
    }
}
