package com.helloai.core.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

/**
 * 内部执行链的「工具调用上下文」——把执行者的真实标识<b>带外</b>注入内部工具的调用参数
 * （REF-8.1 A1 接线）。
 *
 * <p><b>为什么存在</b>：内部 LLM（{@code API_KEY_LLM}）走进程内 {@code AgentLoop}，模型
 * <b>无从得知</b>自己本轮的执行者 {@code agentId} 与当前 {@code subTaskId}（这两个值不进入
 * systemPrompt / userPrompt；{@code AgentLoopInput.agentId} 仅用于事件埋点）。而
 * {@link com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools} 的读型 / 交付登记工具
 * 需要这两个标识做归属校验——若交给模型自报，只会得到幻觉值。故由执行链在调用工具前
 * <b>带外</b>注入真实值，模型无需也无法自报。</p>
 *
 * <p><b>为什么用 {@link ThreadLocal} 而非显式传参</b>：工具执行入口
 * {@link ToolExecutor#execute(String, String)} 的签名由循环调用点固定（{@code ChatModelToolLoop}），
 * 而在「不动 {@code ChatModelToolLoop} 循环本体」的红线下（借鉴落地 §10.5），
 * 上下文无法经方法参数下沉到执行器——带外绑定是<b>唯一合规路径</b>。绑定/解绑严格成对
 * （{@code RuntimeTurnExecutor} 以 {@code try/finally} 保证），且为请求内线程局部状态，
 * <b>不构成单实例假设</b>（区别于进程级注册表式缺陷）。</p>
 *
 * <p><b>注入纪律（两道门控，避免误伤）</b>：
 * <ol>
 *   <li><b>前缀门控</b>——仅 {@link #INTERNAL_TOOL_PREFIX}（{@code internal_}）前缀工具注入，
 *       外部 MCP 工具（{@code web_search} / {@code get_sub_task_detail} 等）逐字不变；</li>
 *   <li><b>schema 门控</b>——仅注入工具自身 {@code @ToolParam} <b>已声明</b>的字段
 *       （从 {@code inputSchema} 的 {@code properties} 派生，与工具注解<b>同源防漂移</b>），
 *       无参 / 无该字段的工具（如 {@code internal_list_skills}）不受影响。</li>
 * </ol>
 * 平台注入值<b>覆盖</b>模型所给值（执行链鉴定的标识为权威，不信任入参）。</p>
 *
 * <p><b>纯静态工具</b>：无 Spring 依赖，可独立单测（对齐 {@code UpstreamAttachmentRenderer} 风格）。</p>
 */
@Slf4j
public final class ToolInvocationContext {

    /** 内部 LLM 进程内工具名前缀（与外部 MCP 工具区分，见 {@code InternalLlmExecutorTools}）。 */
    public static final String INTERNAL_TOOL_PREFIX = "internal_";

    /** 平台注入字段名——须与 {@code InternalLlmExecutorTools} 的 {@code @ToolParam} 同名。 */
    private static final String PARAM_AGENT_ID = "agentId";

    /**
     * 平台注入字段名（{@code taskId}）——**预留通路，当前零消费方**（审计确认）。
     *
     * <p>全仓无任何内部工具声明 {@code @ToolParam(…) Long taskId}，故正常路径下
     * {@link #inject} 的 schema 门控恒为 false、本常量不被写入。保留理由与移除代价见
     * {@link #inject} 方法体内的同点注释。</p>
     */
    private static final String PARAM_TASK_ID = "taskId";

