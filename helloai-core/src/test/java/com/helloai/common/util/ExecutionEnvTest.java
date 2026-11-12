package com.helloai.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ExecutionEnv} 的解析 / 判别 / 渲染。
 *
 * <p>这四类断言各有明确的失效后果，不是凑覆盖率：
 * <ul>
 *   <li><b>未声明 vs 声明不需要</b>：混成一件会让提示词把「明确的边界」当成「缺口」要求申报，
 *       或反过来把真缺口当成已知事实；</li>
 *   <li><b>四态判别</b>：判错就把本地路径渲染成「环境内已有目录」，执行者会去沙箱根目录找一个
 *       不存在的文件夹——这比不写更误导；</li>
 *   <li><b>脏数据不抛</b>：环境是附加价值，解析失败不该让整次拆解失败；</li>
 *   <li><b>就绪三态</b>：{@code ready} 缺失不能渲染成「已就绪」。</li>
 * </ul>
 */
@DisplayName("执行环境声明 ExecutionEnv")
class ExecutionEnvTest {

    @Nested
    @DisplayName("声明与否")
    class Declared {

        @Test
        @DisplayName("缺失 / 空对象 / 类型不对 ⇒ 未声明，渲染为空串（由调用方兜底标记）")
        void absentWhenMissing() {
            assertThat(ExecutionEnv.parse(null).declared()).isFalse();
            assertThat(ExecutionEnv.parse(Map.of()).declared()).isFalse();
            assertThat(ExecutionEnv.parse("E:/yhzx/1027/helloai").declared()).isFalse();
            assertThat(ExecutionEnv.parse(42).declared()).isFalse();

            assertThat(ExecutionEnv.parse(null).render()).isEmpty();
            assertThat(ExecutionEnv.parse(Map.of()).render()).isEmpty();
        }

