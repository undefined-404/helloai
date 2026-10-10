package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 「数据库当前 schema 版本」读取器（REF-2.3）—— 写入 manifest 的 {@code flywayMaxVersion}。
 *
 * <p><b>与 {@link MigrationVersionResolver} 的区别，二者不可混用</b>：
 * 本类读的是**数据库此刻的状态**（{@code flyway_schema_history}），写进备份清单；
 * 而 {@link MigrationVersionResolver} 读的是**当前代码认识多新**（classpath 的迁移文件），
 * 是恢复侧门② 的对照物。写错任一个，门② 都会恒不触发、形同虚设。</p>
 *
 * <p><b>⚠️ 为什么不用 SQL 的 {@code max(version)}</b>：{@code flyway_schema_history.version}
 * 是 **VARCHAR**，{@code max()} 走**字典序** —— {@code '99' > '105'}（因 {@code '9' > '1'}）。
 * 实测在 schema 已到 V105 的库上得到 {@code "99"}，会让门② 误判"备份比代码基线更旧"，
 * 从而**放行本该拒绝的更新备份**。故取回全部版本、在 Java 侧按**前导整数**求最大。
 * （回归用例见 {@code DbSchemaVersionReaderTest}。）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbSchemaVersionReader {

    private final DataSource dataSource;

    /**
     * 库内已成功应用的迁移最高版本（前导整数形式，如 {@code "105"}）；无记录返回 null。
     *
     * @throws BizException 读取失败（判不出就不放行：这个值缺失会让门② 拒绝恢复，方向安全）
     */
    public String dbMaxMigrationVersion() {
        List<String> versions = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = true")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new BizException("读取数据库 schema 版本失败: " + e.getMessage());
        }
        int max = maxLeadingInteger(versions);
        return max < 0 ? null : String.valueOf(max);
    }

    /**
     * 取一组版本号里**数值最大**的前导整数；都解析不出返回 -1。
     *
     * <p>独立成 static 是为了可测 —— 字典序陷阱（{@code "99" vs "105"}）必须由
     * 回归用例钉死，而不能靠"上线看一眼"。</p>
     */
    static int maxLeadingInteger(Collection<String> versions) {
        int max = -1;
        if (versions == null) {
            return max;
        }
        for (String v : versions) {
            int n = leadingInteger(v);
            if (n > max) {
                max = n;
            }
        }
        return max;
    }

    /** 版本号的前导整数（{@code "105"} → 105、{@code "12.3"} → 12）；无前导数字返回 -1。 */
    static int leadingInteger(String version) {
        if (version == null) {
            return -1;
        }
        String s = version.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