    private static final String PARAM_SUB_TASK_ID = "subTaskId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 当前线程绑定的执行者标识（未绑定为 {@code null}）。 */
    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    /**
     * 本执行链<b>已调用过的内部工具名</b>（REF-8.1.1）。
     *
     * <p>用途：「知情后才许做的动作」的判据——例如 {@code internal_report_blocked}（自报阻塞）
     * 要求先读过子任务详情（{@code internal_get_sub_task_detail}），否则拒绝。
     * 登记点在 {@link #inject}（内部工具执行的唯一咽喉），随 {@link #bind} 重置、
     * {@link #unbind} 清空，与执行链生命周期严格一致。</p>
     */
    private static final ThreadLocal<Set<String>> CALLED = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    /**
     * 本轮工具调用的执行者标识四元组 + 执行轮次；任一项可为 null（该字段则不注入）。
     *
     * <p>{@code taskId} 由 `SL-8` 透传：沙箱槽键是任务级的，执行链本来就知道
     * 子任务属于哪个任务；把它一并绑定后，工具层构造任务级工作区句柄不再需要二次查库。</p>
     *
     * <p>{@code reviewer} 由 `SL-10` 注入：reviewer 走业务面
     * （{@code subtask_review}/{@code task_final_report_review} 场景），其沙箱命令应选
     * {@code SandboxCallOrigin.REVIEWER} 且工作区句柄只读挂载——只有 {@code bind} 时
     * 按场景显式声明，不从 agent 角色反推（对齐 `SL-4` origin 显式声明纪律）。</p>
     */
    public record Ids(Long agentId, Long taskId, Long subTaskId, int round, boolean reviewer) {
    }

    /**
     * 绑定本轮执行链的真实标识（由 {@code RuntimeTurnExecutor} 在 {@code agentLoop.run()} 前调用）。
     * 与 {@link #unbind()} 严格成对，二次绑定覆盖旧值。
     *
     * @param taskId 子任务所属任务 ID（`SL-8`：任务级工作区句柄用；可为 null）
     * @param round  执行轮次（`SL-4`：沙箱台账来源/轮次用；缺省 0）
     * @param reviewer 是否审核角色（`SL-10`；reviewer 走只读挂载 + REVIEWER origin）
     */
    public static void bind(Long agentId, Long taskId, Long subTaskId, int round, boolean reviewer) {
        CURRENT.set(new Ids(agentId, taskId, subTaskId, Math.max(round, 0), reviewer));
        CALLED.remove();   // 二次绑定 = 新执行链，调用记录清零
    }

    /** 绑定标识（默认非 reviewer；任务子任务执行链使用，`SL-10`）。 */
    public static void bind(Long agentId, Long taskId, Long subTaskId, int round) {
        bind(agentId, taskId, subTaskId, round, false);
    }

    /** 绑定标识（不绑定 taskId；轮次显式；默认非 reviewer）。 */
    public static void bind(Long agentId, Long subTaskId, int round) {
        bind(agentId, null, subTaskId, round, false);
    }

    /** 绑定标识（轮次 0；业务面 / 无轮次语义的调用方使用；默认非 reviewer）。 */
    public static void bind(Long agentId, Long subTaskId) {
        bind(agentId, null, subTaskId, 0, false);
    }

    /** 当前执行轮次（未绑定返回 0）。 */
    public static int currentRound() {
        Ids ids = CURRENT.get();
        return ids == null ? 0 : ids.round();
    }

    /** 当前绑定的任务 ID（未绑定 / 未透传返回 {@code null}；`SL-8`）。 */
    public static Long currentTaskId() {
        Ids ids = CURRENT.get();
        return ids == null ? null : ids.taskId();
    }

    /**
     * 当前是否审核角色（`SL-10`）；未绑定返回 {@code false}。
     *
     * <p>供 {@code SandboxRuntimeAdapter.toRef} / {@code InternalLlmExecutorTools} 决策
     * 「只读挂载 + REVIEWER origin」。</p>
     */
    public static boolean currentReviewer() {
        Ids ids = CURRENT.get();
        return ids != null && ids.reviewer();
    }

    /** 解绑（{@code agentLoop.run()} 返回后必须调用，防线程复用串味）。 */
    public static void unbind() {
        CURRENT.remove();
        CALLED.remove();
    }

    /**
     * 登记「本执行链调用过该内部工具」（由工具方法自身在入口调用，REF-8.1.1）。
     *
     * <p>刻意<b>不</b>放在 {@link #inject} 里：注入是参数改写点，直连调用（单测 / 进程内复用）
     * 不经过它；放在工具方法入口才与「工具确实被执行」等价。</p>
     *
     * @param toolName 工具名（须与 {@code @Tool(name=…)} 逐字一致）
     */
    public static void markCalled(String toolName) {
        if (CURRENT.get() == null || toolName == null) {
            return;
        }
        Set<String> called = CALLED.get();
        if (called == null) {
            called = new HashSet<>();
            CALLED.set(called);
        }
        called.add(toolName);
    }