        @Test
        @DisplayName("声明了「不需要代码仓库」≠ 未声明：必须渲染成明确边界，不能回落成缺口")
        void declaredWithoutRepoIsNotAbsent() {
            ExecutionEnv env = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_REPO, ""));
            assertThat(env.declared()).isTrue();
            assertThat(env.repo().kind()).isEqualTo(ExecutionEnv.RepoKind.NONE);
            assertThat(env.render())
                    .contains("不需要")
                    .as("这是明确的边界，不是缺口").doesNotContain("未声明");
        }

        @Test
        @DisplayName("只给了运行时也算已声明（不必三项齐全）")
        void declaredWithRuntimeOnly() {
            ExecutionEnv env = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_RUNTIME, List.of("jdk17")));
            assertThat(env.declared()).isTrue();
            assertThat(env.render()).contains("运行时").contains("jdk17");
        }
    }

    @Nested
    @DisplayName("仓库四态判别（判别顺序与参考实现一致）")
    class RepoClassification {

        @Test
        @DisplayName("空 ⇒ NONE；不含分隔符 ⇒ 环境内已有；URL/git ⇒ 远程；其余 ⇒ 本地")
        void classifyFourStates() {
            assertThat(ExecutionEnv.classify(null)).isEqualTo(ExecutionEnv.RepoKind.NONE);
            assertThat(ExecutionEnv.classify("  ")).isEqualTo(ExecutionEnv.RepoKind.NONE);

            assertThat(ExecutionEnv.classify("helloai"))
                    .as("只给目录名 = 执行环境内已存在的仓库").isEqualTo(ExecutionEnv.RepoKind.PREEXISTING);

            assertThat(ExecutionEnv.classify("https://github.com/SWE-agent/SWE-agent"))
                    .isEqualTo(ExecutionEnv.RepoKind.REMOTE);
            assertThat(ExecutionEnv.classify("https://gitee.com/undefined_404/helloai"))
                    .as("参考实现只认 GitHub，我们泛化到任何 URL（无凭据注入链）")
                    .isEqualTo(ExecutionEnv.RepoKind.REMOTE);
            assertThat(ExecutionEnv.classify("git@github.com:a/b.git"))
                    .isEqualTo(ExecutionEnv.RepoKind.REMOTE);

            assertThat(ExecutionEnv.classify("E:/yhzx/1027/helloai"))
                    .as("Windows 正斜杠路径").isEqualTo(ExecutionEnv.RepoKind.LOCAL);
            assertThat(ExecutionEnv.classify("E:\\yhzx\\1027\\helloai"))
                    .as("Windows 反斜杠路径（参考实现只判 / 会误判成环境内已有）")
                    .isEqualTo(ExecutionEnv.RepoKind.LOCAL);
            assertThat(ExecutionEnv.classify("/home/user/repo"))
                    .isEqualTo(ExecutionEnv.RepoKind.LOCAL);
        }

        @Test
        @DisplayName("显式 kind 优先于判别；非法 kind 回落判别而不是丢弃声明")
        void explicitKindWins() {
            ExecutionEnv explicit = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_REPO,
                    Map.of("kind", "local", "value", "helloai", ExecutionEnv.KEY_REPO_BASE_REF, "dev")));
            assertThat(explicit.repo().kind()).isEqualTo(ExecutionEnv.RepoKind.LOCAL);
            assertThat(explicit.repo().baseRef()).isEqualTo("dev");

            ExecutionEnv illegal = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_REPO,
                    Map.of("kind", "weird", "value", "E:/a/b")));
            assertThat(illegal.repo().kind()).isEqualTo(ExecutionEnv.RepoKind.LOCAL);
        }

        @Test
        @DisplayName("基线缺省 HEAD；声明了形态却没给值 ⇒ 降级 NONE（不渲染半截话）")
        void baseRefAndEmptyValue() {
            ExecutionEnv env = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_REPO,
                    Map.of("kind", "remote")));
            assertThat(env.repo().kind()).isEqualTo(ExecutionEnv.RepoKind.NONE);
            assertThat(env.repo().baseRef()).isEqualTo("HEAD");
        }

        @Test
        @DisplayName("渲染带具体值与基线（提示词要求照抄，所以值必须原样出现）")
        void renderCarriesValues() {
            ExecutionEnv env = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_REPO,
                    Map.of("kind", "local", "value", "E:/yhzx/1027/helloai", "baseRef", "main")));
            assertThat(env.render())
                    .contains("E:/yhzx/1027/helloai")
                    .contains("main");
        }
    }

    @Nested
    @DisplayName("运行时与数据源")
    class RuntimeAndDatasource {

        @Test
        @DisplayName("运行时可写数组，也可写逗号/顿号分隔的单串（人手填的常见形态）")
        void runtimeAcceptsBothForms() {
            assertThat(ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_RUNTIME, List.of("jdk17", "bash")))
                    .runtime()).containsExactly("jdk17", "bash");
            assertThat(ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_RUNTIME, "jdk17, python3、node20"))
                    .runtime()).containsExactly("jdk17", "python3", "node20");
        }

        @Test
        @DisplayName("数据源就绪三态：true 标已就绪、false 标未就绪、缺失不标任何结论")
        void datasourceReadyTriState() {
            LinkedHashMap<String, Object> ds = new LinkedHashMap<>();
            ds.put(ExecutionEnv.KEY_DATASOURCES, List.of(
                    Map.of("type", "postgresql", "url", "localhost:15432/helloai", "ready", true),
                    Map.of("type", "redis", "url", "localhost:26379", "ready", false),
                    Map.of("type", "mysql", "url", "localhost:3306/x")));
            String rendered = ExecutionEnv.parse(ds).render();

            assertThat(rendered).contains("postgresql — localhost:15432/helloai（已就绪）");
            assertThat(rendered).contains("redis — localhost:26379（**未就绪**）");
            assertThat(rendered)
                    .as("未说明就绪状态 ≠ 已就绪").endsWith("mysql — localhost:3306/x")
                    .doesNotContain("mysql — localhost:3306/x（");
        }

        @Test
        @DisplayName("数据源简写：DSN 串取 scheme 当类型")
        void datasourceDsnShorthand() {
            ExecutionEnv env = ExecutionEnv.parse(Map.of(ExecutionEnv.KEY_DATASOURCES,
                    List.of("postgresql://localhost:15432/helloai")));
            assertThat(env.datasources()).hasSize(1);
            assertThat(env.datasources().get(0).type()).isEqualTo("postgresql");
            assertThat(env.datasources().get(0).ready()).isNull();
        }
    }

    @Nested
    @DisplayName("脏数据防御")
    class DirtyData {

        @Test
        @DisplayName("各字段类型错位一律降级，不抛异常（环境不该有否决权）")
        void neverThrows() {
            LinkedHashMap<String, Object> dirty = new LinkedHashMap<>();
            dirty.put(ExecutionEnv.KEY_REPO, 42);
            dirty.put(ExecutionEnv.KEY_RUNTIME, Map.of("a", "b"));
            dirty.put(ExecutionEnv.KEY_DATASOURCES, "not-a-list");

            assertThatCode(() -> ExecutionEnv.parse(dirty)).doesNotThrowAnyException();
            ExecutionEnv env = ExecutionEnv.parse(dirty);
            assertThat(env.declared()).isTrue();
            assertThat(env.repo().kind()).isEqualTo(ExecutionEnv.RepoKind.NONE);
            assertThat(env.runtime()).isEmpty();
            assertThat(env.datasources()).isEmpty();
        }
    }
}
