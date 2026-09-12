package com.helloai.api.dto.system;

import com.helloai.core.system.entity.SysDepart;
import lombok.Data;

import java.util.List;

/**
 * 部门树节点投影（API 层不直接暴露 DB 实体，BASE-3.2）。
 */
@Data
public class SysDepartVO {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private String status;
    private String description;
    private List<SysDepartVO> children;

    public static SysDepartVO from(SysDepart d) {
        SysDepartVO vo = new SysDepartVO();
        vo.setId(d.getId());
        vo.setParentId(d.getParentId());
        vo.setName(d.getName());
        vo.setSort(d.getSort());
        vo.setStatus(d.getStatus());
        vo.setDescription(d.getDescription());
        if (d.getChildren() != null && !d.getChildren().isEmpty()) {
            vo.setChildren(d.getChildren().stream().map(SysDepartVO::from).toList());
        }
        return vo;
    }

    public static List<SysDepartVO> fromList(List<SysDepart> list) {
        return list.stream().map(SysDepartVO::from).toList();
    }
}
