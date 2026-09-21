package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.OrgMember;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.OrgMapper;
import com.leyon.backend.mapper.OrgMemberMapper;
import com.leyon.backend.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 组织服务单元测试（P2-10 多租户与商业化前置）
 * 覆盖：创建组织自动入 owner、角色级别比较、加人/改角色/移除权限矩阵、
 * 组织数据归属判定（个人数据按 userId / 组织数据按成员角色）
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgServiceTest {

    @Mock
    private OrgMapper orgMapper;
    @Mock
    private OrgMemberMapper orgMemberMapper;
    @Mock
    private UserMapper userMapper;

    private OrgService orgService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Org.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrgMember.class);
        orgService = new OrgService(orgMapper, orgMemberMapper, userMapper);
    }

    private OrgMember member(String id, String orgId, String userId, String role) {
        OrgMember m = new OrgMember();
        m.setId(id);
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void createOrg_ownerAutoAdded() {
        when(orgMapper.insert(any(Org.class))).thenAnswer(inv -> {
            Org org = inv.getArgument(0);
            org.setId("org1");
            return 1;
        });
        when(orgMemberMapper.insert(any(OrgMember.class))).thenAnswer(inv -> {
            OrgMember m = inv.getArgument(0);
            m.setId("m1");
            return 1;
        });
        Org org = orgService.createOrg("u1", "技术团队", "研发组");
        assertThat(org.getId()).isEqualTo("org1");
        assertThat(org.getOwnerUserId()).isEqualTo("u1");
        // owner 成员写入
        verifyInsertedOwner(org.getId(), "u1");
    }

    private void verifyInsertedOwner(String orgId, String userId) {
        org.mockito.ArgumentCaptor<OrgMember> captor = org.mockito.ArgumentCaptor.forClass(OrgMember.class);
        org.mockito.Mockito.verify(orgMemberMapper).insert(captor.capture());
        OrgMember inserted = captor.getValue();
        assertThat(inserted.getOrgId()).isEqualTo(orgId);
        assertThat(inserted.getUserId()).isEqualTo(userId);
        assertThat(inserted.getRole()).isEqualTo(Org.ROLE_OWNER);
    }

    @Test
    void createOrg_blankNameRejected() {
        assertThatThrownBy(() -> orgService.createOrg("u1", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireRole_roleLevelComparisons() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<OrgMember> w = inv.getArgument(0);
            // 简化：按 userId 返回固定角色（单测隔离运行，用顺序桩）
            return null;
        });
        // 非成员抛 403
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> orgService.requireMember("org1", "u-x"))
                .isInstanceOf(ForbiddenException.class);

        // viewer 足够读（requireMember 通过）
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        orgService.requireMember("org1", "u1");

        // viewer 不能管理（requireRole editor）
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u1", Org.ROLE_VIEWER));
        assertThatThrownBy(() -> orgService.requireRole("org1", "u1", Org.ROLE_EDITOR))
                .isInstanceOf(ForbiddenException.class);

        // editor 可管理、可读
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u1", Org.ROLE_EDITOR));
        orgService.requireRole("org1", "u1", Org.ROLE_EDITOR);
        orgService.requireRole("org1", "u1", Org.ROLE_VIEWER);
    }

    @Test
    void addMember_notOwnerRejected() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-viewer", Org.ROLE_VIEWER));
        assertThatThrownBy(() -> orgService.addMember("org1", "u-viewer", "someone", Org.ROLE_EDITOR))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addMember_ownerAddsEditor() {
        // 第 1 次 selectOne：requireRole 校验 owner；第 2 次 selectOne：findMember 判定不存在（新用户未入组）
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-owner", Org.ROLE_OWNER), null);
        User target = new User();
        target.setId("u-new");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);
        when(orgMemberMapper.insert(any(OrgMember.class))).thenAnswer(inv -> {
            OrgMember m = inv.getArgument(0);
            m.setId("m2");
            return 1;
        });
        OrgMember added = orgService.addMember("org1", "u-owner", "newbie", Org.ROLE_EDITOR);
        assertThat(added.getOrgId()).isEqualTo("org1");
        assertThat(added.getUserId()).isEqualTo("u-new");
        assertThat(added.getRole()).isEqualTo(Org.ROLE_EDITOR);
    }

    @Test
    void addMember_invalidRoleRejected() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-owner", Org.ROLE_OWNER));
        assertThatThrownBy(() -> orgService.addMember("org1", "u-owner", "newbie", "super-admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeRole_ownerCannotChangeSelf() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-owner", Org.ROLE_OWNER));
        assertThatThrownBy(() -> orgService.changeRole("org1", "u-owner", "u-owner", Org.ROLE_VIEWER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeMember_ownerCannotRemoveSelf() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-owner", Org.ROLE_OWNER));
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> orgService.removeMember("org1", "u-owner", "u-owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memberCanExit_bySelfExitAllowed() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org1", "u-viewer", Org.ROLE_VIEWER));
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(orgMemberMapper.deleteById("m1")).thenReturn(1);
        orgService.removeMember("org1", "u-viewer", "u-viewer");
        org.mockito.Mockito.verify(orgMemberMapper).deleteById("m1");
    }

    @Test
    void getOrgIdOfUser_returnsFirstOrg() {
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(member("m1", "org9", "u1", Org.ROLE_EDITOR));
        assertThat(orgService.getOrgIdOfUser("u1")).isEqualTo("org9");
        // 无组织返回 null
        when(orgMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(StringUtils.hasText(orgService.getOrgIdOfUser("u1"))).isFalse();
    }

    @Test
    void isMember_coversOrgAndUser() {
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThat(orgService.isMember("org1", "u1")).isTrue();
        when(orgMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        assertThat(orgService.isMember("org1", "u1")).isFalse();
        assertThat(orgService.isMember("", "u1")).isFalse();
        assertThat(orgService.isMember("org1", "")).isFalse();
    }

    @Test
    void listMyOrgs_returnsMatchingOrgs() {
        when(orgMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(member("m1", "org1", "u1", Org.ROLE_OWNER)));
        Org org1 = new Org();
        org1.setId("org1");
        when(orgMapper.selectBatchIds(List.of("org1"))).thenReturn(List.of(org1));
        List<Org> orgs = orgService.listMyOrgs("u1");
        assertThat(orgs).hasSize(1);
        assertThat(orgs.get(0).getId()).isEqualTo("org1");
    }
}