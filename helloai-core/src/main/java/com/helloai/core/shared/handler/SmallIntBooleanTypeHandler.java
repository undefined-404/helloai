package com.helloai.core.shared.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SMALLINT(0/1) ↔ Boolean TypeHandler（用于 pending_clarify_confirm 等
 * 按代码规范 9.3 使用 SMALLINT 而非 BOOLEAN 的布尔列）：
 * write 侧 Boolean → ps.setInt(0/1)，read 侧 smallint → Boolean。
 *
 * <p>直接使用 MyBatis 内置 BooleanTypeHandler 时，PG 驱动会把 true/false 作为
 * boolean 字面量插入 smallint 列，报
 * {@code column "..." is of type smallint but expression is of type boolean}。</p>
 *
 * <p>不注册全局类型映射（无 @MappedTypes/@MappedJdbcTypes），仅经
 * {@code @TableField(typeHandler = SmallIntBooleanTypeHandler.class)} 显式指定，
 * 避免影响其他布尔列（如 BOOLEAN 类型的 web_search_enabled）。</p>
 */
public class SmallIntBooleanTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter ? 1 : 0);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return readSmallInt(rs.getObject(columnName));
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return readSmallInt(rs.getObject(columnIndex));
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return readSmallInt(cs.getObject(columnIndex));
    }

    /** smallint 读取：NULL → null，1 → true，其余（含 0）→ false。 */
    private Boolean readSmallInt(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue() == 1;
    }
}
