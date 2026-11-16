package com.helloai.core.task.service.impl;

import com.helloai.core.shared.util.UserContext;
import com.helloai.core.task.entity.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@code TaskService#createTask} 的归属语义（PA-1）。
 *
 * <p>人侧消息钩子按 {@code task.owner_id} 投递，无主任务会被有意跳过（防私事件泄漏）；
 * HTTP 创建任务由登录用户发起，必须落归属，否则拆解完成消息永远静默丢失。</p>
 */
class TaskServiceImplCreateOwnerTest {

    private static final long USER_ID = 1000000000000000001L;

    private TaskServiceImpl service() {
        TaskServiceImpl impl = spy(new TaskServiceImpl(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null));
        doReturn(true).when(impl).save(any());
        return impl;
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("有用户态：创建即落归属 owner_id")
    void shouldSetOwnerFromCurrentUser() {
        UserContext.set(USER_ID);
        TaskServiceImpl impl = service();

        impl.createTask("归属用例", null, null, null, null, null);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(impl).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("无用户态（外部通道）：owner 保持 null")
    void shouldKeepOwnerNullWithoutUserContext() {
        TaskServiceImpl impl = service();

        impl.createTask("无主用例", null, null, null, null, null);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(impl).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isNull();
    }
}
