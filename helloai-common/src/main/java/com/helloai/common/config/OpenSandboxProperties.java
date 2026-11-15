package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenSandbox 沙箱集成配置（S-3 / S-5，沙箱集成计划 §4.2/§4.7）。
 *
 * <p>对应配置前缀 {@code helloai.sandbox.opensandbox}。新能力默认关闭
 * （{@code enabled=false}，对齐 REF-6.13：通过注入启用）；开启后才由
 * {@code OpenSandboxSandboxProvider} 消费。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.sandbox.opensandbox")
public class OpenSandboxProperties {

    /** 是否启用 OpenSandbox 沙箱提供方（默认 false，REF-6.13 新能力默认关闭）。 */
    private boolean enabled = false;

    /** OpenSandbox server 地址（如 http://localhost:8090，dev；compose 内为服务名地址）。 */
    private String url = "http://localhost:8090";

    /**
     * app → OpenSandbox server 的认证 api_key（OPEN-SANDBOX-API-KEY 头）。
     *
     * <p>与平台凭据体系【分离】（凭据红线 §4.5）：api_key 存配置 / 环境变量，
     * 不进 {@code credential_vault}；沙箱容器绝不注入平台凭据 env。</p>
     */
    private String apiKey;

    /** 声明式沙箱规格（纯声明，不承载提供方 API 调用参数；见 {@link SandboxSpec}）。 */
    private Spec spec = new Spec();

    /**
     * 命名规格表（`SKX-5`）：{@code helloai.sandbox.opensandbox.spec-presets.<name>.*}。
     *
     * <p>同一子任务可能先跑 python 验算再跑 node 出图，镜像/资源需求不同，全局单例 {@link #spec} 只能表达一种，
     * 故引入命名表；{@code default} 及表为空时所有调用回落 {@link #spec}（既有配置零迁移）。</p>
     */
    private Map<String, Spec> specPresets = new LinkedHashMap<>();

    /**
     * 技能执行使用的命名规格（`SKX-8`，默认 {@code node}）。
     *
     * <p>技能镜像由「技能需要什么运行时」决定（archify 要 node），与验算命令镜像是两件事，故独立成键，
     * 换技能只改配置不改代码。指向 {@link #specPresets} 键名；缺失时回落全局 {@link #spec} 并打 warn。</p>
     */
    private String skillSpecPreset = "node";

    /**
     * 复用沙箱作用域（`SL-1`，默认 {@code task}）。
     *
     * <p>{@code task} = 任务级共享：同任务全部子任务命中同一「默认规格」沙箱（槽键 {@code task:{taskId}}），
     * 上游产物跨子任务可见、返工可续作；任务终态（{@code DONE}/{@code CANCELLED}/删除）经事件释放全部沙箱（`SL-2`）。
     * {@code subtask} = 子任务级（旧语义，仅回滚/排障）：槽键 {@code scope:{subTaskId}}，turn 结束释放。
     * 会话内技能执行不受影响：{@code scopeId}（conversationId）经 {@code SandboxScopes} 解析未命中即回落直接作用域。</p>
     */
    private String scope = "task";

    /**
     * 是否启用同作用域复用沙箱（`ADR-005`，默认 true）。
     *
     * <p>对齐 deepseek-harness `dsh-tool-bash-persistent`：同作用域内多次 {@code internal_run_in_sandbox}
     * 复用同一沙箱，写入文件跨调用保留，模型可对上一轮产物增量编辑（{@code sed -i}/python 定点改）而非整篇重写。
     * 置 false ⇒ 回落「每次新建 + 无条件 kill」一次性语义（排障 / 需绝对隔离时用）。</p>
     */
    private boolean reuseEnabled = true;

    /**
     * 空闲上限（秒，默认 900 = 15min）；只用于「空闲回收器」判活（`SL-7`）。
     *
     * <p>不再作为 {@code renew} 目标——{@code renew} 是绝对过期时刻，拿 900s 续期会把容器寿命压在 15 分钟；
     * 容器能活多久改由 {@link #keepAliveSeconds} 决定。任务级槽（{@code task:{taskId}}）以 {@link #keepAliveSeconds}
     * 为回收阈值（任务可能长时间等人工审查/下游，空闲 15 分钟就销毁会丢整个工作区）；
     * 会话技能等 {@code scope:{id}} 槽无终态释放点，按本值回收。</p>
     */
    private long idleTtlSeconds = 900L;

    /**
     * 容器保活目标（秒，默认 28800 = 8h）；{@code renew} 的绝对过期时刻（`SL-7`）。
     *
     * <p>与 {@link #idleTtlSeconds} 拆开：{@code renew} 只能设绝对过期时刻，写小缩寿命、写大又需回收器兜底否则泄漏；
     * 拆开后 {@code renew} 负责「活到 8h」、{@link #idleTtlSeconds} 负责「多久算废弃」，互不干扰。
     * 创建时 {@code spec.timeout-seconds} 只是初始预算（服务端允许 {@code renew} 超出创建值），无需调到 8h。</p>
     */
    private long keepAliveSeconds = 28_800L;

