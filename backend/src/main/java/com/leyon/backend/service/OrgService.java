package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.OrgMember;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.OrgMapper;
import com.leyon.backend.mapper.OrgMemberMapper;
import com.leyon.backend.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组织业务服务（P2-10 多租户与商业化前置）
 * 提供组织创建/查询/删除、成员管理（加人/改角色/移除/退出）与角色矩阵鉴权
 * <p>
 * 角色分级：owner(拥有者) &gt; editor(编辑者) &gt; viewer(只读)；非成员视为无权。
 *
 * @author leyon
 */
@Service
public class OrgService {

    private final OrgMapper orgMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final UserMapper userMapper;

    public OrgService(OrgMapper orgMapper, OrgMemberMapper orgMemberMapper, UserMapper userMapper) {
        this.orgMapper = orgMapper;
        this.orgMemberMapper = orgMemberMapper;
        this.userMapper = userMapper;
    }

    // ===================== 角色与归属查询 =====================

    /**
     * 查询用户所属的首个组织ID（按加入时间正序取第一个，供配额作用域定位）
     *
     * @param userId 用户ID
     * @return 组织ID，无组织返回 null
     */
    public String getOrgIdOfUser(String userId) {
        LambdaQueryWrapper<OrgMember> wrapper = new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getUserId, userId)
                .orderByAsc(OrgMember::getJoinedAt)
                .last("LIMIT 1");
        OrgMember member = orgMemberMapper.selectOne(wrapper);
        return member == null ? null : member.getOrgId();
    }

    /**
     * 判断是否是组织成员
     */
    public boolean isMember(String orgId, String userId) {
        if (!StringUtils.hasText(orgId) || !StringUtils.hasText(userId)) {
            return false;
        }
        return orgMemberMapper.selectCount(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getUserId, userId)) > 0;
    }

    /**
     * 获取用户在组织中的角色
     *
     * @return owner/editor/viewer，非成员返回 null
     */
    public String getRole(String orgId, String userId) {
        if (!StringUtils.hasText(orgId) || !StringUtils.hasText(userId)) {
            return null;
        }
        OrgMember member = orgMemberMapper.selectOne(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getUserId, userId)
                .last("LIMIT 1"));
        return member == null ? null : member.getRole();
    }

    /**
     * 要求当前用户是组织成员，否则抛出 403
     */
    public void requireMember(String orgId, String userId) {
        if (!isMember(orgId, userId)) {
            throw new ForbiddenException("无权访问该组织资源");
        }
    }

    /**
     * 要求当前用户角色 >= 最低角色，否则抛出 403
     *
     * @param orgId   组织ID
     * @param userId  当前用户ID
     * @param minRole 最低允许角色（owner/editor/viewer）
     */
    public void requireRole(String orgId, String userId, String minRole) {
        if (!hasRoleAtLeast(orgId, userId, minRole)) {
            throw new ForbiddenException("无权执行该操作");
        }
    }

    /**
     * 判断用户角色是否 >= 最低角色（非成员返回 false）
     * 供 WebSocket 等不适合抛 HTTP 异常的场景做授权判断
     */
    public boolean hasRoleAtLeast(String orgId, String userId, String minRole) {
        String role = getRole(orgId, userId);
        return role != null && roleLevel(role) >= roleLevel(minRole);
    }

    /**
     * 角色级别：owner=3 &gt; editor=2 &gt; viewer=1
     */
    private int roleLevel(String role) {
        return switch (role) {
            case Org.ROLE_OWNER -> 3;
            case Org.ROLE_EDITOR -> 2;
            case Org.ROLE_VIEWER -> 1;
            default -> 0;
        };
    }

    // ===================== 组织管理 =====================

    /**
     * 创建组织，创建者自动成为 owner 成员
     */
    @Transactional
    public Org createOrg(String userId, String name, String description) {
        if (!StringUtils.hasText(name) || name.trim().length() > 64) {
            throw new IllegalArgumentException("组织名称不能为空且不超过64字符");
        }
        Org org = new Org();
        org.setName(name.trim());
        org.setOwnerUserId(userId);
        org.setDescription(description);
        org.setIsDeleted(Org.NOT_DELETED);
        orgMapper.insert(org);

        OrgMember owner = new OrgMember();
        owner.setOrgId(org.getId());
        owner.setUserId(userId);
        owner.setRole(Org.ROLE_OWNER);
        orgMemberMapper.insert(owner);
        return org;
    }

    /**
     * 查询用户加入的组织列表
     */
    public List<Org> listMyOrgs(String userId) {
        List<OrgMember> members = orgMemberMapper.selectList(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getUserId, userId));
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> orgIds = members.stream().map(OrgMember::getOrgId).distinct().toList();
        return orgMapper.selectBatchIds(orgIds);
    }

    /**
     * 查询组织详情（成员可查）
     */
    public Org getById(String orgId, String userId) {
        Org org = getById(orgId);
        if (org == null) {
            throw new IllegalArgumentException("组织不存在");
        }
        requireMember(orgId, userId);
        return org;
    }

    public Org getById(String orgId) {
        return orgMapper.selectById(orgId);
    }

    /**
     * 查询组织成员列表（仅成员可查），成员附带用户名回填
     */
    public List<OrgMember> listMembers(String orgId, String userId) {
        requireMember(orgId, userId);
        List<OrgMember> members = orgMemberMapper.selectList(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .orderByAsc(OrgMember::getJoinedAt));
        fillUsernames(members);
        return members;
    }

    /**
     * 批量回填成员用户名（前端成员列表展示用户名而非用户ID；一次批量查询避免 N+1）
     */
    private void fillUsernames(List<OrgMember> members) {
        if (members.isEmpty()) {
            return;
        }
        List<String> userIds = members.stream().map(OrgMember::getUserId).distinct().toList();
        Map<String, String> usernameById = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        members.forEach(member -> member.setUsername(usernameById.get(member.getUserId())));
    }

    /**
     * 添加成员（仅 owner，按用户名定位用户）
     *
     * @param orgId      组织ID
     * @param operatorId 操作人（owner）
     * @param username   待加入用户名
     * @param role       分配角色（editor/viewer；owner 仅创建者可拥有）
     */
    @Transactional
    public OrgMember addMember(String orgId, String operatorId, String username, String role) {
        requireRole(orgId, operatorId, Org.ROLE_OWNER);
        String targetRole = normalizeAssignableRole(role);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        CanonicalMember existing = findMember(orgId, user.getId());
        if (existing != null) {
            throw new IllegalArgumentException("该用户已是组织成员");
        }
        OrgMember member = new OrgMember();
        member.setOrgId(orgId);
        member.setUserId(user.getId());
        member.setRole(targetRole);
        orgMemberMapper.insert(member);
        return member;
    }

    /**
     * 修改成员角色（仅 owner；不允许修改 owner 本人的角色，不允许把成员改成 owner）
     */
    @Transactional
    public void changeRole(String orgId, String operatorId, String targetUserId, String newRole) {
        requireRole(orgId, operatorId, Org.ROLE_OWNER);
        if (operatorId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能修改自己(owner)的角色");
        }
        String targetRole = normalizeAssignableRole(newRole);
        CanonicalMember member = findMember(orgId, targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("该用户不是组织成员");
        }
        OrgMember update = new OrgMember();
        update.setId(member.id());
        update.setRole(targetRole);
        orgMemberMapper.updateById(update);
    }

    /**
     * 移除成员（owner 移除他人 / 本人退出；owner 不可移除自己）
     */
    @Transactional
    public void removeMember(String orgId, String operatorId, String targetUserId) {
        requireMember(orgId, operatorId);
        boolean selfExit = operatorId.equals(targetUserId);
        if (!selfExit) {
            // 他人被移除：仅 owner 可操作
            requireRole(orgId, operatorId, Org.ROLE_OWNER);
        }
        CanonicalMember member = findMember(orgId, targetUserId);
        if (member == null) {
            throw new IllegalArgumentException("该用户不是组织成员");
        }
        if (Org.ROLE_OWNER.equals(member.role())) {
            throw new IllegalArgumentException("不能移除组织拥有者");
        }
        orgMemberMapper.deleteById(member.id());
    }

    /**
     * 删除组织（仅 owner；逻辑删除组织 + 清空成员关系）
     */
    @Transactional
    public void deleteOrg(String orgId, String userId) {
        requireRole(orgId, userId, Org.ROLE_OWNER);
        Org org = getById(orgId);
        if (org == null) {
            throw new IllegalArgumentException("组织不存在");
        }
        org.setIsDeleted(Org.DELETED);
        orgMapper.updateById(org);
        // 清空成员关系（物理删除，解除所有关联）
        orgMemberMapper.delete(new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, orgId));
    }

    // ===================== 内部辅助 =====================

    /**
     * 归一化可分配角色：只允许 editor / viewer（owner 不通过成员管理分配）
     */
    private String normalizeAssignableRole(String role) {
        if (Org.ROLE_EDITOR.equals(role)) {
            return Org.ROLE_EDITOR;
        }
        if (Org.ROLE_VIEWER.equals(role)) {
            return Org.ROLE_VIEWER;
        }
        throw new IllegalArgumentException("角色仅支持 editor / viewer");
    }

    private record CanonicalMember(String id, String role) {
    }

    private CanonicalMember findMember(String orgId, String userId) {
        OrgMember member = orgMemberMapper.selectOne(new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getUserId, userId)
                .last("LIMIT 1"));
        return member == null ? null : new CanonicalMember(member.getId(), member.getRole());
    }
}