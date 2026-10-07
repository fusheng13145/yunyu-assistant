package com.leyon.backend.service;

import com.leyon.backend.entity.UserMemory;
import com.leyon.backend.mapper.UserMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户长期记忆服务单测（v2.85 · C-161，收口候选 ⑫）
 * 覆盖：保存（钳制/空拒绝）、列表、删除归属校验、提示注入形状
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserMemoryServiceTest {

    @Mock
    private UserMemoryMapper userMemoryMapper;

    private UserMemoryService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryService(userMemoryMapper);
    }

    private UserMemory row(String id, String userId, String content) {
        UserMemory m = new UserMemory();
        m.setId(id);
        m.setUserId(userId);
        m.setContent(content);
        return m;
    }

    @Test
    void save_clampsOverlongContent() {
        service.save("u-1", "x".repeat(600));
        ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).hasSize(500);
        assertThat(captor.getValue().getUserId()).isEqualTo("u-1");
    }

    @Test
    void save_blankContentOrUser_throws() {
        assertThatThrownBy(() -> service.save("u-1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(null, "内容"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void delete_onlyOwnMemory() {
        // 他人记忆不可删：归属校验 fail-closed
        when(userMemoryMapper.selectById("m-1")).thenReturn(row("m-1", "other-user", "内容"));
        assertThat(service.delete("m-1", "u-1")).isFalse();
        verify(userMemoryMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyString());
        // 自己的可删
        when(userMemoryMapper.deleteById("m-1")).thenReturn(1);
        assertThat(service.delete("m-1", "other-user")).isTrue();
    }

    @Test
    void formatForPrompt_emptyReturnsEmptyString() {
        when(userMemoryMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.formatForPrompt("u-1")).isEmpty();
    }

    @Test
    void formatForPrompt_listsMemoriesAsPromptSection() {
        when(userMemoryMapper.selectList(any())).thenReturn(List.of(
                row("m-1", "u-1", "用户偏好简洁回答"),
                row("m-2", "u-1", "用户在做 Java 后端")));
        String prompt = service.formatForPrompt("u-1");
        assertThat(prompt).contains("[用户长期记忆]")
                .contains("用户偏好简洁回答")
                .contains("用户在做 Java 后端");
    }
}
