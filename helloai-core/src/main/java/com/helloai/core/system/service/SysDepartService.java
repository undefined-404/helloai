package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysDepart;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 系统部门服务（组织架构树，RBAC，BASE-3.2）。
 *
 * <p>部门树 CRUD + 用户-部门分配。删除守卫：存在子部门或被用户关联时拒绝。
 * 批量查询方法（listXxxByUserIds）走 IN 查询，避免 N+1。</p>
 */
public interface SysDepartService extends IService<SysDepart> {

    /** 全量部门树（parent 挂接，每级按 sort 升序） */
    List<SysDepart> listTree();

    SysDepart createDepart(SysDepart req);

    SysDepart updateDepart(Long id, SysDepart req);

    /**
     * 删除部门。
     *
     * @throws BizException 存在子部门或已被用户关联时拒绝
     */
    void deleteDepart(Long id);

    /** 查询用户已关联部门 id 列表 */
    List<Long> listDepartIdsByUser(Long userId);

    /** 分配用户部门（先删后插，全量覆盖） */
    void assignUserDeparts(Long userId, List<Long> departIds);

    /** 批量查询用户部门 id（供用户列表回显分配；空入参返回空 Map） */
    Map<Long, List<Long>> listDepartIdsByUserIds(Collection<Long> userIds);

    /** 批量查询用户部门名称（供用户列表展示；空入参返回空 Map） */
    Map<Long, List<String>> listDepartNamesByUserIds(Collection<Long> userIds);

    /** 按部门 id 集合查询关联用户 id（数据权限过滤用，BASE-3.3；空入参返回空列表） */
    List<Long> listUserIdsByDepartIds(Collection<Long> departIds);

    /** 部门及其全部后代部门 id（数据权限 DEPT_AND_CHILD 用；含自身） */
    List<Long> listDepartAndChildIds(Long departId);
}
