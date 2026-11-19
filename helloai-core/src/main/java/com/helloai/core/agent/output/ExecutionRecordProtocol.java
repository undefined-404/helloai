package com.helloai.core.agent.output;

/**
 * Executor 回填块（{@code EXECUTION_RECORD}）的唯一协议定义（{@code MA1-1}）。
 *
 * <p><b>为什么存在</b>：{@code ## EXECUTION_RECORD} 起止标记此前在生成侧（AgentRuntimeContextAssembler）、
 * 提取侧（ExecutionOutputParser 靠「JSON 之后」位置判断）、回填侧（ExecutionRecordParser）各写一遍。
 * 剥离规则若与回填侧分叉，{@code readModel} 剥离该块后回填侧会解析不到。本类把<b>协议标记</b>收口成
 * 单一常量，三处共用；同时提供纯函数 {@link #strip(String)} 供读模型剥离该机器块。</p>
 *
 * <p>只收口<b>协议定义</b>；注释、日志文案中的字面 {@code EXECUTION_RECORD} 说明文字不动
 * （它们描述语义，不属于解析器依赖的标记常量）。</p>
 *
 * <p>域归属：{@code agent}/output（生成侧所在域）。回填侧（task 域）对它是<b>前向</b>依赖
 * （域链 task→agent 合法），不会新增反向依赖。</p>
 */
public final class ExecutionRecordProtocol {

    /** 回填块的标题行（Markdown 二级标题）。 */
    public static final String SECTION_HEADER = "## EXECUTION_RECORD";

    /** 回填块的终止分隔行（{@link com.helloai.core.task.spec.ExecutionRecordParser} 以此为块尾；读模型剥离从 {@link #SECTION_HEADER} 到文本末尾）。 */
    public static final String BLOCK_END = "---";

    /** 摘要段标签（{@link com.helloai.core.task.spec.ExecutionRecordParser} 解析、迭代摘要提取两头共用）。 */
    public static final String SUMMARY_LABEL = "SUMMARY:";

    private ExecutionRecordProtocol() {
    }

    /**
     * 定位回填块标题行的位置——<b>取最后一次出现且在行首</b>的那一处。
     *
     * <p>为什么不用首次出现：回填块由生成侧拼在产出<b>尾部</b>；而正文里完全可能引用该字面
     * （例如正在改这个协议的子任务自己就会写到它）。首次出现会命中正文引用，把正常正文整段砍掉。
     * 取最后一次且要求行首，两者都满足时才是真正的块起点。</p>
     *
     * <p>剥离侧（本类 {@link #strip(String)}）与回填侧（{@code ExecutionRecordParser#parse}）
     * <b>必须用同一个定位口径</b>，否则会把「剥离后仍解析得到」的关系打破。</p>
     *
     * @param text 待定位文本，可空
     * @return 标题行在行首的绝对下标；无可用命中返回 -1
     */
    public static int locateBlockStart(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int idx = text.lastIndexOf(SECTION_HEADER);
        while (idx >= 0) {
            if (idx == 0 || text.charAt(idx - 1) == '\n') {
                return idx;
            }
            idx = text.lastIndexOf(SECTION_HEADER, idx - 1);
        }
        return -1;
    }

    /**
     * 从执行产出中剥离 {@code EXECUTION_RECORD} 回填块，返回剩余的展示正文。
     *
     * <p>纯函数：无状态、无 IO、不抛异常。块从 {@link #SECTION_HEADER} 所在行首开始到文本末尾
     * （生成侧把该块放在尾部）。起止边界与回填侧同源——见 {@link #locateBlockStart(String)}。</p>
     *
     * @param output 完整执行产出（可空）
     * @return 剥离回填块后的展示正文；未命中标题行时原样返回；{@code output} 为 null 亦返回 null
     */
    public static String strip(String output) {
        if (output == null) {
            return null;
        }
        int idx = locateBlockStart(output);
        if (idx < 0) {
            return output;
        }
        // 从标题行行首截断，避免把标题行前的换行残留进展示段；stripTrailing 已保证空串即 ""
        return output.substring(0, idx).stripTrailing();
    }
}
