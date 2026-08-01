package com.helloai.core.shared.handler;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * PostgreSQL JSONB TypeHandler — 将 Java Map/Object 序列化为正确的 JSONB 参数。
 *
 * <p>MyBatis-Plus 内置 {@link JacksonTypeHandler} 在 write 侧调用
 * {@link PreparedStatement#setString(int, String)}，PostgreSQL 拒绝将
 * VARCHAR 隐式转换为 JSONB（ERROR: column is of type jsonb but expression
 * is of type character varying）。本 Handler 覆写 write 侧，使用
 * {@link PGobject}(type="jsonb") 包装 JSON 值，确保 JDBC 驱动将参数
 * 标记为 jsonb 类型。</p>
 */
public class PgJsonbTypeHandler extends JacksonTypeHandler {

    public PgJsonbTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter == null) {
            ps.setNull(i, jdbcType.TYPE_CODE);
            return;
        }
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(toJson(parameter));
        ps.setObject(i, pgObject);
    }
}
