package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcceptanceItemSplitter} 单测（`RV-3` 拆分口径）。
 *
 * <p>锁定三件事：① 圈号命中时条目内部的全角 {@code ；} 不再拆（真机 5 条圈号样例的形态）；
 * ② 半角 {@code ;} 永不参与拆分（真机 0 命中且高频出现在代码片段里）；
 * ③ 无边界信号时整段作唯一一项——拆分只影响「有几条要核」，宁少拆不漏判。</p>
 */
@DisplayName("AcceptanceItemSplitter（RV-3 验收标准拆分）")
class AcceptanceItemSplitterTest {

    @Test
    @DisplayName("圈号优先：条目内含全角分号属句内停顿，不误拆")
    void circledNumbersTakePriorityAndKeepInnerFullWidthSemicolon() {
        List<String> items = AcceptanceItemSplitter.split(
                "① 输出 Markdown 调研报告；需含对比表格；② 报告末尾给出结论");

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).isEqualTo("输出 Markdown 调研报告；需含对比表格");
        assertThat(items.get(1)).isEqualTo("报告末尾给出结论");
    }

    @Test
    @DisplayName("无圈号时按全角分号拆")
    void fallsBackToFullWidthSemicolon() {
        assertThat(AcceptanceItemSplitter.split("接口清单完整；每个端点标注参数；含错误码表"))
                .containsExactly("接口清单完整", "每个端点标注参数", "含错误码表");
    }

    @ParameterizedTest
    @DisplayName("半角分号不参与拆分：代码片段与枚举串保持整段")
    @ValueSource(strings = {
            "验收 SQL 脚本可执行：SELECT a; SELECT b;",
            "配置项为 key;value;another 形式",
    })
    void halfWidthSemicolonNeverSplits(String acceptance) {
        assertThat(AcceptanceItemSplitter.split(acceptance)).hasSize(1);
    }

    @Test
    @DisplayName("整段无分隔符：返回唯一一项，不返回空清单")
    void singleItemWhenNoSeparator() {
        assertThat(AcceptanceItemSplitter.split("交付一个可运行的批量重命名脚本"))
                .containsExactly("交付一个可运行的批量重命名脚本");
    }

    @ParameterizedTest
    @DisplayName("空输入返回空清单（没有可核对的验收点，批评器据此跳过）")
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankInputYieldsEmptyList(String acceptance) {
        assertThat(AcceptanceItemSplitter.split(acceptance)).isEmpty();
    }

    @Test
    @DisplayName("null 输入返回空清单")
    void nullInputYieldsEmptyList() {
        assertThat(AcceptanceItemSplitter.split(null)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("清洗：去行首列表符号、去尾部句读（否则子串归属会失败）")
    @CsvSource({
            "'- 接口清单覆盖全部端点。', '接口清单覆盖全部端点'",
            "'* 每个端点标注参数、', '每个端点标注参数'",
            "'· 含错误码表，', '含错误码表'",
    })
    void normalizesBulletPrefixAndTailPunctuation(String raw, String expected) {
        assertThat(AcceptanceItemSplitter.split(raw)).containsExactly(expected);
    }

    @Test
    @DisplayName("清洗：首尾空白一律去除")
    void trimsOuterWhitespace() {
        assertThat(AcceptanceItemSplitter.split("  交付报告  ")).containsExactly("交付报告");
    }

    @Test
    @DisplayName("拆分后为空白的条目丢弃，不占位")
    void emptySegmentsAreDropped() {
        assertThat(AcceptanceItemSplitter.split("① ；② 有效条目")).containsExactly("有效条目");
    }

    @Test
    @DisplayName("条目数上限 20：异常输入不把 finding 文案撑爆")
    void capsItemAtTwenty() {
        StringBuilder acceptance = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            acceptance.append("第").append(i).append("条").append('；');
        }

        assertThat(AcceptanceItemSplitter.split(acceptance.toString())).hasSize(20);
    }

    @Test
    @DisplayName("单条超长截断至 500 字符，保留原样交由覆盖判据兜")
    void capsItemLengthAtFiveHundredChars() {
        String longItem = "长".repeat(600);

        assertThat(AcceptanceItemSplitter.split(longItem))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item).hasSize(500));
    }

    @Test
    @DisplayName("圈号超出 ①~⑳ 范围（如 ㉑）不作为边界")
    void circledRangeIsLimitedToTwenty() {
        assertThat(AcceptanceItemSplitter.split("㉑ 第二十一条㉒ 第二十二条"))
                .hasSize(1);
    }

    @Test
    @DisplayName("真机样本：末尾「即判不通过」是驳回条件，并入前一条而非独立验收项")
    void mergesTrailingConsequenceClauseIntoPreviousItem() {
        List<String> items = AcceptanceItemSplitter.split(
                "要点卡须同时具备 3 个必填要素（读者是谁、读者要做什么判断、本段提供什么依据）且事实要点条目数 ≤3；"
                        + "每条事实要点后须附来源标注（知识库 source#chunkIdx 或任务上下文出处）；"
                        + "三要素缺任一项、或任一条要点无来源标注，即判不通过。");

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).contains("3 个必填要素").contains("条目数 ≤3");
        // 合并后原文保留：reviewer 只对前一条实质要求写覆盖，也能命中
        assertThat(items.get(1)).contains("来源标注").contains("即判不通过");
    }

    @ParameterizedTest
    @ValueSource(strings = {"否则不通过", "则判不通过", "均判不通过", "一律不通过", "视为不通过", "视同不通过"})
    @DisplayName("后果条款各变体均并入前一条")
    void mergesAllConsequenceVariants(String marker) {
        assertThat(AcceptanceItemSplitter.split("第一项要求；第二项要求；缺少任一，".concat(marker)))
                .hasSize(2)
                .last().asString().contains(marker);
    }

    @Test
    @DisplayName("首项即后果条款（无可归属前文）原样保留，不丢原文")
    void keepsLeadingConsequenceClauseAsIs() {
        assertThat(AcceptanceItemSplitter.split("即判不通过；第二条要求"))
                .containsExactly("即判不通过", "第二条要求");
    }

    @Test
    @DisplayName("圈号形态下被单独编号的后果条款同样并入前一条")
    void mergesConsequenceClauseUnderCircledNumbering() {
        List<String> items = AcceptanceItemSplitter.split(
                "① 输出清单；② 逐条标注参数；③ 否则不通过");

        assertThat(items).hasSize(2);
        assertThat(items.get(1)).isEqualTo("逐条标注参数；否则不通过");
    }

    @Test
    @DisplayName("真机样本：圈号前的引导句（以冒号结尾）不作为独立验收项")
    void dropsLeadInClauseBeforeCircledItems() {
        List<String> items = AcceptanceItemSplitter.split(
                "三项结构断言全部满足才通过：①按汉字正则统计的汉字数 ≤120（脚本输出汉字数 = N 且 N ≤ 120）；"
                        + "②正文中「双域」出现 ≥1 次、「自检」出现 ≥1 次；"
                        + "③正文为一整段，且不含标题、列表符号、markdown 标记与「字数：X」类附加说明。");

        assertThat(items).hasSize(3);
        assertThat(items).noneMatch(item -> item.contains("全部满足才通过"));
        assertThat(items.get(0)).startsWith("按汉字正则统计的汉字数");
    }

    @Test
    @DisplayName("中间位置的引导句（以冒号结尾）同样丢弃，前后条目保持原序")
    void dropsMiddleLeadInClause() {
        assertThat(AcceptanceItemSplitter.split("第一项要求；第二部分如下：；第三项要求"))
                .containsExactly("第一项要求", "第三项要求");
    }

    @Test
    @DisplayName("全部条目都是引导句时回退为原文，不把整段判成「无需核对」")
    void fallsBackWhenEveryItemLooksLikeLeadIn() {
        assertThat(AcceptanceItemSplitter.split("要求如下："))
                .containsExactly("要求如下：");
    }

    @Test
    @DisplayName("真机样本（第二轮）：括号内的交叉引用圈号不是条目边界")
    void circledNumberInsideParenthesesIsNotABoundary() {
        List<String> items = AcceptanceItemSplitter.split(
                "结构可数断言：（对应任务级验收条目 ①）① 正文中「双域」出现 ≥1 次且「自检」出现 ≥1 次，命中计 2/2；"
                        + "（对应任务级验收条目 ②）② 汉字数 ≤120，且含标点的总字符数 ≤120，"
                        + "交付中给出两项计数过程与结果；③ 正文首字符即正文首字、末字符为句末标点；"
                        + "④ 正文中每条事实可回溯到子任务 1 或 2 的清单条目。");

        assertThat(items).hasSize(4);
        assertThat(items).noneMatch(item -> item.contains("对应任务级验收条目"));
        assertThat(items.get(0)).isEqualTo("正文中「双域」出现 ≥1 次且「自检」出现 ≥1 次，命中计 2/2");
        assertThat(items.get(1)).startsWith("汉字数 ≤120");
    }

    @Test
    @DisplayName("括号内圈号 + 引导句：括号感知拆分后引导句按冒号规则丢弃")
    void circledInsideParenthesesThenLeadInDropped() {
        assertThat(AcceptanceItemSplitter.split("（对应条目 ①）① 甲；② 乙"))
                .containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("普通括号（可能含实质要求）不被当作交叉引用剥离")
    void keepsOrdinaryParentheses() {
        assertThat(AcceptanceItemSplitter.split("输出（含表格）；附截图"))
                .containsExactly("输出（含表格）", "附截图");
    }

    @Test
    @DisplayName("独立成条的交叉引用括号被丢弃，不产生空洞验收项")
    void dropsStandaloneCrossReferenceParenthetical() {
        assertThat(AcceptanceItemSplitter.split("正文内容；（对应任务级验收条目 ②）"))
                .containsExactly("正文内容");
    }
}
