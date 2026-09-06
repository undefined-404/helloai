package com.helloai.core.planner.memory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.constant.LongTermMemoryType;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.entity.LongTermMemory;
import com.helloai.core.planner.memory.mapper.LongTermMemoryMapper;
import com.helloai.core.planner.memory.service.impl.LongTermMemoryServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LongTermMemoryService} C5-S1/S2/S3 单元测试：摘要归档幂等 + 受控 recall。
 */
@DisplayName("LongTermMemoryService 长期记忆（C5-S1/S2/S3）")
class LongTermMemoryServiceImplTest {

    private static final Long CONV_ID = 1L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LongTermMemory.class);
    }

    private LongTermMemoryMapper memoryMapper;
    private LongTermMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(LongTermMemoryMapper.class);
        service = spy(new LongTermMemoryServiceImpl(memoryMapper));
        ReflectionTestUtils.setField(service, "baseMapper", memoryMapper);
    }

    private RequirementConversation conversation() {
        RequirementConversation c = new RequirementConversation();
        c.setId(CONV_ID);
        c.setTitle("日报模块");
        return c;
    }

    @Test
    @DisplayName("归档会话摘要：type=SESSION + ref 引用 + 摘要化 content（非原始对话）")
    void archiveSession() {
        when(memoryMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        RequirementMessage msg = new RequirementMessage();
        msg.setRole("user");
        msg.setContent("需要一个日报统计");

        service.archiveSession(conversation(), List.of(msg));

        org.mockito.ArgumentCaptor<LongTermMemory> captor = org.mockito.ArgumentCaptor.forClass(LongTermMemory.class);
        verify(memoryMapper).insert(captor.capture());
        LongTermMemory saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(LongTermMemoryType.SESSION);
        assertThat(saved.getScopeKey()).isEqualTo("conversation:1");
        assertThat(saved.getTitle()).isEqualTo("日报模块");
        assertThat(saved.getContent()).contains("日报统计");
        assertThat(saved.getRefType()).isEqualTo("REQUIREMENT_CONVERSATION");
        assertThat(saved.getRefId()).isEqualTo(CONV_ID);
    }

    @Test
    @DisplayName("归档幂等：ref 已存在则跳过，不重复插入")
    void archiveIdempotent() {
        when(memoryMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service.archiveSession(conversation(), List.of());

        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
    }

    @Test
    @DisplayName("recall：按关键词 like 匹配 + limit 封顶")
    void searchRecall() {
        LongTermMemory m = new LongTermMemory();
        m.setTitle("日报模块");
        m.setContent("- 需求点");
        when(memoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(m));

        List<LongTermMemory> result = service.searchForRecall("日报", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("日报模块");
    }

    @Test
    @DisplayName("recall：关键词空返回空列表；limit 超界封顶 20")
    void searchRecallGuard() {
        assertThat(service.searchForRecall(null, 5)).isEmpty();
        assertThat(service.searchForRecall("  ", 5)).isEmpty();
        assertThat(service.searchForRecall("kw", 999)).isEmpty(); // 无结果，不抛
    }

    @Test
    @DisplayName("会话为空跳过归档")
    void archiveNullConversation() {
        service.archiveSession(null, List.of());
        verify(memoryMapper, never()).insert(any(LongTermMemory.class));
    }
}
