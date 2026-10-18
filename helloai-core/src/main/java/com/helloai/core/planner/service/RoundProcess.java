package com.helloai.core.planner.service;

import java.util.List;

/**
 * 一轮对话的「过程数据」（思考过程 + 检索步骤）：可随消息 payload 落库、done 后可回看。
 *
 * <p><b>为什么必须落库</b>（2026-10-18 用户实测）：原实现只把 reasoning 作为 SSE 流里的
 * 一次性帧推给前端，`sending` 结束后整个流式占位区被移除 ⇒ <b>思考过程随回复完成而消失</b>，
 * 刷新页面更是完全不可见。DeepSeek 网页版 / ChatGPT 的做法是——思考与检索步骤都是
 * 「消息的一部分」：实时流式展示，结束后折叠成一行「已深度思考（用时 12 秒）」，
 * 刷新/切换会话后依旧能展开回看。本类型承载这份可持久化的过程数据。</p>
 *
 * <p><b>steps 与 thinking 分开</b>：thinking 是模型自述的推理文本（可能很长、需流式追加）；
 * steps 是平台侧发生的动作事件（「正在联网检索 xxx」「已检索 N 条 · 21.5s」），
 * 数量少、语义结构化。前端按到达顺序混排展示，但样式与折叠策略不同。</p>
 *
 * @param thinking 思考过程全文（无推理模型/无思考时为空串）
 * @param steps    过程步骤（按发生顺序；如联网检索的发起与结果摘要）
 */
public record RoundProcess(String thinking, List<String> steps) {

    /** 空过程（同步路径/无思考无检索时用，落库时视为「无过程数据」不写 payload）。 */
    public static final RoundProcess EMPTY = new RoundProcess("", List.of());

    public RoundProcess {
        thinking = thinking == null ? "" : thinking;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 是否确有过程数据（决定是否写 payload，避免给纯文本回复塞空对象）。 */
    public boolean isEmpty() {
        return thinking.isBlank() && steps.isEmpty();
    }
}