    /**
     * 本执行链是否调用过某内部工具（未绑定 / 未调用均为 false）。
     *
     * @param toolName 工具名（须与 {@code @Tool(name=…)} 逐字一致）
     */
    public static boolean hasCalled(String toolName) {
        Set<String> called = CALLED.get();
        return called != null && toolName != null && called.contains(toolName);
    }

    /** 当前线程绑定的标识；未绑定返回 {@code null}（仅供诊断 / 测试）。 */
    public static Ids current() {
        return CURRENT.get();
    }

    /**
     * 执行链上下文的**可跨线程快照**。
     *
     * <p><b>为什么必须能跨线程</b>：{@code CancellableToolExecutor}（REF-8.6 单工具超时装饰器）
     * 把工具调用提交到独立线程池（线程名 {@code agent-tool-timeout-N}）后
     * {@code future.get(timeout)} 等待。而本类的两个状态都是 {@link ThreadLocal}——
     * 子线程读不到父线程的绑定 ⇒ {@code requireInternalChain()} 判定「缺少执行链上下文」⇒
     * <b>全部 {@code internal_*} 工具在这条路径上 100% 失败</b>（实测：get_sub_task_detail 6 次 /
     * get_deps_summary 6 次 / list_skills 2 次 / run_in_sandbox 4 次，success 全为 false）。</p>
     *
     * <p>{@code called} 刻意<b>共享同一 Set 实例</b>（不复制）：工具在子线程执行时
     * {@link #markCalled(String)} 写入的记录，必须对父线程可见——否则
     * 「internal_report_blocked 要求先读子任务详情」的判据会永远为 false。</p>
     *
     * @param ids    执行者标识
     * @param called 已调用内部工具集（同一实例引用）
     */
    public record Scope(Ids ids, Set<String> called) {
    }

    /**
     * 捕获当前线程的执行链上下文；未绑定返回 {@code null}
     * （用方可据此决定「原样直调」而不必关心细节）。
     */
    public static Scope capture() {
        Ids ids = CURRENT.get();
        if (ids == null) {
            return null;
        }
        Set<String> called = CALLED.get();
        if (called == null) {
            // ★必须写回当前线程：否则子线程 markCalled 写的是「父线程拿不到的临时 Set」，
            // hasCalled() 在父线程永远为 false（「先读详情才许报阻塞」的判据会失效）
            called = new HashSet<>();
            CALLED.set(called);
        }
        return new Scope(ids, called);
    }

