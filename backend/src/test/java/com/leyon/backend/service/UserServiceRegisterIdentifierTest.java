package com.leyon.backend.service;

import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
 * 注册的标识字段测试（v2.89）
 * <p>
 * 覆盖的是三条会静默失效的性质：
 * <ul>
 *   <li><b>校验与查重必须全部排在领取邀请码之前</b>——顺序错一次的代价是"码烧掉、号没建出来"，
 *       而用户只会看到一个报错和一个已经用过的码（{@link UserServiceInviteCodeTest} 锁的是同一族，
 *       本文件把邮箱/手机号这两条新分支接进去）；</li>
 *   <li><b>空串必须归成 NULL 落库</b>——迁移 0013 的唯一索引建在"仅活行参与"的生成列上，NULL 不冲突、
 *       空串冲突；写成空串的后果是第二个没填邮箱的人注册失败，且报错指向邮箱；</li>
 *   <li><b>查重用归一化值</b>——按原始串查则 "ALICE@example.com" 绕过查重，插入时才被大小写敏感的
 *       唯一索引撞死，对外是 500 而不是"邮箱已被注册"。</li>
 * </ul>
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class UserServiceRegisterIdentifierTest {

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
        return new UserService(userMapper, jwtUtil, loginAttemptService, inviteCodeService,
                new IdentifierPolicy());
    }

    private void noIdentifierTaken() {
        lenient().when(userMapper.selectActiveByUsername(anyString())).thenReturn(List.of());
        lenient().when(userMapper.selectActiveByEmail(anyString())).thenReturn(List.of());
        lenient().when(userMapper.selectActiveByPhone(anyString())).thenReturn(List.of());
    }

    private User captureInserted() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void register_storesNormalizedEmailAndPhone() {
        UserService service = userService(false);
        noIdentifierTaken();

        service.register("alice", "abc12345", "  Alice@Example.COM ", "+86 (138) 0000-0000", null);

        User inserted = captureInserted();
        assertThat(inserted.getEmail()).isEqualTo("alice@example.com");
        assertThat(inserted.getPhone()).isEqualTo("+8613800000000");
    }

    @Test
    void register_blankIdentifiersStoredAsNullNotEmpty() {
        UserService service = userService(false);
        noIdentifierTaken();

        service.register("alice", "abc12345", "   ", "", null);

        User inserted = captureInserted();
        assertThat(inserted.getEmail()).isNull();
        assertThat(inserted.getPhone()).isNull();
    }

    @Test
    void register_duplicateEmail_rejectedAndCodeNotBurned() {
        UserService service = userService(true);
        // 不 stub 用户名查重：邮箱查重在它之前，走到那一步就说明顺序错了
        when(userMapper.selectActiveByEmail("alice@example.com"))
                .thenReturn(List.of(new User()));

        assertThatThrownBy(() -> service.register("alice", "abc12345", "alice@example.com", null, "GOOD123456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("邮箱");

        verify(userMapper, never()).selectActiveByUsername(anyString());
        verify(inviteCodeService, never()).claim(anyString(), anyString());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_duplicatePhone_rejectedAndCodeNotBurned() {
        UserService service = userService(true);
        when(userMapper.selectActiveByPhone("13800000000"))
                .thenReturn(List.of(new User()));

        assertThatThrownBy(() -> service.register("alice", "abc12345", null, "138 0000 0000", "GOOD123456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("手机号");

        verify(userMapper, never()).selectActiveByUsername(anyString());
        verify(inviteCodeService, never()).claim(anyString(), anyString());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_duplicateDetectedOnNormalizedEmail() {
        UserService service = userService(false);
        when(userMapper.selectActiveByEmail("alice@example.com"))
                .thenReturn(List.of(new User()));

        assertThatThrownBy(() -> service.register("alice", "abc12345", "ALICE@Example.com", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("邮箱");

        verify(userMapper).selectActiveByEmail("alice@example.com");
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_malformedEmail_rejectedBeforeAnyWrite() {
        UserService service = userService(true);

        assertThatThrownBy(() -> service.register("alice", "abc12345", "alice@example", null, "GOOD123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱");

        verify(inviteCodeService, never()).claim(anyString(), anyString());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_emailShapedUsername_rejected() {
        UserService service = userService(false);

        assertThatThrownBy(() -> service.register("a@b.co", "abc12345", "a@b.co", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_phoneShapedUsername_rejected() {
        UserService service = userService(false);

        assertThatThrownBy(() -> service.register("13800000000", "abc12345", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_inviteModeStillClaimsForTheInsertedAccount() {
        UserService service = userService(true);
        noIdentifierTaken();
        when(inviteCodeService.claim(eq("GOOD123456"), anyString())).thenReturn(true);

        User created = service.register("alice", "abc12345", "alice@example.com", "13800000000", "GOOD123456");

        ArgumentCaptor<String> claimedFor = ArgumentCaptor.forClass(String.class);
        verify(inviteCodeService).claim(eq("GOOD123456"), claimedFor.capture());
        assertThat(claimedFor.getValue()).isEqualTo(created.getId());
        assertThat(captureInserted().getId()).isEqualTo(created.getId());
    }
}
