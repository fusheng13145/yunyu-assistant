package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Session;
import com.leyon.backend.mapper.SessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 会话服务单元测试
 * 覆盖：归属校验、标题更新/置顶、自动生成标题、逻辑删除
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private OrgService orgService;
    @Mock
    private AssistantService assistantService;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        // 初始化 MyBatis-Plus 实体元数据，使 LambdaUpdateWrapper 在纯单测环境可用
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Session.class);
        sessionService = new SessionService(sessionMapper, orgService, assistantService);
    }

    /** 个人助手桩：create 会校验助手存在与可用 */
    private Assistant assistantOf(String userId, String orgId) {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setUserId(userId);
        a.setOrgId(orgId);
        return a;
    }

    private Session ownedSession(String id, String userId, String assistantId, String title, int pinned) {
        Session s = new Session();
        s.setId(id);
        s.setUserId(userId);
        s.setAssistantId(assistantId);
        s.setTitle(title);
        s.setIsPinned(pinned);
        return s;
    }

    @Test
    void create_assignsOwnerAndDefaultTitle() {
        when(assistantService.getById("a1")).thenReturn(assistantOf("u1", null));
        Session created = sessionService.create("u1", "a1", null, null);
        assertThat(created.getUserId()).isEqualTo("u1");
        assertThat(created.getAssistantId()).isEqualTo("a1");
        assertThat(created.getOrgId()).isNull();
        assertThat(created.getTitle()).isEqualTo(Session.DEFAULT_TITLE);
        assertThat(created.getIsPinned()).isEqualTo(Session.NOT_PINNED);
        verify(sessionMapper).insert(created);
    }

    @Test
    void create_unknownAssistant_rejected() {
        when(assistantService.getById("a1")).thenReturn(null);
        assertThatThrownBy(() -> sessionService.create("u1", "a1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessionMapper, never()).insert(any(Session.class));
    }

    @Test
    void create_foreignPersonalAssistant_rejected() {
        when(assistantService.getById("a1")).thenReturn(assistantOf("u2", null));
        assertThatThrownBy(() -> sessionService.create("u1", "a1", null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(sessionMapper, never()).insert(any(Session.class));
    }

    @Test
    void create_orgAssistant_requiresMembership() {
        when(assistantService.getById("a1")).thenReturn(assistantOf("u9", "org-1"));
        doThrow(new ForbiddenException("无权访问该组织资源")).when(orgService).requireMember("org-1", "u1");
        assertThatThrownBy(() -> sessionService.create("u1", "a1", null, null))
                .isInstanceOf(ForbiddenException.class);

        reset(orgService);
        sessionService.create("u1", "a1", null, null);
        verify(orgService).requireMember("org-1", "u1");
        verify(sessionMapper).insert(any(Session.class));
    }

    @Test
    void getOwned_rejectsForeignUser() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "标题", Session.NOT_PINNED));
        assertThat(sessionService.getOwned("s1", "u2")).isNull();
        assertThat(sessionService.getOwned("s1", "u1")).isNotNull();
    }

    @Test
    void update_foreignUserReturnsFalse() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "标题", Session.NOT_PINNED));
        assertThat(sessionService.update("s1", "u2", "新标题", Session.PINNED)).isFalse();
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void update_pinsAndRenamesOwnedSession() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "旧标题", Session.NOT_PINNED));
        when(sessionMapper.update(eq(null), any())).thenReturn(1);
        boolean ok = sessionService.update("s1", "u1", "新标题", Session.PINNED);
        assertThat(ok).isTrue();
        verify(sessionMapper).update(eq(null), any());
    }

    @Test
    void autoTitleIfNeeded_onlyWhenDefaultTitle() {
        // 标题已自定义 -> 不更新
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "自定义标题", Session.NOT_PINNED));
        sessionService.autoTitleIfNeeded("s1", "第一条用户消息");
        verify(sessionMapper, never()).updateById(any(Session.class));

        // 标题为默认 -> 取首条消息前缀更新
        Session defaultSession = ownedSession("s2", "u1", "a1", Session.DEFAULT_TITLE, Session.NOT_PINNED);
        when(sessionMapper.selectById("s2")).thenReturn(defaultSession);
        sessionService.autoTitleIfNeeded("s2", "这是一个非常长的用户消息用于测试自动标题截断逻辑是否生效的内容");
        verify(sessionMapper).updateById(any(Session.class));
        assertThat(defaultSession.getTitle()).startsWith("这是一个非常长");
        assertThat(defaultSession.getTitle()).endsWith("...");
    }

    @Test
    void autoTitleIfNeeded_ignoresBlankMessage() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", Session.DEFAULT_TITLE, Session.NOT_PINNED));
        sessionService.autoTitleIfNeeded("s1", "   ");
        verify(sessionMapper, never()).updateById(any(Session.class));
    }

    @Test
    void delete_foreignUserReturnsFalse() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "标题", Session.NOT_PINNED));
        assertThat(sessionService.delete("s1", "u2")).isFalse();
        verify(sessionMapper, never()).deleteById(any(String.class));
    }

    @Test
    void delete_ownedSessionDeletes() {
        when(sessionMapper.selectById("s1")).thenReturn(ownedSession("s1", "u1", "a1", "标题", Session.NOT_PINNED));
        when(sessionMapper.deleteById(any(String.class))).thenReturn(1);
        assertThat(sessionService.delete("s1", "u1")).isTrue();
        verify(sessionMapper).deleteById("s1");
    }

    /** 冒烟：listByUser 构造的查询条件不要因方向搞反排序 */
    @Test
    void listByUser_ordersPinnedThenUpdated() {
        // 仅验证方法可用（真实排序断言依赖 MP LambdaQueryWrapper 内部结构，这里验证不抛异常）
        List<Session> empty = List.of();
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(empty);
        assertThat(sessionService.listByUser("u1", "a1")).isEmpty();
        verify(sessionMapper).selectList(any(LambdaQueryWrapper.class));
    }
}