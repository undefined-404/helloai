package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感内容扫描配置（`MA-8` 第一段：只记不拦）。
 *
 * <p><b>为什么 {@code enabled} 默认 {@code true}</b>：本项是 <b>log-only</b>，开启时对用户与执行链
 * 行为零变化，只是多写一条事件。默认关掉就永远拿不到观察期数据，观察期形同虚设。
 * 真正需要「默认 OFF」的是<b>阻断</b>那一档（蓝图 §4 `MA-8` ⑤ 升阻断三前提之一），
 * 本配置的 {@code enabled} 不是那个开关。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.sensitive-scan")
public class SensitiveScanProperties {

    /** 扫描总开关。默认开（log-only 零行为变化）；关闭后三个挂点都直接跳过，不写事件。 */
    private boolean enabled = true;

    /**
     * 要关掉的单条规则（{@code SensitiveScanRule.code()} 取值，如 {@code mobile}）。
     *
     * <p>蓝图 §4 `MA-8` ⑦：规则必须能<b>单个开关</b>——某条规则误报爆炸时不必整项下线。
     * 写错名字不静默忽略，启动时记一条 warn（否则「配置写错」与「配置生效」不可区分）。</p>
     */
    private List<String> disabledRules = new ArrayList<>();

    /**
     * 单次扫描的字符上限。超长文本只扫前 N 字符。
     *
     * <p>蓝图 §4 `MA-8` ⑦ 性能风险：正则在长文本上可能慢。只扫新增内容 + 超长截断是本项的
     * 明确处置。默认 8000 足够覆盖正常对话/产物片段，又不至于让单次扫描成为热点。</p>
     */
    private int maxScanChars = 8000;

    /**
     * 附件正文扫描的字符上限（挂点④ 专用，默认 40,000 = {@link #maxScanChars} 的 5 倍）。
     *
     * <p><b>为什么附件要比在线扫描宽</b>：两者的成本约束完全不同——</p>
     * <ul>
     *   <li>在线挂点（①③）跑在<b>请求/装配线程</b>上，扫慢了直接拖慢用户等待，且对话文本本身就短；</li>
     *   <li>附件挂点跑在<b>异步解析线程</b>（{@code chatAttachmentParseExecutor}）上，用户不在等它，
     *       而配置文件动辄几千行、密钥可能落在第 200 行——只扫头 8000 字符等于「大文件必漏」。</li>
     * </ul>
     *
     * <p>窗口内仍然只记前 {@code MAX_HITS_PER_RULE} 条命中、{@link #maxScanChars} 之外的部分
     * 由 {@code truncated} 标记如实呈现，不假装「扫完了没发现」。</p>
     */
    private int attachmentMaxScanChars = 40000;
}
