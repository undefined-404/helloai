package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.SkillPackageExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ChatAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 对话内技能执行（2026-10-27）：用户选一个产出型技能 → 当场跑出文件 → 落成会话附件。
 *
 * <p><b>为什么不是「建任务派子任务」</b>：那是 `/task` 的语义（把需求变成可交付的多步计划）。
 * 用户选 archify 说「画一张支付生命周期图」，要的是<b>这一轮就拿到图</b>，不是拿到一个待拆解的任务。
 * 子任务路径还会引入 PLANNER 拆解、执行者选人、评审等环节——对「画一张图」全是噪声。</p>
 *
 * <p><b>为什么是两段式而不是工具循环</b>：对话走流式通道，而平台已裁定流式面不进工具循环
 * （{@code PlatformAgentExecutionService#executeStream} 注释）。技能执行本身是<b>确定性</b>的
 * ——模型只需要产出一次结构化输入，剩下的校验/渲染/回读都由平台做，天然不需要多轮工具交互。</p>
 *
 * <p><b>失败处理只做一次自修复</b>：{@code validate} 失败通常是某个必填字段写法不对，
 * 把 CLI 的错误输出回给模型重产一次，成功率明显提升；仍失败就如实报错并附首条错误——
 * 无限重试会把一次「模型没理解 schema」拖成几十秒的空转。</p>
 *
 * <p><b>作用域</b>：沙箱槽键用 {@code -conversationId}（会话 ID 取负）：子任务 ID 是雪花正数，
 * 负数不会撞车，且同一个会话的两次技能执行会串行复用同一个工作区（包注入幂等）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSkillRunner {

    /** 技能产物输出目录（与技能正文里的约定一致）。 */
    private static final String OUT_DIR = "/workspace/out";

    private static final String SPEC_PATH = OUT_DIR + "/spec.json";

    private static final String HTML_PATH = OUT_DIR + "/artifact.html";

    /** 单次产物上限：与对话框内联预览的 5MB 同一把尺子（更大的产物既传不动也看不了）。 */
    private static final int MAX_ARTIFACT_BYTES = 5 * 1024 * 1024;

    /** 一次执行 + 一次自修复。 */
    private static final int MAX_SPEC_ATTEMPTS = 2;

    /** 命令超时：冷启动渲染实测约 15s，给足余量但不放任卡死。 */
    private static final int COMMAND_TIMEOUT_SECONDS = 180;

    /** 诊断轮 scene 标识（不进业务面循环白名单，走单次调用）。 */
    private static final String SCENE = "conversation_skill";

    /** 技能正文注入上限（与 {@code SkillDirective} 同一口径）。 */
    private static final int BODY_LIMIT = 6000;

    private final AgentSkillSpecService agentSkillSpecService;

    private final SkillPackageExecutionService skillPackageExecutionService;

    private final SkillSpecExtractor specExtractor;

    private final PlatformAgentExecutionService platformAgentExecutionService;

    private final PlannerAgentPicker plannerAgentPicker;

    private final ChatAttachmentService chatAttachmentService;

    private final ObjectMapper objectMapper;

    /** 读技能包内的 schema 文件（classpath 资源；拿不到就跳过注入，不阻断执行）。 */
    private final ResourceLoader resourceLoader;

    /**
     * 执行结果。
     *
     * @param attachmentId 产物附件 ID（前端据此下载 / 预览）
     * @param fileName     产物文件名
     * @param fileSize     字节数
     * @param sha256       内容哈希（与 CLI 回执可对账）
     * @param chartType    实际使用的图表类型
     */
    public record Result(Long attachmentId, String fileName, long fileSize, String sha256, String chartType) {
    }

    /**
     * 跑一次技能，产出文件并登记为会话附件。
     *
     * @param conversationId 会话 ID（产物归属 + 沙箱作用域）
     * @param plannerAgentId 指定的 planner（可空 = 系统自动选）
     * @param uploaderUserId 生成者用户 ID（可空）
     * @param skillName      技能包名（如 {@code archify}）
     * @param userNeed       用户原话（技能输入的唯一依据）
     * @return 产物信息
     * @throws BizException 技能不可用 / 模型抽不出输入 / 渲染失败 / 产物读不回
     */
    public Result run(Long conversationId, Long plannerAgentId, Long uploaderUserId,
                      String skillName, String userNeed) {
        if (conversationId == null) {
            throw new BizException("缺少会话上下文，无法执行技能");
        }
        if (!skillPackageExecutionService.isAvailable(skillName)) {
            throw new BizException("技能不可用（未入库或沙箱未启用）: " + skillName);
        }
        Long scopeId = -conversationId;
        try {
            SkillSpecExtractor.ChartSpec spec = produceSpec(conversationId, plannerAgentId, skillName, userNeed);
            SkillPackageExecutionService.RunResult runResult = runRender(scopeId, skillName, spec);
            if (!runResult.ok()) {
                // 完整 CLI 输出进日志（1357 字符级别，落盘可追）——只留 firstLine 会把 JSON
                // 错误 receipt 裁成孤零零一个「{」，用户看不到真正的 error/diagnostics。
                log.warn("对话内技能渲染失败: conversationId={}, skill={}, chartType={}, exitCode={}, timedOut={}, output={}",
                        conversationId, skillName, spec.chartType(), runResult.exitCode(),
                        runResult.timedOut(), runResult.output());
                throw new BizException("技能渲染失败（" + skillName + "）：" + firstLine(runResult.output()));
            }
            SkillPackageExecutionService.Artifact artifact =
                    skillPackageExecutionService.readArtifact(scopeId, HTML_PATH, MAX_ARTIFACT_BYTES);
            if (artifact == null) {
                throw new BizException("产物读不回：命令自报成功但 " + HTML_PATH + " 不可读");
            }
            if (artifact.truncated()) {
                throw new BizException("产物超过单文件上限（" + MAX_ARTIFACT_BYTES + " 字节），拒绝交付被截断的文件");
            }
            String fileName = fileNameOf(spec.chartType());
            ChatAttachment attachment = chatAttachmentService.saveArtifact(
                    conversationId, uploaderUserId, fileName, "text/html", artifact.bytes());
            log.info("对话内技能执行完成: conversationId={}, skill={}, chartType={}, fileName={}, size={}, sha256={}, attachmentId={}",
                    conversationId, skillName, spec.chartType(), fileName, artifact.bytes().length,
                    artifact.sha256(), attachment.getId());
            return new Result(attachment.getId(), fileName, artifact.bytes().length,
                    artifact.sha256(), spec.chartType());
        } finally {
            // 对话轮没有「turn 结束」钩子，必须自己释放；否则容器活到空闲超时
            skillPackageExecutionService.release(scopeId);
        }
    }

    /**
     * 产物消息的 payload（前端据此渲染「产物卡片」）。
     *
     * <p>协议：{@code {"type":"artifact","artifact":{...}}}。与结构化追问的 {@code type} 字段同名不冲突——
     * 前端按 {@code type} 分派渲染器，未知类型一律降级为纯文本。</p>
     *
     * @param result 执行结果
     * @return JSON 串；序列化失败返回 {@code null}（消息退化为纯文本，不阻断落库）
     */
    public String payloadOf(Result result) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "artifact",
                    "artifact", Map.of(
                            "attachmentId", String.valueOf(result.attachmentId()),
                            "fileName", result.fileName(),
                            "fileSize", result.fileSize(),
                            "sha256", result.sha256() == null ? "" : result.sha256(),
                            "chartType", result.chartType() == null ? "" : result.chartType())));
        } catch (Exception e) {
            log.warn("产物消息 payload 序列化失败（消息退化为纯文本）: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 让模型按技能规范产出结构化输入（失败时带错误自修复一次）。
     */
    private SkillSpecExtractor.ChartSpec produceSpec(Long conversationId, Long plannerAgentId,
                                                     String skillName, String userNeed) {
        String body = agentSkillSpecService.loadBody(skillName);
        if (body == null || body.isBlank()) {
            throw new BizException("技能正文不可读，无法构造输入: " + skillName);
        }
        String trimmedBody = body.length() > BODY_LIMIT
                ? body.substring(0, BODY_LIMIT) + "\n\n（技能正文过长，已截断）" : body;
        // schema 注入是成功率的关键：技能正文只说「按 schemas/<type>.schema.json 写」，
        // 模型看不到那份文件就只能凭印象编字段，validate 必然失败。
        List<String> guessedTypes = guessTypes(userNeed);
        String schemaSection = schemaSection(skillName, guessedTypes);
        String basePrompt = specPrompt(trimmedBody, guessedTypes, schemaSection, userNeed);
        String prompt = basePrompt;
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_SPEC_ATTEMPTS; attempt++) {
            String output = callLlm(conversationId, plannerAgentId, prompt);
            try {
                return specExtractor.extract(output);
            } catch (SkillSpecExtractor.SpecExtractException e) {
                lastError = e.getMessage();
                log.warn("技能输入抽取失败（第 {}/{} 次）: skill={}, reason={}",
                        attempt, MAX_SPEC_ATTEMPTS, skillName, lastError);
                // 下一轮把失败原因与原始输出一起回给模型：只说「再试一次」它多半会重复同一个错
                prompt = basePrompt + "\n\n【上一次的输出不合格】\n原因：" + lastError
                        + "\n上一次输出（仅供定位问题，不要照抄）：\n" + brief(output)
                        + "\n请重新输出，只给一个合法 JSON 对象。";
            }
        }
        throw new BizException("未能从模型输出里得到可用的图表输入：" + lastError);
    }

    /**
     * 跑「写 spec → validate → deliver」一条命令。
     *
     * <p>写文件用 base64 而不是 heredoc：spec 里必然有引号与花括号，heredoc 的分隔符选择、
     * shell 变量展开都会引入转义问题；base64 的字符集只有 {@code A-Za-z0-9+/=}，放进单引号里恒定安全。</p>
     */
    private SkillPackageExecutionService.RunResult runRender(Long scopeId, String skillName,
                                                             SkillSpecExtractor.ChartSpec spec) {
        String base64 = Base64.getEncoder().encodeToString(spec.specJson().getBytes(StandardCharsets.UTF_8));
        String command = "mkdir -p " + OUT_DIR
                + " && printf '%s' '" + base64 + "' | base64 -d > " + SPEC_PATH
                + " && node bin/archify.mjs validate " + spec.chartType() + " " + SPEC_PATH + " --json"
                + " && node bin/archify.mjs deliver " + spec.chartType() + " " + SPEC_PATH + " " + HTML_PATH
                + " --json --quality showcase";
        return skillPackageExecutionService.run(skillName, scopeId, command, COMMAND_TIMEOUT_SECONDS);
    }

    /** 调 planner 产输入（单次同步，不进流式、不进工具循环）。 */
    private String callLlm(Long conversationId, Long plannerAgentId, String prompt) {
        AgentProfileSnapshot planner = plannerAgentPicker.pick(plannerAgentId);
        AgentTask task = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId, "scene", SCENE))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), task);
        if (!result.isSuccess()) {
            throw new BizException("技能输入生成失败（LLM）：" + result.getErrorMessage());
        }
        return result.getOutput();
    }

    /**
     * 输入产出提示词。
     *
     * <p>为什么把「输出契约」写在技能正文之外：技能正文是给人/模型读的完整手册（含 CLI 用法、
     * 失败对策、验收标准），但本链路只需要其中的<b>输入结构</b>。契约单列能覆盖正文里
     * 「用 internal_export_artifact 导出」这类对话轮并不存在的步骤，避免模型去调不存在的工具。</p>
     *
     * <p><b>2026-10-27 关键约束显式化</b>：模型读 2 万字 schema 抓不住必填字段，真实失败里
     * 最典型的是 workflow 节点 {@code type} 填了动作词（action/task/step），而 archify 的
     * 枚举是组件类型（frontend/backend/...）。把「最容易错的三条」在契约段点名，
     * 而不是让模型自己从 schema 里找——schema 仍注入做兜底，但契约段是主参照。</p>
     */
    private static String specPrompt(String skillBody, List<String> guessedTypes,
                                     String schemaSection, String userNeed) {
        return """
                你要为一次「图表渲染」准备输入数据。平台会把你输出的 JSON 直接交给渲染 CLI，
                所以**只输出 JSON**，不要解释、不要 Markdown 围栏、不要任何多余文字。

                ## 输出契约（必须严格遵守）
                {"chartType":"<类型>","spec":{ … }}

                - chartType 只能取：architecture / workflow / sequence / dataflow / lifecycle /
                  erd / tree / class / timeline / waterfall
                - spec 的结构必须符合下方 schema（缺必填字段会直接渲染失败）
                - 用户描述的是「状态机 / 生命周期 / 状态流转」时用 lifecycle；
                  是「业务流程与决策分支」时用 workflow；是「谁调谁、消息往返」时用 sequence
                - 不要输出 html、不要输出命令、不要调用任何工具

                ## 最容易踩的坑（渲染前必查，比 schema 全文更重要）
                %s

                ## 类型初判（仅供你参考，最终以你的判断为准）
                按用户描述，最可能是：%s

                ## 输入 schema（字段名与层级必须与之一致）
                %s

                ## 技能规范正文（讲了怎么用 CLI 与常见坑，写 spec 时不必照抄其步骤）
                %s

                ## 用户需求
                %s
                """.formatted(pitfallsSection(guessedTypes),
                String.join(" / ", guessedTypes),
                schemaSection.isBlank() ? "（该技能包未提供 schema 文件，按下文规范自行组织字段）" : schemaSection,
                skillBody, userNeed);
    }

    /**
     * 按初判类型输出「最容易踩的坑」段（显式点名枚举与必填，模型不用去长 schema 里找）。
     * 命中不到已知类型时给通用兜底提示。
     */
    private static String pitfallsSection(List<String> guessedTypes) {
        boolean workflow = guessedTypes.contains("workflow");
        boolean architecture = guessedTypes.contains("architecture");
        boolean lifecycle = guessedTypes.contains("lifecycle");
        boolean sequence = guessedTypes.contains("sequence");
        StringBuilder sb = new StringBuilder();
        if (workflow) {
            sb.append("workflow 类型（用户描述含流程/决策/审批时必读）：\n")
                    .append("- 顶层必填：schema_version（取 1 或 2）、diagram_type（必须恰为 \"workflow\"）、meta（必含 title 与 output）、lanes、nodes、edges\n")
                    .append("- 每个节点必填 id / lane / col / type / label；id 只能字母开头（数字或中文开头的 id 校验不过）\n")
                    .append("- **node.type 是组件枚举，不是动作词**：只能取 frontend / backend / database / cloud / security / messagebus / external——\n")
                    .append("  把流程步骤写进 type（如 \"action\"/\"task\"/\"step\"）会直接校验失败；步骤名应放 label，类型按执行方选组件\n")
                    .append("- 每条泳道（lane）必填 id 与 label；每条边（edge）必填 from 与 to（引用节点 id）\n")
                    .append("- 用户要求「泳道」时 lanes 必须有多个；「成功主路径」用 mainPath 列出主链节点 id\n");
        }
        if (architecture) {
            sb.append("architecture 类型（用户描述含架构/模块/依赖时必读）：\n")
                    .append("- 组件 type 同样是组件枚举（frontend / backend / database / cloud / security / messagebus / external），不要造新词\n")
                    .append("- id 只能字母开头，数字/中文开头校验不过；依赖关系写在 edges（from/to 引用组件 id）\n");
        }
        if (lifecycle) {
            sb.append("lifecycle 类型（用户描述含状态机/状态流转时必读）：\n")
                    .append("- 状态节点与迁移按 schema 的 states/transitions 结构填，字段名与 schema 一致\n")
                    .append("- 迁移的 from/to 必须引用已定义的状态 id\n");
        }
        if (sequence) {
            sb.append("sequence 类型（用户描述含调用链/消息往返时必读）：\n")
                    .append("- 参与者/消息按 schema 的 participants/messages 结构填，消息 from/to 引用参与者 id\n");
        }
        if (sb.length() == 0) {
            sb.append("- 严格按注入的 schema 填必填字段；id 一律字母开头（数字/中文开头校验不过）；枚举字段只取 schema 列出的值，不发明新值\n");
        }
        return sb.toString();
    }

    /**
     * 从用户原话初判图表类型（平台侧启发式）。
     *
     * <p><b>只为一件事服务</b>：决定把哪几份 schema 注入 prompt。模型仍可推翻这个初判——
     * 它输出的 {@code chartType} 才是最终事实。所以这里宁可多命中一两类（注入 2 份），
     * 也不要为了「精准」而漏掉真正需要的那份：漏了 schema 等于让模型盲写字段。</p>
     *
     * <p>命中不到任何关键词时退化为「架构 / 流程」两张最通用的。</p>
     */
    private static List<String> guessTypes(String need) {
        String text = need == null ? "" : need.toLowerCase();
        List<String> hits = new ArrayList<>();
        if (containsAny(text, "状态机", "生命周期", "状态流转", "状态迁移", "lifecycle", "state machine", "支付", "订单状态")) {
            hits.add("lifecycle");
        }
        if (containsAny(text, "流程", "审批", "分支", "决策", "workflow", "bpmn")) {
            hits.add("workflow");
        }
        if (containsAny(text, "架构", "模块", "依赖", "微服务", "architecture", "组件")) {
            hits.add("architecture");
        }
        if (containsAny(text, "时序", "调用链", "消息往返", "sequence", "交互顺序")) {
            hits.add("sequence");
        }
        if (containsAny(text, "表结构", "实体关系", "数据库设计", "erd", "er 图")) {
            hits.add("erd");
        }
        if (containsAny(text, "数据流", "dataflow", "数据流转", "etl")) {
            hits.add("dataflow");
        }
        if (containsAny(text, "目录", "组织架构", "分类", "tree", "层级")) {
            hits.add("tree");
        }
        if (containsAny(text, "类图", "class diagram", "继承", "接口关系")) {
            hits.add("class");
        }
        if (containsAny(text, "时间轴", "里程碑", "timeline")) {
            hits.add("timeline");
        }
        if (containsAny(text, "耗时", "阶段耗时", "waterfall", "瀑布")) {
            hits.add("waterfall");
        }
        if (hits.isEmpty()) {
            hits.add("architecture");
            hits.add("workflow");
        }
        // 最多注入两份：三份 schema 加起来 20KB+，prompt 过长反而让模型抓不住重点
        return hits.size() > 2 ? hits.subList(0, 2) : hits;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读技能包内的 schema 文件拼成 prompt 段。
     *
     * <p><b>路径按「技能包名 + 版本」拼</b>而不是写死 {@code archify/3.1.0}：升级技能包时
     * 不需要改代码。包内没有 {@code schemas/} 目录（不是数据驱动型技能）时返回空串，
     * prompt 退化为「按正文自行组织字段」——不阻断执行。</p>
     */
    private String schemaSection(String skillName, List<String> types) {
        var spec = agentSkillSpecService.getPackage(skillName);
        if (spec == null || spec.version().isBlank()) {
            return "";
        }
        String base = "classpath:skills/packages/" + spec.name() + "/" + spec.version() + "/schemas/";
        StringBuilder sb = new StringBuilder();
        appendSchema(sb, base + "common.schema.json", "common.schema.json");
        for (String type : types) {
            appendSchema(sb, base + type + ".schema.json", type + ".schema.json");
        }
        return sb.toString();
    }

    private void appendSchema(StringBuilder sb, String location, String label) {
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                return;
            }
            String text;
            try (var in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            sb.append("### ").append(label).append('\n').append(text).append("\n\n");
        } catch (Exception e) {
            log.debug("schema 读取失败（跳过该份注入）: location={}, err={}", location, e.getMessage());
        }
    }

    /**
     * 产物文件名：类型 + 时间戳。
     *
     * <p>不用用户原话做文件名：中文 + 标点在不同文件系统上的清洗规则不一致，
     * 而 {@code lifecycle-20261027-0112.html} 这种名字在下载列表里同样可读、且天然不重名。</p>
     */
    private static String fileNameOf(String chartType) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        return chartType + "-" + stamp + ".html";
    }

    /**
     * 取输出的首行（CLI 报错常常多行，消息里只放最关键的一句）。
     *
     * <p><b>2026-10-27 改进</b>：archify 的 {@code --json} 失败输出是一份格式化的 JSON receipt
     * （{@code {"schemaVersion":1,"ok":false,"error":...,"diagnostics":[...]}}），直接取首行
     * 只会得到孤零零的「{」。这里先尝试解析 receipt：命中 {@code error} 或首条
     * {@code diagnostics[].message} 就展示它们；解析不出（普通文本输出）再回落首行。</p>
     */
    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "（无输出）";
        }
        String trimmed = output.strip();
        if (trimmed.startsWith("{")) {
            String receiptSummary = receiptErrorSummary(trimmed);
            if (receiptSummary != null) {
                return receiptSummary.length() > 300 ? receiptSummary.substring(0, 300) + "…" : receiptSummary;
            }
        }
        String[] lines = trimmed.split("\\R");
        String first = lines.length == 0 ? trimmed : lines[0];
        return first.length() > 300 ? first.substring(0, 300) + "…" : first;
    }

    /** 从 CLI {@code --json} 错误 receipt 里提取可读错误摘要（error 或首条 diagnostics 的 message）。 */
    private static String receiptErrorSummary(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(json);
            String error = root.path("error").asText(null);
            var diagnostics = root.path("diagnostics");
            String diagMessage = null;
            if (diagnostics.isArray() && !diagnostics.isEmpty()) {
                diagMessage = diagnostics.get(0).path("message").asText(null);
            }
            if (error != null && !error.isBlank()) {
                return diagMessage != null && !diagMessage.isBlank()
                        ? error + " | " + diagMessage
                        : error;
            }
            if (diagMessage != null && !diagMessage.isBlank()) {
                return diagMessage;
            }
        } catch (Exception ignored) {
            // 不是合法 JSON（普通文本输出），回落首行逻辑
        }
        return null;
    }

    private static String brief(String output) {
        if (output == null) {
            return "";
        }
        String text = output.strip();
        return text.length() > 800 ? text.substring(0, 800) + "…" : text;
    }
}
