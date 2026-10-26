package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话内「显式技能」指令（2026-10-26）。
 *
 * <p><b>为什么需要</b>：前端「＋→技能」此前只有「方案整理 / 直接拆解」两个<b>流程</b>入口，
 * 而平台技能目录里还有<b>产出型</b>技能（archify / biz-process-lifecycle 等）——
 * 用户在界面上根本选不到。要让「选一个技能 → 说一句话 → 拿到可下载产物」成立，
 * 需要在对话消息上携带「本轮用哪个技能包」这一事实。</p>
 *
 * <p><b>为什么用消息前缀而不是新增请求字段</b>：{@code /task} 已是同款做法（命令前缀随消息落库、
 * 前端气泡只展示用户原文），复用同一条通道意味着 controller / service / 流式通道
 * 全部零签名改动，且技能选择天然可回溯（建任务时从历史消息里就能取回）。
 * 前缀 {@code [skill:name]} 对用户不可见——前端发送前拼、后端落库前剥。</p>
 *
 * <p><b>与 {@code required_skills} 的关系</b>：解析出的技能名一路进
 * {@code task.required_skills}，由既有技能注入链（{@code AgentSkillSpecService}）在子任务执行时
 * 展开为「规范正文 + requiredTools」，执行者据此调 {@code internal_run_skill} 跑技能 CLI，
 * 再用 {@code internal_export_artifact} 把产物登记为附件。本类只负责<b>识别与渲染</b>，
 * 不执行、不建任务。</p>
 *
 * <p><b>不编造</b>：技能不存在 / 正文读不到时返回空段（不注入任何「规范」），
 * 让本轮退回普通对话，而不是让模型按想象执行一个平台并不支持的技能。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillDirective {

    /** 消息前缀：{@code [skill:archify] 画个支付流程图}（关键字大小写不敏感，锚定消息开头）。 */
    private static final Pattern PREFIX = Pattern.compile(
            "^\\[skill:([A-Za-z0-9][A-Za-z0-9._-]*)]\\s*", Pattern.CASE_INSENSITIVE);

    /** 技能正文注入上限（字符）：技能 md 可达万级，全文塞进 prompt 会挤掉对话历史。 */
    static final int BODY_LIMIT = 6000;

    /** 依赖展开深度上限（防目录里出现环）。 */
    private static final int MAX_DEPENDENCY_DEPTH = 3;

    private final AgentSkillSpecService agentSkillSpecService;

    private final ObjectMapper objectMapper;

    /** 消息 payload 里承载技能名的键（与 options / questions 等既有键共存，互不冲突）。 */
    public static final String PAYLOAD_KEY = "skill";

    /**
     * 解析结果。
     *
     * @param skillName 技能包名；无前缀时 {@code null}
     * @param text      剥离前缀后的用户原文（无前缀时与入参一致）
     */
    public record Parsed(String skillName, String text) {

        /** 是否带了显式技能。 */
        public boolean hasSkill() {
            return skillName != null && !skillName.isBlank();
        }
    }

    /** 解析消息前缀（幂等：无前缀原样返回）。 */
    public Parsed parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Parsed(null, raw == null ? "" : raw);
        }
        Matcher m = PREFIX.matcher(raw);
        if (!m.find()) {
            return new Parsed(null, raw);
        }
        String name = m.group(1);
        String rest = raw.substring(m.end());
        return new Parsed(name, rest.trim().isEmpty() ? "" : rest.trim());
    }

    /** 是否带显式技能前缀（流式通道据此拒绝，与 {@code /task} 同口径）。 */
    public boolean isSkillCommand(String raw) {
        return parse(raw).hasSkill();
    }

    /**
     * 把技能名写进消息 payload（保留原有字段）。
     *
     * <p>为什么不落一张「会话技能」表：技能是<b>一句消息</b>的属性（本轮选它，下一轮可能换），
     * 挂在消息 payload 上与 {@code /task} 的「命令随消息走」同构，且建任务时能沿历史回溯到最近一次选择。</p>
     *
     * @param payload   原 payload（可为 null / 空）
     * @param skillName 技能名（空则原样返回）
     * @return 合并后的 JSON 串
     */
    public String mergePayload(String payload, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return payload;
        }
        try {
            ObjectNode node = payload == null || payload.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readValue(payload, ObjectNode.class);
            if (node == null) {
                node = objectMapper.createObjectNode();
            }
            node.put(PAYLOAD_KEY, skillName);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("技能名合并进消息 payload 失败（保留原 payload）: skill={}, err={}", skillName, e.getMessage());
            return payload;
        }
    }

    /**
     * 从消息 payload 里读技能名。
     *
     * @param payload 消息 payload（可为 null）
     * @return 技能名；无 / 非法 JSON / 字段缺失均返回 {@code null}
     */
    public String skillOfPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(payload);
            if (node == null || !node.hasNonNull(PAYLOAD_KEY)) {
                return null;
            }
            String value = node.get(PAYLOAD_KEY).asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 渲染本轮技能注入段（追加到 prompt 末尾）。
     *
     * @param skillName 技能包名
     * @return Markdown 段；技能不存在 / 正文为空时返回 {@code ""}（不注入）
     */
    public String renderInstruction(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return "";
        }
        SkillPackage spec = agentSkillSpecService.getPackage(skillName);
        if (spec == null) {
            log.warn("显式技能未在目录中：{}（本轮不注入技能规范，退回普通对话）", skillName);
            return "";
        }
        String body = agentSkillSpecService.loadBody(skillName);
        if (body == null || body.isBlank()) {
            log.warn("显式技能正文不可读：{}（本轮不注入技能规范）", skillName);
            return "";
        }
        String trimmed = body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) + "\n\n（技能正文过长，已截断）" : body;
        StringBuilder sb = new StringBuilder(trimmed.length() + 700);
        sb.append("\n\n## 本轮显式技能（用户已在界面上选定）\n\n");
        sb.append("技能包：`").append(spec.name()).append("` @").append(spec.version()).append('\n');
        if (!spec.description().isBlank()) {
            sb.append("用途：").append(spec.description()).append('\n');
        }
        sb.append("\n以下是该技能的执行规范正文，**本轮的回答与终稿必须遵守**").append("；")
                .append("若与上文「输出形态」约束冲突，以本段为准：\n\n");
        sb.append("<<<技能正文开始>>>\n").append(trimmed).append("\n<<<技能正文结束>>>\n\n");
        sb.append("【落地口径（平台事实，不要另编）】\n");
        sb.append("1. 本轮终稿的交付物形态按技能规范写清楚（文件名 / 格式 / 校验方式），让执行者一次做对。\n");
        sb.append("2. 建任务时该技能会写入 required_skills，子任务执行者可用 `internal_run_skill` 在容器内运行技能 CLI，")
                .append("并用 `internal_export_artifact` 把产物登记为可下载附件。\n");
        sb.append("3. 不要声称「已生成文件 / 已渲染 / 已校验」——产物由执行阶段产生，本轮的职责是把规范落到终稿。\n");
        return sb.toString();
    }

    /**
     * 技能名 + 其依赖包（递归、去重、保持「依赖在前」顺序）。
     *
     * <p>为什么带依赖：{@code biz-process-lifecycle} 只写「状态机该怎么建」，
     * 真正的渲染靠依赖包 {@code archify}。只传自己的话，执行者拿到了建模纪律却没有 CLI。</p>
     *
     * @param skillName 技能包名
     * @return 技能标签列表；不存在时返回空列表（调用方据此不写 required_skills）
     */
    public List<String> requiredSkillsWithDependencies(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return List.of();
        }
        if (agentSkillSpecService.getPackage(skillName) == null) {
            log.warn("显式技能不存在，跳过 required_skills 注入：{}", skillName);
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        collect(skillName, ordered, 0);
        return new ArrayList<>(ordered);
    }

    private void collect(String name, Set<String> acc, int depth) {
        if (name == null || name.isBlank() || depth > MAX_DEPENDENCY_DEPTH || acc.contains(name)) {
            return;
        }
        SkillPackage spec = agentSkillSpecService.getPackage(name);
        if (spec == null) {
            log.warn("技能依赖未在目录中，已忽略：{}（被依赖方深度 {}）", name, depth);
            return;
        }
        for (String dep : spec.dependencies()) {
            collect(dep, acc, depth + 1);
        }
        acc.add(name);
    }
}