    /**
     * 在指定上下文快照中执行动作（供跨线程工具执行使用）：进入时装载、退出时严格还原，
     * 不污染宿主线程（线程池线程会被复用，必须还原）。
     *
     * @param scope  快照（{@code null} ⇒ 无上下文，直接执行）
     * @param action 实际动作
     */
    public static <T> T callWith(Scope scope, java.util.function.Supplier<T> action) {
        if (scope == null) {
            return action.get();
        }
        Ids prevIds = CURRENT.get();
        Set<String> prevCalled = CALLED.get();
        CURRENT.set(scope.ids());
        CALLED.set(scope.called());
        try {
            return action.get();
        } finally {
            if (prevIds == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prevIds);
            }
            if (prevCalled == null) {
                CALLED.remove();
            } else {
                CALLED.set(prevCalled);
            }
        }
    }

    /**
     * 把平台标识注入内部工具的调用参数（两道门控见类 javadoc）。
     *
     * <p><b>三支的消费现状（审计）</b>：{@code agentId} / {@code subTaskId} 各有多个
     * 内部工具以 {@code @ToolParam} 声明，属<b>在用</b>；{@code taskId}<b>零消费方</b>，属<b>预留</b>
     * ——它的实际去向是 {@link #currentTaskId()} 的 ThreadLocal 直读（任务级工作区句柄）。
     * 保留该支的理由（而非删除）见方法体内同点注释，核心是「不为『ID 只能带外注入』开例外」。</p>
     *
     * @param toolName      工具名（非 {@link #INTERNAL_TOOL_PREFIX} 前缀则<b>原样返回</b>）
     * @param inputSchema   工具自身的输入 schema（JSON 字符串，取自
     *                      {@code ToolCallback.getToolDefinition().inputSchema()}）；
     *                      null / 空白 / 解析失败时按「放行」处理（已受前缀门控约束，
     *                      且多重键会被 spring-ai 按参数名取参时忽略）
     * @param argumentsJson 模型产出的调用参数（原样透传；非法 JSON 时退化为「仅平台标识」对象）
     * @return 注入后的参数 JSON；未绑定上下文或非内部工具时返回入参本身
     */
    public static String inject(String toolName, String inputSchema, String argumentsJson) {
        Ids ids = CURRENT.get();
        if (ids == null || toolName == null || !toolName.startsWith(INTERNAL_TOOL_PREFIX)) {
            return argumentsJson;
        }
        ObjectNode node = parseObject(argumentsJson);
        if (ids.agentId() != null && declares(inputSchema, PARAM_AGENT_ID)) {
            node.put(PARAM_AGENT_ID, ids.agentId());
        }
        // taskId：预留通路，当前零消费方（审计确认）。
        // 现状：没有任何内部工具声明 `@ToolParam(…) Long taskId`，且内部工具的 inputSchema
        // 正常可达（取自 ToolCallback.getToolDefinition()），故 declares() 恒为 false，本分支
        // 在正常路径上不生效（唯一的例外是 schema 取不到时的 fail-open 放行，此时多写的键
        // 会被 spring-ai 按参数名取参忽略）。单测 ToolInvocationContextTest#taskIdShouldFollowSchemaGating
        // 用**合成 schema** 覆盖本分支，不代表存在真实消费方。
        //
        // 为什么不删——taskId 的真实用途本就「不经参数」：
        //   1) 绑定与读取自成一条通路：RuntimeTurnExecutor.bind(…, taskId, …) →
        //      currentTaskId() → SandboxRuntimeAdapter#toRef 构造任务级工作区句柄
        //      （`SL-8`：执行链已知道 taskId，就省掉热路径上每轮一次 SubTaskQueries 查库）。
        //      本分支只是同一条契约在「工具参数面」上的出口，不是 taskId 的唯一去向。
        //   2) 三支与 Ids 的三字段、bind(...) 的三个重载一一对应；只删一支，等于在
        //      「ID 只能带外注入、不能由模型自报」上开一个例外，读者会据此认定「taskId 永不
        //      作为工具参数」。将来新增任务级内部工具（如任务级产物清单 / 任务级台账汇总）
        //      按既有惯例声明 `@ToolParam(required = false) Long taskId`（描述「平台自动注入，
        //      无需填写」）时，模型只会传 null 或幻觉值，且是**静默失败**——正是本类要消除的
        //      缺陷类型。而 fails-loud 在这里不成立：该惯例下参数本身就允许缺省。
        //   3) 留存成本仅 3 行 + 1 常量，且 schema 门控保证「无声明即不写」，不会误伤任何工具。
        //
        // 反过来说，若将来确定 taskId 永不进入参数面，应连同 Ids#taskId() 与 currentTaskId()
        // 一起收口，而不是只删这一段——只删注入会让「已绑定但不注入」变成无声的半套语义。
        if (ids.taskId() != null && declares(inputSchema, PARAM_TASK_ID)) {
            node.put(PARAM_TASK_ID, ids.taskId());
        }
        if (ids.subTaskId() != null && declares(inputSchema, PARAM_SUB_TASK_ID)) {
            node.put(PARAM_SUB_TASK_ID, ids.subTaskId());
        }
        return node.toString();
    }

    /** 解析模型参数为可写对象；null / 空白 → 空对象；非法 JSON → 空对象（仅保留平台标识）。 */
    private static ObjectNode parseObject(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
            return MAPPER.createObjectNode();
        } catch (Exception e) {
            log.warn("工具调用参数非合法 JSON，退化为仅注入平台标识: err={}", e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    /** schema 是否声明了某字段（从 {@code properties} 派生，与 {@code @ToolParam} 同源）。 */
    private static boolean declares(String inputSchema, String param) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return true;
        }
        try {
            JsonNode properties = MAPPER.readTree(inputSchema).path("properties");
            return properties.isObject() && properties.has(param);
        } catch (Exception e) {
            log.debug("工具 inputSchema 解析失败，按放行处理: err={}", e.getMessage());
            return true;
        }
    }
}
