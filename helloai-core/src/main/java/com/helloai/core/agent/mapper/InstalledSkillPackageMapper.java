package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.InstalledSkillPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 已安装技能包 Mapper（REF-1.6）。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD；查询一律由服务层用
 * {@code LambdaQueryWrapper} 表达（ACTIVE 选版 / 按 name 列历史版本 / 内置同名判重），
 * 不另建 XML——JSONB 字段由实体上的 {@code PgJsonbTypeHandler} 处理
 * （{@code autoResultMap = true} 已开启）。</p>
 *
 * <p>唯一的例外是 {@link #selectOriginZipRefIncludingDeleted()}：它需要绕过逻辑删除过滤，
 * 而 {@code LambdaQueryWrapper} 做不到这一点。</p>
 */
@Mapper
public interface InstalledSkillPackageMapper extends BaseMapper<InstalledSkillPackage> {

    /**
     * 只读取技能包原始 zip 的存储地址，<b>含逻辑删除行</b>，供存储对账构造「被引用集合」。
     *
     * <p>两个刻意的取舍：</p>
     * <ul>
     *   <li><b>不带 {@code deleted = 0}</b>——{@link BaseMapper} 内置方法会按逻辑删除列自动追加
     *       条件，只有手写 SQL 才关得掉。对账口径是「任何一行指向即算被引用」（保守口径：
     *       宁可漏判孤儿，也不误删），与 {@code AttachmentMapper.selectAllIncludingDeleted()} 同款。</li>
     *   <li><b>只选一列</b>——对账只要地址，顺带避免把 {@code body} 等大字段拉进内存。</li>
     * </ul>
     *
     * <p>返回的实体只有 {@code originZipUrl} 有值，其余字段为 {@code null}，不要当作查询结果复用。</p>
     */
    @Select("SELECT origin_zip_url FROM skill_package "
            + "WHERE origin_zip_url IS NOT NULL AND origin_zip_url <> ''")
    List<InstalledSkillPackage> selectOriginZipRefIncludingDeleted();
}
