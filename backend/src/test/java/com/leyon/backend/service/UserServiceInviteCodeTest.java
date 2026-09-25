package com.leyon.backend.service;

import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册的邀请码闸门测试（v2.37）
 * 覆盖：invite 模式缺码即拒、领取失败绝不建号、领取与建号用同一 userId（审计链闭合）、
 * 用户名重复不得烧掉一个可用码、open 模式忽略邀请码
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class UserServiceInviteCodeTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private InviteCodeService inviteCodeService;

    private UserService userService(boolean inviteRequired) {
        lenient().when(inviteCodeService.inviteRequired()).thenReturn(inviteRequired);
        return new UserService(userMapper, jwtUtil, loginAttemptService, inviteCodeService);
    }

    @Test
    void register_withoutCodeInInviteMode_rejectedAndNothingWritten() {
        UserService service = userService(true);
        when(userMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.register("alice", "abc12345", "  "))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("邀请码");

        verify(inviteCodeService, never()).claim(anyString(), anyString());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_whenClaimFails_doesNotCreateUser() {
        UserService service = userService(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(inviteCodeService.claim(eq("BADCODE123"), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.register("alice", "abc12345", "BADCODE123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("邀请码");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_claimsCodeForTheSameUserItInserts() {
        UserService service = userService(true);
        when(inviteCodeService.claim(eq("GOOD123456"), anyString())).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);

        User created = service.register("alice", "abc12345", "GOOD123456");

        ArgumentCaptor<String> claimedFor = ArgumentCaptor.forClass(String.class);
        verify(inviteCodeService).claim(eq("GOOD123456"), claimedFor.capture());
        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        // 领取记录里的 used_by 必须就是新建账号的 id，否则管理端"谁用的这个码"不可追溯
        assertThat(claimedFor.getValue()).isEqualTo(created.getId());
        assertThat(inserted.getValue().getId()).isEqualTo(created.getId());
        assertThat(created.getPassword()).isNull();
    }

    @Test
    void register_duplicateUsername_doesNotBurnCode() {
        UserService service = userService(true);
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.register("alice", "abc12345", "GOOD123456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("用户名已存在");

        verify(inviteCodeService, never()).claim(anyString(), anyString());
    }

    @Test
    void register_inOpenMode_ignoresInviteCode() {
        UserService service = userService(false);
        when(userMapper.selectCount(any())).thenReturn(0L);

        User created = service.register("alice", "abc12345", null);

        assertThat(created.getUsername()).isEqualTo("alice");
        verify(inviteCodeService, never()).claim(anyString(), anyString());
        verify(userMapper).insert(any(User.class));
    }
}
