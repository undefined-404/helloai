package com.helloai.common.constant;

/**
 * 敏感内容扫描挂点（`MA-8`）。
 *
 * <p><b>为什么挂点本身是枚举</b>：命中事件要能回答「在哪漏的」——用户贴进来的、模型自己产出的、
 * 还是出站给模型时带的。三个挂点各挡一类（蓝图 §4 `MA-8` ⑥「为什么三个挂点都要」），
 * 聚合 SQL 要按挂点分组，因此挂点必须是有稳定 id 的枚举常量，不能是各处自写的字符串。</p>
 */
public enum SensitiveScanPoint {

    /** 挂点①：用户输入进对话<b>前</b>（需求澄清 / 助理对话）。 */
    INPUT("input", "用户输入"),

    /** 挂点②：子任务产物<b>落库前</b>（执行结果提交）。 */
    ARTIFACT("artifact", "产物落库"),

    /** 挂点③：LLM 请求<b>出站前</b>（执行上下文装配）。 */
    LLM_OUTBOUND("llm_outbound", "模型出站"),

    /**
     * 挂点④：会话附件正文<b>解析完成落库前</b>（上传 / 平台产物登记）。
     *
     * <p><b>为什么必须单独一个挂点，而不能靠前三个覆盖</b>：文件内容存在
     * {@code chat_attachment.extracted_text} 里，它是<b>另一条数据通路</b>——
     * ① 挂点①扫的是消息 {@code content}，用户打的字里没有文件正文；
     * ② 挂点③扫的是子任务执行链的 {@code userPrompt}，而对话 / 澄清链的附件正文
     *    由 {@code ChatAttachmentPromptRenderer} 拼进自己的模板，不经过那个装配点；
     * ③ 运行时 {@code internal_read_attachment} 直读对象存储，也不经过挂点③。
     * 三条主链之外，这是唯一能在「正文进入任何 Prompt 之前」看到完整原文的位置。</p>
     */
    ATTACHMENT("attachment", "附件正文"),
    ;

    private final String code;
    private final String label;

    SensitiveScanPoint(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 事件 payload / 聚合 SQL 使用的稳定 id（snake_case）。 */
    public String code() {
        return code;
    }

    /** 人话短标签（进日志）。 */
    public String label() {
        return label;
    }
}
