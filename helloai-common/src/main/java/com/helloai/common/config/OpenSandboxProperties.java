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
     * 命名规格表（`SKX-5`，2026-10-26）：{@code helloai.sandbox.opensandbox.spec-presets.<name>.*}。
     *
     * <p><b>为什么需要按调用分层</b>：同一个子任务里可能先跑 python 验算、再跑 node 出图，
     * 两种容器镜像不同、资源需求也不同。全局单例 {@link #spec} 只能表达一种，故引入命名表；
     * {@code default} 仍走 {@link #spec}（既有配置零迁移）。</p>
     *
     * <p><b>缺省即安全</b>：表为空时所有调用回落到 {@link #spec}，行为与 `SKX-5` 之前完全一致。</p>
     */
    private Map<String, Spec> specPresets = new LinkedHashMap<>();

    /**
     * 技能执行使用的命名规格（`SKX-8`，2026-10-26，默认 {@code node}）。
     *
     * <p><b>为什么单独一个键</b>：技能执行的镜像由「技能需要什么运行时」决定（archify 要 node），
     * 与「验算命令用什么镜像」是两件事。留成一个可配置键，换技能（如 python 类）时只改配置不改代码。
     * 指向 {@link #specPresets} 的键名；缺失时回落全局 {@link #spec} 并打 warn。</p>
     */
    private String skillSpecPreset = "node";

    /**
     * 复用沙箱作用域（`SL-1`，2026-11-12，默认 {@code task}）。
     *
     * <p>{@code task} = <b>任务级共享</b>：同一任务下的全部子任务命中同一个「默认规格」沙箱
     * （槽键 {@code task:{taskId}}），上游产物跨子任务可见、返工可续作；任务终态
     * （{@code DONE}/{@code CANCELLED}/{@code 删除}）时经事件释放该任务全部沙箱（`SL-2`）。</p>
     *
     * <p>{@code subtask} = <b>子任务级</b>（旧语义，仅用于回滚 / 排障）：槽键
     * {@code scope:{subTaskId}}，turn 结束释放——行为回到 2026-11-11 前。</p>
     *
     * <p><b>会话内技能执行不受影响</b>：{@code scopeId}（conversationId）经
     * {@code SandboxScopes} 解析未命中即回落直接作用域，两种模式下行为一致。</p>
     */
    private String scope = "task";

    /**
     * 是否启用<b>同作用域复用</b>沙箱（`ADR-005`，2026-10-25；默认 true）。
     *
     * <p>对齐 deepseek-harness `dsh-tool-bash-persistent` 的产品级口径：同一作用域内的多次
     * `internal_run_in_sandbox` 调用复用同一沙箱，<b>写入的文件跨调用保留</b>，模型可对上一轮
     * 产物做增量编辑（`sed -i` / `python` 定点改），而不是每轮整篇重写。</p>
     *
     * <p>置 false ⇒ 回落原「每次调用新建 + 无条件 kill」的一次性语义（行为零变化，
     * 用于排障 / 需要绝对隔离的场景）。</p>
     */
    private boolean reuseEnabled = true;

    /**
     * 空闲上限（秒，默认 900 = 15min）；<b>只用于「空闲回收器」判活</b>（`SL-7`，2026-11-13）。
     *
     * <p><b>语义变更（`SL-7`）</b>：本值不再作为 {@code renew} 的目标——`renew` 是<b>绝对</b>
     * 过期时刻（2026-11-13 探针实测：把 15:24 的过期改到 06:24 会真的提前回收），
     * 拿 900s 续期会把容器寿命压在 15 分钟。改由 {@link #keepAliveSeconds} 决定容器能活多久。</p>
     *
     * <p><b>适用范围</b>：任务级槽（{@code task:{taskId}}）以 {@link #keepAliveSeconds} 为回收阈值——
     * 任务可能长时间等人工审查/等待下游，空闲 15 分钟就销毁会丢掉整个任务工作区；
     * 会话技能等 {@code scope:{id}} 槽无终态释放点，按本值回收。</p>
     */
    private long idleTtlSeconds = 900L;

    /**
     * 容器保活目标（秒，默认 28800 = 8h）；{@code renew} 的绝对过期时刻（`SL-7`，2026-11-13）。
     *
     * <p><b>为什么与空闲上限拆成两个量</b>：`renew` 只能设置绝对过期时刻，写小值会缩短寿命、
     * 写大值又需要「有回收器兜底」否则等于泄漏。拆开之后——{@code renew} 负责「活到 8 小时」，
     * {@link #idleTtlSeconds} 负责「多久算废弃」，两者互不干扰。</p>
     *
     * <p><b>与创建参数的关系</b>：创建时的 {@code spec.timeout-seconds} 只是初始预算
     * （探针实测：创建 300s 后可 {@code renew} 到 600s，服务端允许超出创建值），
     * 因此不需要把规格里的 {@code timeout-seconds} 调到 8h。</p>
     */
    private long keepAliveSeconds = 28_800L;

    /**
     * `/workspace` 卷化开关（`SL-10`，2026-11-13，默认 false）。
     *
     * <p><b>为什么卷化</b>：审核隔离要求 reviewer 与 executor 共享同一份工作区，
     * 但 reviewer 只能读（容器层 EROFS）、executor 可写。把 `/workspace` 从容器可写层
     * 移到 host 卷（服务器按部署形态换 PVC / OSSFS）后，executor 挂同卷
     * {@code readOnly=false}、reviewer 挂同卷 {@code readOnly=true}，即达到
     * 「同卷共享 + 写权限分面」。</p>
     *
     * <p><b>缺省即安全</b>：置 {@code false} 不挂任何卷，回落既有「容器可写层」行为
     * （零变化）；显式开启且配置了 {@link Volume#hostPath}（或 {@code pvc}/{@code ossfs}）
     * 才真正挂载。</p>
     */
    private boolean volumeEnabled = false;

    /**
     * `/workspace` 卷定义（`SL-10`，2026-11-13）。
     *
     * <p>SDK {@code Volume} 映射：本地优先 {@code host}（容器宿主目录）；服务器按部署形态
     * 在 {@code pvc} / {@code ossfs} 里二选一。{@link #hostPath} 为空且无 pvc/ossfs 时
     * 视同未启用卷（回落容器可写层）。</p>
     */
    private Volume volume = new Volume();

    /**
     * 取命名规格；不存在返回 {@code null}（由调用方决定「回落默认还是 fail-close」）。
     *
     * <p><b>为什么不在这里静默回落</b>：配置写错名字若被静默当成默认规格，症状是
     * 「明明配了 node 规格却跑了 python 容器」，排查成本极高。返回 {@code null} 让调用方
     * 打一条 warn 并显式回落，日志里能看见。</p>
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
     * `/workspace` 卷定义（`SL-10`，2026-11-13）。
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
