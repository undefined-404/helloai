package com.helloai.core.planner.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.LongTermMemoryType;
import com.helloai.core.planner.memory.UserProfileExtractor;
import com.helloai.core.planner.memory.entity.LongTermMemory;
import com.helloai.core.planner.memory.entity.UserProfile;
import com.helloai.core.planner.memory.mapper.LongTermMemoryMapper;
import com.helloai.core.planner.memory.mapper.UserProfileMapper;
import com.helloai.core.planner.memory.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户级画像 / USER 记忆服务实现（PA-2c）。
 *
 * <p><b>性能隔离</b>：{@code @Async("appEventExecutor")} 从对话主链路解耦——抽取与落库不进
 * 用户请求线程，不增加对话延迟；但因此<b>不携带 {@link com.helloai.core.shared.util.UserContext}
 * 与 Sa-Token 上下文</b>，userId 必须在触发点（同步线程）取好传参。</p>
 *
 * <p>best-effort：抽取/落库任何异常只记日志，绝不向调用方抛（对话主链路不受影响）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryServiceImpl implements UserMemoryService {

    private final UserProfileMapper userProfileMapper;
    private final LongTermMemoryMapper longTermMemoryMapper;

    private static final String SOURCE_RULE = "RULE";

    @Override
    @Async("appEventExecutor")
    public void triggerFromMessage(Long userId, String userMessage) {
        if (userId == null) {
            return; // 画像必须挂人，挂了上才写
        }
        try {
            Map<String, Object> facts = UserProfileExtractor.extract(userMessage);
            if (facts.isEmpty()) {
                return; // 无显式偏好句式，本轮不落画像（不刷版本）
            }
            Map<String, Object> merged = mergeProfile(userId, facts);
            recordUserMemory(userId, merged);
        } catch (Exception e) {
            log.warn("用户画像抽取失败（不阻断主链路）: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 结构化画像合并 + 时间戳覆盖（超越 QwenPaw 纯 Markdown 无校验短板）。
     *
     * <p>按 {@code user_id} 找现存画像；字段级覆盖：同 key 以新抽取值替换，不同 key 保留累计。
     * 每次有实质变更则续读-更新（读改写，无并发写场景——每轮独立触发，冲突概率极低）并
     * {@code version + 1}；无现存行则新建。{@code source} 标记抽取途径。</p>
     *
     * @return 合并后的完整画像（供 USER 记忆按全量重建）
     */
    private Map<String, Object> mergeProfile(Long userId, Map<String, Object> facts) {
        UserProfile existing = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            UserProfile p = new UserProfile();
            p.setUserId(userId);
            p.setProfileJson(new LinkedHashMap<>(facts));
            p.setSource(SOURCE_RULE);
            p.setVersion(1);
            userProfileMapper.insert(p);
            log.info("用户画像新建: userId={}, fields={}", userId, facts.keySet());
            return new LinkedHashMap<>(facts);
        }
        Map<String, Object> merged = existing.getProfileJson() != null
                ? new LinkedHashMap<>(existing.getProfileJson()) : new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            Object incoming = e.getValue();
            Object cur = merged.get(e.getKey());
            // 偏好列表按并集累计（去重）；其余字段按 key 覆盖（新值即最新事实）
            List<Object> union = unionIfLists(cur, incoming);
            if (union != null) {
                if (!union.equals(cur)) {
                    merged.put(e.getKey(), union);
                    changed = true;
                }
                continue;
            }
            if (!merged.containsKey(e.getKey()) || !Objects.equals(cur, incoming)) {
                merged.put(e.getKey(), incoming);
                changed = true;
            }
        }
        if (!changed) {
            return merged; // 无实质变化，不刷版本
        }
        existing.setProfileJson(merged);
        existing.setVersion((existing.getVersion() == null ? 0 : existing.getVersion()) + 1);
        userProfileMapper.updateById(existing);
        log.debug("用户画像合并: userId={}, version={}, changedKeys={}",
                userId, existing.getVersion(), facts.keySet());
        return merged;
    }

    /**
     * USER 型长期记忆精选子集 upsert：以 {@code owner_id=userId} 建一条精选记忆，
     * 内容为合并后的完整画像；后续覆盖同一条记录（保持「每用户一条精选」）。
     */
    private void recordUserMemory(Long userId, Map<String, Object> facts) {
        LongTermMemory existing = longTermMemoryMapper.selectOne(
                new LambdaQueryWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getType, LongTermMemoryType.USER)
                        .eq(LongTermMemory::getOwnerId, userId)
                        .last("LIMIT 1"));
        if (existing == null) {
            LongTermMemory m = new LongTermMemory();
            m.setType(LongTermMemoryType.USER);
            m.setScopeKey("user:" + userId);
            m.setTitle("用户画像");
            m.setContent(renderFacts(facts));
            m.setTag(renderTag(facts));
            m.setRefType("USER_PROFILE");
            m.setRefId(userId);
            m.setMemoryTime(OffsetDateTime.now());
            m.setOwnerId(userId);
            longTermMemoryMapper.insert(m);
            return;
        }
        // 按合并后的完整画像重建，确保新偏好能进入精选记忆（而非只刷新时间戳）
        existing.setContent(renderFacts(facts));
        existing.setTag(renderTag(facts));
        existing.setMemoryTime(OffsetDateTime.now());
        longTermMemoryMapper.updateById(existing);
    }

    /** 两侧都是列表时返回并集（保序去重）；否则返回 null 表示按标量覆盖。 */
    private static List<Object> unionIfLists(Object cur, Object incoming) {
        if (!(cur instanceof List<?> curList) || !(incoming instanceof List<?> inList)) {
            return null;
        }
        List<Object> union = new ArrayList<>(curList);
        for (Object v : inList) {
            if (!union.contains(v)) {
                union.add(v);
            }
        }
        return union;
    }

    private static String renderFacts(Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder();
        facts.forEach((k, v) -> sb.append('-').append(k).append(": ").append(v).append('\n'));
        return sb.toString().trim();
    }

    private static String renderTag(Map<String, Object> facts) {
        List<?> prefs = facts.get(UserProfileExtractor.KEY_PREFERENCES) instanceof List<?> l ? l : List.of();
        if (!prefs.isEmpty()) {
            String joined = String.join(",", prefs.stream().map(String::valueOf).toList());
            return joined.length() <= 128 ? joined : joined.substring(0, 128);
        }
        Object style = facts.get(UserProfileExtractor.KEY_STYLE);
        return style != null ? String.valueOf(style) : "user-profile";
    }
}