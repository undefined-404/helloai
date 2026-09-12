package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.system.SysDepartSaveRequest;
import com.helloai.api.dto.system.SysDepartVO;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.SysDepart;
import com.helloai.core.system.service.SysDepartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理接口（RBAC 组织架构，BASE-3.2）。
 *
 * <p>位于 {@code /api/admin/**} 下，由 AdminOnlyInterceptor 强制 admin 身份；
 * 动作级权限码鉴权：depart:view / depart:add / depart:edit / depart:delete。
 * 删除守卫（子部门 / 用户关联）在服务层。</p>
 */
@RestController
@RequestMapping("/api/admin/departs")
@RequiredArgsConstructor
public class SysDepartController {

    private final SysDepartService sysDepartService;

    /** 全量部门树（parent 挂接，每级按 sort 升序） */
    @SaCheckPermission("depart:view")
    @GetMapping("/tree")
    public R<List<SysDepartVO>> tree() {
        return R.ok(SysDepartVO.fromList(sysDepartService.listTree()));
    }

    @SaCheckPermission("depart:add")
    @PostMapping
    public R<SysDepartVO> create(@Valid @RequestBody SysDepartSaveRequest req) {
        return R.ok(SysDepartVO.from(sysDepartService.createDepart(toEntity(req))));
    }

    @SaCheckPermission("depart:edit")
    @PutMapping("/{id}")
    public R<SysDepartVO> update(@PathVariable("id") Long id,
                                 @Valid @RequestBody SysDepartSaveRequest req) {
        return R.ok(SysDepartVO.from(sysDepartService.updateDepart(id, toEntity(req))));
    }

    @SaCheckPermission("depart:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysDepartService.deleteDepart(id);
        return R.ok();
    }

    private SysDepart toEntity(SysDepartSaveRequest req) {
        SysDepart d = new SysDepart();
        d.setName(req.getName());
        d.setParentId(req.getParentId());
        d.setSort(req.getSort());
        d.setStatus(req.getStatus());
        d.setDescription(req.getDescription());
        return d;
    }
}
