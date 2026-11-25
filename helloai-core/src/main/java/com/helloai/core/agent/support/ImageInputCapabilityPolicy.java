package com.helloai.core.agent.support;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 「本次请求能不能带图」的<b>唯一判定</b>（不变量 I-2 的判定本体）。
 *
 * <p><b>为什么必须抽成共享组件（而不是各消费点各写一次）</b>：这个判定的消费点有两个——
 * 对话面 {@code AgentChatClientServiceImpl#applyUserMessage}（`VU-9`）与执行面
 * {@code AgentRuntimeContextAssembler#resolveMedia}（`VU-10`）。两处若各写一份，
 * 任一处改判据（比如从「查模型元数据」改成「同时看 Agent 能力位」）另一处就静默偏离，
 * 得到<b>同一个模型在对话面能看图、在执行面不能看</b>这种最难排查的形态（判据 4）。</p>
 *
 * <p><b>数据源是 {@code llm_provider_model.capabilities} 的 {@code input} 数组</b>
 * （经 {@code ModelCapabilities#supportsInput}），<b>不是</b> {@code AgentAccessType} 的能力位：
 * {@code CAP_SUPPORTS_IMAGE_UNDERSTANDING} 是「这个 Agent 声明自己能看图」的治理视图，
 * 而「这次请求能不能带图」取决于<b>实际调用模型的元数据</b>。两者可能不一致
 * （Agent 声明了、但它绑的模型不支持），此时以<b>模型元数据</b>为准——
 * 因为 provider 拒收图片是硬约束，声明不改变事实。</p>
 *
 * <p><b>为什么按 {@code Agent.modelType} 而不是前端选中的模型</b>：这是
 * OpenCode issue {@code #60736} 的成因形态——维护者原话是「note 是每次构建请求时按实际
 * 使用的模型加的，而你前端看着选的是 {@code deepseek-flash}」。本项目存在 provider
 * 回落与模型变体，前端选中 ≠ 实际调用 ⇒ 判定必须在装配层、用即将真正用于请求的
 * {@code Agent} 来取。</p>
 *
 * <p><b>fail-close</b>：查不到模型配置（{@code null}）、未声明 {@code image}、或查询本身抛异常
 * ⇒ 一律视为<b>不支持</b>。反过来（查不到就当支持）在有图时必被 provider 400，
 * 且报错与真实原因无关，排查成本远高于少送一张图。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageInputCapabilityPolicy {

    /** 图片输入模态 token（{@code llm_provider_model.capabilities.input} 的取值之一）。 */
    static final String MODALITY_IMAGE = "image";

    /**
     * 模型看不到图时的显式说明（不变量 I-4「禁假装看过」的落地产物）。
     *
     * <p><b>为什么放在本类（唯一来源）</b>：这条文案与「本次是否真的送了图」是<b>同一个事实</b>，
     * 而本类正是那个事实的判定处。两个消费面（对话面 {@code AgentChatClientServiceImpl}、
     * 执行面 {@code AgentRuntimeContextAssembler}）都要用它，各写一份必然漂移——
     * 一边说「看不到」、另一边忘了说，模型在后者就会凭文件名编造图片内容
     * （正文级判据要求：两处措辞不得各写一套）。</p>
     *
     * <p><b>措辞要求</b>：明确「附件存在但当前模型无法查看图像内容」，并明确
     * 「不要凭文件名推测内容」——后者是这条说明的核心价值：模型拿到文件名后
     * 编造图片内容是当前最坏的失败形态（用户看不出是编的）。</p>
     */
    public static final String MEDIA_NOT_VISIBLE_NOTE =
            "（说明：本轮附带了图片，但当前模型无法查看图像内容。"
                    + "请勿根据文件名、大小或上下文推测图片内容；"
                    + "如回答依赖图中信息，请直接说明看不到图并建议改用支持图片输入的模型或改用文字描述。）";

    private final LlmProviderModelQueryService llmProviderModelQueryService;

    /**
     * 该 Agent 实际绑定的模型是否声明可读图片。
     *
     * <p><b>绝不抛异常</b>：媒体是增强而非必要条件，能力查询失败必须退化为「看不到」，
     * 不能让整轮对话或整个子任务因此失败（同 {@code AttachmentMediaAssembler} 的纪律）。</p>
     *
     * @param agent 即将用于本次请求的 Agent（可空；null 视为不支持）
     * @return true-声明了 {@code image} 输入；false-未声明 / 查不到 / 查询失败
     */
    public boolean supportsImageInput(Agent agent) {
        return agent != null && supportsImageInputByModelType(agent.getModelType());
    }

    /**
     * 按 {@code modelType} 判定能否带图（`VU-2` reviewer 侧入口）。
     *
     * <p><b>为什么需要这个入口而不是让调用方自己查 Agent</b>：核验侧手里只有
     * {@code AgentProfileSnapshot}（只读投影，含 {@code modelType} 但<b>不含</b>
     * {@code Agent} 实体），为了判一次能力去回查实体既多一次主键读，也会把「判定」这件事
     * 散成「调用方先取字段、再调判定」两步——那正是两处各写一份的开端（判据 4）。
     * 判定本体收在本方法，实体版只是取字段后委托，两个入口的判据<b>永远同源</b>。</p>
     *
     * <p><b>为什么不叫 {@code supportsImageInput(String)}（同名重载）</b>：与
     * {@link #supportsImageInput(Agent)} 构成「同参数个数 + 不相关类型」的重载对，
     * 任何传 {@code null} 字面量或 Mockito {@code any()} 的调用点都会变成
     * <b>「对 supportsImageInput 的引用不明确」编译错</b>（本类首次落地即撞上：测试的
     * {@code supportsImageInput(null)} 与核验侧的三元表达式同时歧义）。改名比在每处加强转更彻底——
     * 强转只治当次调用，地雷仍留着。</p>
     *
     * @param modelType 模型标识（{@code provider:model} 或裸模型名）；空 ⇒ 不支持
     * @return true-声明了 {@code image} 输入；false-未声明 / 查不到 / 查询失败
     */
    public boolean supportsImageInputByModelType(String modelType) {
        if (!StringUtils.hasText(modelType)) {
            return false;
        }
        try {
            LlmProviderModelProfile profile =
                    llmProviderModelQueryService.findCapabilityProfileByModelType(modelType);
            if (profile == null || profile.capabilities() == null) {
                return false;
            }
            return profile.capabilities().supportsInput(MODALITY_IMAGE);
        } catch (RuntimeException e) {
            // 元数据查询失败不得让主链路失败：媒体是增强，降级为「看不到」即可
            log.warn("模型图片能力查询失败，按不支持处理: modelType={}, err={}", modelType, e.toString());
            return false;
        }
    }
}