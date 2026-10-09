package com.leyon.backend.service;

import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

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

    /**
     * v2.90：查重与插入之间的竞态由迁移 0013 的唯一索引兜底，但兜底后必须把 1062 翻译回业务拒绝。
     * <p>
     * 下面三条消息是<b>真机抓下来的原文</b>（{@code .scratch/v290-race-probe.sh} 两轮读数），
     * 不是按猜测拼的格式——MyBatis 的异常翻译会把 "### Error updating database" 整块带进来，
     * 于是它落到全局兜底并被脱敏成"请求处理失败"，用户看不到自己撞的是哪一个标识。
     */
    private void insertThrows(String dbMessage) {
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException(dbMessage));
    }

    @Test
    void register_emailRaceOnIndex_rejectsAsEmailTaken() {
        UserService service = userService(false);
        noIdentifierTaken();
        insertThrows("### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: "
                + "Duplicate entry 'race084135@example.test' for key 'users.uk_users_email_active'");

        assertThatThrownBy(() -> service.register("alice", "abc12345", "alice@example.com", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该邮箱已被注册");
    }

    @Test
    void register_phoneRaceOnIndex_rejectsAsPhoneTaken() {
        UserService service = userService(false);
        noIdentifierTaken();
        insertThrows("### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: "
                + "Duplicate entry '13800002891' for key 'users.uk_users_phone_active'");

        assertThatThrownBy(() -> service.register("alice", "abc12345", null, "138 0000 2891", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该手机号已被注册");
    }

    @Test
    void register_usernameRaceOnIndex_rejectsAsUsernameTaken() {
        UserService service = userService(false);
        noIdentifierTaken();
        insertThrows("### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: "
                + "Duplicate entry 'race_u_084318' for key 'users.username'");

        assertThatThrownBy(() -> service.register("alice", "abc12345", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("用户名已存在");
    }

    /**
     * 认不出索引名时也不能把 DB 原文交出去：这一条锁的是"回落安全文案"，
     * 而不是依赖全局脱敏兜底——后者会把原因抹成"请求处理失败"，正是本批要消掉的形状。
     */
    @Test
    void register_unrecognizedIndex_rejectsWithoutLeakingDbText() {
        UserService service = userService(false);
        noIdentifierTaken();
        insertThrows("### Error updating database.  Cause: java.sql.SQLException: "
                + "Duplicate entry 'x' for key 'users.some_future_index'");

        assertThatThrownBy(() -> service.register("alice", "abc12345", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).isNotBlank();
                    assertThat(msg).doesNotContainIgnoringCase("sql")
                            .doesNotContain("Duplicate")
                            .doesNotContain("users.");
                });
    }

    /**
     * 竞态分支不得改变既有不变量：邀请码模式下的领取仍然发生在插入之前，
     * 插入被索引撞死时由 {@code @Transactional} 回滚撤销领取（这条性质单测桩证不了，见手册 7.4 v2.90 的真机取证）。
     */
    @Test
    void register_inviteModeRaceStillClaimsBeforeInsert() {
        UserService service = userService(true);
        noIdentifierTaken();
        when(inviteCodeService.claim(eq("GOOD123456"), anyString())).thenReturn(true);
        insertThrows("### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: "
                + "Duplicate entry 'alice@example.com' for key 'users.uk_users_email_active'");

        assertThatThrownBy(() -> service.register("alice", "abc12345", "alice@example.com", null, "GOOD123456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该邮箱已被注册");

        verify(inviteCodeService).claim(eq("GOOD123456"), anyString());
    }
}
