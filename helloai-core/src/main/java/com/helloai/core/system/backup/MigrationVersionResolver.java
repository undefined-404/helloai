package com.helloai.core.system.backup;

import com.helloai.common.base.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「当前代码内已知的最高 Flyway 迁移号」解析器（REF-2.3b 门② 的判据来源）。
 *
 * <p><b>为什么单独成类</b>：闸门需要的是"**运行时**代码认识多新的 schema"，
 * 而答案藏在 classpath 的 {@code db/migration/V*.sql} 里。若让闸门自己扫，
 * 一是把"找版本"与"做判定"两种职责揉在一起，二是**单元测试无法替换数据源**
 * （迁移文件在 {@code helloai-start} 模块，{@code helloai-core} 的测试 classpath 里没有，
 * 会让所有闸门用例都卡在门②，掩住门①③的验证）。抽出来后测试注入替身即可。</p>
 *
 * <p><b>为什么不用运行库的 {@code flyway_schema_history}</b>：那是**数据库的状态**，
 * 不是"代码认识多少"。门②要挡的是「备份来自更新的平台版本」—— 判据必须是代码基线。</p>
 */
@Slf4j
@Component
public class MigrationVersionResolver {

    /** {@code V<数字>__xxx.sql}。 */
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.+\\.[Ss][Qq][Ll]$");

    private static final String LOCATION = "classpath*:db/migration/*.sql";

    /** 记忆化：classpath 内容在运行期不变（同 SkillPackageCatalog 的无 TTL 口径）。 */
    private volatile Integer cached;

    /**
     * 当前代码内已知的最高迁移号。
     *
     * @return 最高 V 号；扫不到任何迁移时抛异常（**不返回 0** —— 0 会让门②对任何备份都放行，
     *         把"扫不到"伪装成"基线很低"）
     */
    public int codeMaxMigrationVersion() {
        Integer c = cached;
        if (c == null) {
            synchronized (this) {
                c = cached;
                if (c == null) {
                    c = scan();
                    cached = c;
                }
            }
        }
        return c;
    }

    private int scan() {
        int max = -1;
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name == null) {
                    continue;
                }
                Matcher m = MIGRATION_NAME.matcher(name);
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        } catch (Exception e) {
            throw new BizException("迁移目录扫描失败: " + e.getMessage());
        }
        if (max <= 0) {
            throw new BizException("未扫描到任何 Flyway 迁移（" + LOCATION
                    + "）—— 无法判定备份的 schema 是否新于当前代码");
        }
        log.info("当前代码内已知最高迁移号: V{}", max);
        return max;
    }
}
