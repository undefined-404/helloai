package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysDepart;
import com.helloai.core.system.entity.SysUserDepart;
import com.helloai.core.system.mapper.SysDepartMapper;
import com.helloai.core.system.mapper.SysUserDepartMapper;
import com.helloai.core.system.service.SysDepartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统部门服务实现（组织架构树，RBAC，BASE-3.2）。
 *
 * <p>删除守卫：存在子部门 / 已被用户关联时拒绝（避免破坏组织树与用户归属）。
 * 批量查询走 IN + Map 分组，避免 N+1（CODE_STYLE §18/§19）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDepartServiceImpl extends ServiceImpl<SysDepartMapper, SysDepart> implements SysDepartService {

    private final SysUserDepartMapper sysUserDepartMapper;

    @Override
    public List<SysDepart> listTree() {
        List<SysDepart> all = list(Wrappers.<SysDepart>lambdaQuery().orderByAsc(SysDepart::getSort));
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, SysDepart> byId = all.stream()
                .collect(Collectors.toMap(SysDepart::getId, d -> d));
        List<SysDepart> roots = new ArrayList<>();
        for (SysDepart d : all) {
            if (d.getParentId() == null) {
                roots.add(d);
                continue;
            }
            SysDepart parent = byId.get(d.getParentId());
            if (parent != null) {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(d);
            } else {
                // 父节点缺失：兜底平铺，避免孤儿行丢失
                roots.add(d);
            }
        }
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDepart createDepart(SysDepart req) {
        validateParent(req.getParentId(), null);
        SysDepart d = new SysDepart();
        d.setParentId(req.getParentId());
        d.setName(req.getName());
        d.setSort(req.getSort() != null ? req.getSort() : 0);
        d.setStatus(req.getStatus() != null ? req.getStatus() : "ACTIVE");
        d.setDescription(req.getDescription());
        save(d);
        log.info("部门创建: name={}, parentId={}, id={}", d.getName(), d.getParentId(), d.getId());
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDepart updateDepart(Long id, SysDepart req) {
        SysDepart d = getById(id);
        if (d == null) {
            throw new BizException("部门不存在: " + id);
        }
        if (req.getParentId() != null) {
            if (req.getParentId().equals(id)) {
                throw new BizException("父部门不能是自己");
            }
            validateParent(req.getParentId(), id);
            d.setParentId(req.getParentId());
        }
        if (req.getName() != null) d.setName(req.getName());
        if (req.getSort() != null) d.setSort(req.getSort());
        if (req.getStatus() != null) d.setStatus(req.getStatus());
        if (req.getDescription() != null) d.setDescription(req.getDescription());
        updateById(d);
        log.info("部门更新: id={}, name={}", id, d.getName());
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDepart(Long id) {
        SysDepart d = getById(id);
        if (d == null) {
            return;
        }
        Long childCount = baseMapper.selectCount(Wrappers.<SysDepart>lambdaQuery()
                .eq(SysDepart::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("存在子部门，请先删除子部门");
        }
        Long userRef = sysUserDepartMapper.selectCount(Wrappers.<SysUserDepart>lambdaQuery()
                .eq(SysUserDepart::getDepartId, id));
        if (userRef != null && userRef > 0) {
            throw new BizException("该部门已被用户关联，请先解除关联");
        }
        removeById(id);
        log.info("部门删除: id={}, name={}", id, d.getName());
    }

    @Override
    public List<Long> listDepartIdsByUser(Long userId) {
        return sysUserDepartMapper.selectList(Wrappers.<SysUserDepart>lambdaQuery()
                        .eq(SysUserDepart::getUserId, userId))
                .stream()
                .map(SysUserDepart::getDepartId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignUserDeparts(Long userId, List<Long> departIds) {
        sysUserDepartMapper.delete(Wrappers.<SysUserDepart>lambdaQuery()
                .eq(SysUserDepart::getUserId, userId));
        if (departIds != null) {
            for (Long departId : departIds.stream().distinct().toList()) {
                SysUserDepart ud = new SysUserDepart();
                ud.setUserId(userId);
                ud.setDepartId(departId);
                sysUserDepartMapper.insert(ud);
            }
        }
        log.info("用户部门分配: userId={}, departCount={}", userId, departIds == null ? 0 : departIds.size());
    }

    @Override
    public Map<Long, List<Long>> listDepartIdsByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return sysUserDepartMapper.selectList(Wrappers.<SysUserDepart>lambdaQuery()
                        .in(SysUserDepart::getUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(SysUserDepart::getUserId,
                        Collectors.mapping(SysUserDepart::getDepartId, Collectors.toList())));
    }

    @Override
    public Map<Long, List<String>> listDepartNamesByUserIds(Collection<Long> userIds) {
        Map<Long, List<Long>> idsByUser = listDepartIdsByUserIds(userIds);
        if (idsByUser.isEmpty()) {
            return Map.of();
        }
        List<Long> allDepartIds = idsByUser.values().stream().flatMap(List::stream).distinct().toList();
        if (allDepartIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = listByIds(allDepartIds).stream()
                .collect(Collectors.toMap(SysDepart::getId, SysDepart::getName));
        Map<Long, List<String>> result = new HashMap<>();
        idsByUser.forEach((userId, departIds) -> result.put(userId, departIds.stream()
                .map(nameById::get)
                .filter(java.util.Objects::nonNull)
                .toList()));
        return result;
    }

    @Override
    public List<Long> listUserIdsByDepartIds(Collection<Long> departIds) {
        if (departIds == null || departIds.isEmpty()) {
            return List.of();
        }
        return sysUserDepartMapper.selectList(Wrappers.<SysUserDepart>lambdaQuery()
                        .in(SysUserDepart::getDepartId, departIds))
                .stream()
                .map(SysUserDepart::getUserId)
                .distinct()
                .toList();
    }

    @Override
    public List<Long> listDepartAndChildIds(Long departId) {
        if (departId == null) {
            return List.of();
        }
        List<SysDepart> all = list(Wrappers.<SysDepart>lambdaQuery());
        Map<Long, List<Long>> childrenByParent = all.stream()
                .filter(d -> d.getParentId() != null)
                .collect(Collectors.groupingBy(SysDepart::getParentId,
                        Collectors.mapping(SysDepart::getId, Collectors.toList())));
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(departId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            if (result.contains(cur)) {
                continue;
            }
            result.add(cur);
            queue.addAll(childrenByParent.getOrDefault(cur, List.of()));
        }
        return result;
    }

    /** 父部门校验：存在 + 非自身（编辑时） */
    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null) {
            return;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new BizException("父部门不能是自己");
        }
        if (getById(parentId) == null) {
            throw new BizException("父部门不存在: " + parentId);
        }
    }
}