    /**
     * `/workspace` 卷化开关（`SL-10`，默认 false）。
     *
     * <p>审核隔离要求 reviewer 与 executor 共享同一工作区但 reviewer 只读（容器层 EROFS）、executor 可写：
     * 把 `/workspace` 从容器可写层移到 host 卷（服务器换 PVC/OSSFS），executor 挂同卷 {@code readOnly=false}、
     * reviewer 挂 {@code readOnly=true}，即「同卷共享 + 写权限分面」。置 false 不挂卷、回落容器可写层（零变化）；
     * 显式开启且配了 {@link Volume#hostPath}（或 pvc/ossfs）才真正挂载。</p>
     */
    private boolean volumeEnabled = false;

    /**
     * `/workspace` 卷定义（`SL-10`）。
     *
     * <p>SDK {@code Volume} 映射：本地优先 {@code host}（容器宿主目录）；服务器按部署形态
     * 在 {@code pvc} / {@code ossfs} 里二选一。{@link #hostPath} 为空且无 pvc/ossfs 时
     * 视同未启用卷（回落容器可写层）。</p>
     */
    private Volume volume = new Volume();

    /**
     * 取命名规格；不存在返回 {@code null}（由调用方决定回落默认还是 fail-close）。
     *
     * <p>不在此静默回落：配置写错名若被当成默认规格，症状是「配了 node 却跑 python 容器」，排查成本极高；
     * 返回 {@code null} 让调用方打 warn 并显式回落，日志可见。</p>
     *
     * @param name 规格名（{@code null}/空白 ⇒ {@code null}，表示「未指定」）
     */
    public Spec findSpecPreset(String name) {
        if (name == null || name.isBlank() || specPresets == null) {
            return null;
        }
        return specPresets.get(name.trim());
    }

    /**
     * 取生效规格：指定名字且命中 ⇒ 该规格；否则 ⇒ 全局 {@link #spec}。
     *
     * @param name 规格名（可为 null）
     */
    public Spec resolveSpec(String name) {
        Spec preset = findSpecPreset(name);
        return preset != null ? preset : spec;
    }

    /**
     * 声明式沙箱规格字段（与 OpenSandbox API 请求体逐字对齐，S-1）。
     */
    @Data
    public static class Spec {

        /** 沙箱类型标识（当前支持 opensandbox）。 */
        private String type = "opensandbox";

        /** 容器镜像（如 python:3.12）。 */
        private String image = "python:3.12";

        /** 容器入口命令（默认 tail -f /dev/null 保持存活）。 */
        private List<String> entrypoint = new ArrayList<>(List.of("tail", "-f", "/dev/null"));

        /** 资源限制（K8s 风格字符串口径）。 */
        private ResourceLimits resourceLimits = new ResourceLimits();

        /** 网络策略（默认禁出 + egress 白名单）。 */
        private NetworkPolicy networkPolicy = new NetworkPolicy();

        /** 生命周期作用域（agent / user / fixed）。 */
        private String scope = "agent";

        /** 沙箱超时秒数（默认 3600 = 1h）。 */
        private long timeoutSeconds = 3600L;
    }

    /** 资源限制（K8s 风格字符串，与 OpenSandbox API 逐字对齐）。 */
    @Data
    public static class ResourceLimits {

        private String cpu = "500m";

        private String memory = "512Mi";
    }

    /** 网络策略：默认动作 + egress 白名单（默认禁出，与 REF-5.4 同向）。 */
    @Data
    public static class NetworkPolicy {

        private String defaultAction = "deny";

        private List<String> egress = new ArrayList<>();
    }

    /**
     * `/workspace` 卷定义（`SL-10`）。
     *
     * <p>{@code host} 用于本地 / docker-in-docker 形态：一个宿主机目录配给同一任务的
     * executor 与 reviewer 共享，靠 {@code readOnly} 分写权限。{@code pvc} / {@code ossfs}
     * 为服务器 K8s / OSS 形态，三者互斥（{@code host} 优先）。</p>
     */
    @Data
    public static class Volume {

        /** 宿主机目录（本地 / docker；为空则回落 pvc/ossfs，全空视为未启用卷）。 */
        private String hostPath = "";

        /** K8s 持久卷声明名（服务器形态；与 {@link #hostPath} 互斥）。 */
        private String pvc = "";

        /** OSSFS 挂载标识（服务器形态；与 {@link #hostPath} 互斥）。 */
        private String ossfs = "";

        /** 卷在容器内的挂载点（固定为工作区根 {@code /workspace}）。 */
        private String mountPath = "/workspace";

        /** 只读挂载（reviewer 用；executor 走 {@code readOnly=false}）。 */
        private boolean readOnly = false;
    }
}
