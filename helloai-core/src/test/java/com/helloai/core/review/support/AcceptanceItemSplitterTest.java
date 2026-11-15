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

    /** 真机原文（2026-11-13 dev 沙箱探针任务 A，`review_record` round=1 INCONCLUSIVE 的原判定对象）。 */
    static final String REAL_ACCEPTANCE_A =
            "AUTOMATED（逐条可执行核查）：1) 交付物内 cat 原始输出行匹配正则 ^HELLOAI_SANDBOX_[0-9]{19,}$，且文末保留换行（对应任务级验收条目 3）；"
                    + "2) 交付物内 sha256sum 输出含 64 位十六进制摘要（^[0-9a-f]{64}$）且路径字段为 /workspace/probe.txt，"
                    + "重跑 sha256sum /workspace/probe.txt 得到的摘要与之逐字符相同（对应条目 2）；"
                    + "3) 交付物给出 wc -c 结果且数值等于对同一文件重跑 wc -c 的输出（该格式下预期为 36），"
                    + "同时与 ls -l 显示的大小一致（对应条目 2）；"
                    + "4) 交付物四段证据（cat 原文 / sha256 / 字节数 / 执行者标识与写入时刻）齐全，任一缺失即判 fail（对应条目 2）；"
                    + "5) 交付物全文不含 /tmp、不含 xxd 调用；对交付物文本执行 grep -q 'HELLOAI_SANDBOX_' 命中且有实际数字（对应条目 7、8）；"
                    + "6) 交付物正文为 UTF-8，grep -P '\\x{FFFD}|[\\x{E000}-\\x{F8FF}]' 无命中，且不出现 GBK/CP1252 双重解码乱码串。";

    /** 真机原文（同一任务的探针子任务 B）。 */
    static final String REAL_ACCEPTANCE_B =
            "AUTOMATED（逐条可执行核查）：1) 交付物含 ls -l 原始输出行，匹配正则 ^-[-rwx]{9}.*? +[0-9]+ +.*?/workspace/probe\\.txt$"
                    + "（含权限、大小、mtime、路径三要素）（对应任务级验收条目 4）；"
                    + "2) 交付物含 cat 原文且与 A 交付物的 cat 原文逐字相同（哈希比对：两者 echo -n 后 sha256 一致，行尾换行保留），"
                    + "且非「已读取/内容一致」式转述（对应条目 5）；"
                    + "3) 交付物含 64 位十六进制摘要，与 A 的摘要逐字符相同，且一致性判定字段为 YES（对应条目 6）；"
                    + "4) 四项（ls -l 行 / cat 原文 / sha256 / 判定）齐全，缺任一即 fail（对应条目 4）；"
                    + "5) 本子任务命令记录中 grep 不到 > >> tee touch cp mv rm chmod truncate 等写动作，"
                    + "且 probe.txt 在 B 执行前后 mtime 未变化（ls -l 的 mtime 与 A 申报写入时刻一致，误差 ≤1 分钟）（对应条目 9）；"
                    + "6) 全流程路径仅 /workspace/probe.txt，grep 不到 /tmp，grep 不到 xxd（对应条目 7、8）；"
                    + "7) 交付物同时含 A、B 两个执行者标识字段；若两者不同即 pass，若相同则须含 ≥1 条环境限制原因说明段落（对应条目 10）；"
                    + "8) 计划结构核查：仅存在 A、B 两个顺序子任务，B 的 dependsOn 非空且指向 A，无第三个子任务（对应条目 1）；"
                    + "9) 交付物正文为 UTF-8，grep -P '\\x{FFFD}|[\\x{E000}-\\x{F8FF}]' 无命中，且不出现 GBK/CP1252 双重解码乱码串。";

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

    @Test
    @DisplayName("真机样本（2026-11-13 dev 探针 A）：6 条编号逐条拆开，条目内全角分号不再截断")
    void splitsRealMachineAcceptanceA() {
        List<String> items = AcceptanceItemSplitter.split(REAL_ACCEPTANCE_A);

        assertThat(items).hasSize(6);
        assertThat(items).noneMatch(item -> item.matches("^\\d{1,2}[).、].*"));
        assertThat(items).noneMatch(item -> item.contains("逐条可执行核查"));
        // 第 5 条内部含全角分号：修复前会被拆成「5) …」与「对交付物…」两截
        assertThat(items.get(4)).startsWith("交付物全文不含 /tmp")
                .contains("；对交付物文本执行 grep -q 'HELLOAI_SANDBOX_'");
    }

    @Test
    @DisplayName("真机样本（2026-11-13 dev 探针 B）：9 条编号逐条拆开，引导句丢弃")
    void splitsRealMachineAcceptanceB() {
        List<String> items = AcceptanceItemSplitter.split(REAL_ACCEPTANCE_B);

        assertThat(items).hasSize(9);
        assertThat(items.get(7)).startsWith("计划结构核查");
        assertThat(items).noneMatch(item -> item.contains("逐条可执行核查"));
    }

    @ParameterizedTest
    @DisplayName("阿拉伯编号三种形态（1) / 1. / 1、）均作为条目边界")
    @ValueSource(strings = {"1) 甲；2) 乙", "1. 甲；2. 乙", "1、甲；2、乙"})
    void arabicNumberingVariantsSplit(String acceptance) {
        assertThat(AcceptanceItemSplitter.split(acceptance)).containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("两个合法边界才启用：版本号 1.2 与三位编号（100)）不算边界")
    void arabicNumberingRequiresAtLeastTwoRealBoundaries() {
        assertThat(AcceptanceItemSplitter.split("交付 1.2 版本；附带说明"))
                .containsExactly("交付 1.2 版本", "附带说明");
        assertThat(AcceptanceItemSplitter.split("100) 甲；100) 乙"))
                .containsExactly("100) 甲", "100) 乙");
    }

    @Test
    @DisplayName("单个偶然编号不足以切换模式：回退分号并保留编号原文")
    void singleIncidentalNumberFallsBackToSemicolon() {
        assertThat(AcceptanceItemSplitter.split("注意事项：1) 仅这一项；后续说明"))
                .containsExactly("注意事项：1) 仅这一项", "后续说明");
    }

    @Test
    @DisplayName("括号内编号是交叉引用，不是条目边界")
    void arabicNumberInsideParenthesesIsNotBoundary() {
        assertThat(AcceptanceItemSplitter.split("（见 1) 项）；甲；乙"))
                .containsExactly("（见 1) 项）", "甲", "乙");
    }
}
