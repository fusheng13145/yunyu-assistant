package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.Session;
import com.leyon.backend.mapper.SessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 会话业务服务
 * 提供会话创建、列表、标题/置顶更新、删除等能力
 *
 * @author leyon
 */
@Service
public class SessionService {

    private final SessionMapper sessionMapper;
    private final OrgService orgService;

    /** 自动生成标题时截取的用户消息前缀长度 */
    private static final int TITLE_PREFIX_LENGTH = 20;

    public SessionService(SessionMapper sessionMapper, OrgService orgService) {
        this.sessionMapper = sessionMapper;
        this.orgService = orgService;
    }

    /**
     * 创建会话，归属当前用户；请求携带 orgId 且当前用户为 editor(含)以上时归属组织
     *
     * @param userId      用户ID
     * @param assistantId 助手ID
     * @param title       会话标题（可为空，默认"新对话"）
     * @param orgId       组织ID（可为空）
     * @return 创建后的会话
     */
    public Session create(String userId, String assistantId, String title, String orgId) {
        String effectiveOrgId = resolveCreateOrg(orgId, userId);
        Session session = new Session();
        session.setUserId(userId);
        session.setAssistantId(assistantId);
        session.setOrgId(effectiveOrgId);
        session.setTitle(StringUtils.hasText(title) ? title.trim() : Session.DEFAULT_TITLE);
        session.setIsPinned(Session.NOT_PINNED);
        session.setIsDeleted(Session.NOT_DELETED);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 解析创建归属组织：携带 orgId 且当前用户为 editor(含)以上时归属组织；否则归属个人
     */
    private String resolveCreateOrg(String orgId, String userId) {
        if (!StringUtils.hasText(orgId)) {
            return null;
        }
        orgService.requireRole(orgId, userId, Org.ROLE_EDITOR);
        return orgId;
    }

    /**
     * 查询用户的会话列表（指定助手），置顶优先、按更新时间倒序
     *
     * @param userId      用户ID
     * @param assistantId 助手ID（可为空，空时返回该用户全部会话）
     * @return 会话列表
     */
    public List<Session> listByUser(String userId, String assistantId) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, userId)
                .orderByDesc(Session::getIsPinned)
                .orderByDesc(Session::getUpdatedAt);
        if (StringUtils.hasText(assistantId)) {
            wrapper.eq(Session::getAssistantId, assistantId);
        }
        return sessionMapper.selectList(wrapper);
    }

    /**
     * 根据ID查询会话（不含归属校验）
     *
     * @param id 会话ID
     * @return 会话实体，不存在返回 null
     */
    public Session getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return sessionMapper.selectById(id);
    }

    /**
     * 根据 ID 查询会话并校验访问权限（读权限：个人按 userId，组织按成员 viewer 以上），
     * 非本人/非组织成员返回 null
     *
     * @param id     会话ID
     * @param userId 当前用户ID
     * @return 可读会话实体
     */
    public Session getOwned(String id, String userId) {
        Session session = getById(id);
        if (session == null) {
            return null;
        }
        if (StringUtils.hasText(session.getOrgId())) {
            return orgService.isMember(session.getOrgId(), userId) ? session : null;
        }
        return userId.equals(session.getUserId()) ? session : null;
    }

    /**
     * 更新会话（标题 / 置顶，仅更新非空字段），带归属校验
     * 个人按 userId，组织按角色 editor(含)以上
     *
     * @param id      会话ID
     * @param userId  当前用户ID
     * @param title   新标题（可为空，空则不更新）
     * @param isPinned 置顶标记（可为空）
     * @return true-更新成功 false-会话不存在或无权
     */
    public boolean update(String id, String userId, String title, Integer isPinned) {
        Session owned = getOwned(id, userId);
        if (owned == null) {
            return false;
        }
        if (StringUtils.hasText(owned.getOrgId())) {
            orgService.requireRole(owned.getOrgId(), userId, Org.ROLE_EDITOR);
        }
        LambdaUpdateWrapper<Session> wrapper = new LambdaUpdateWrapper<Session>()
                .eq(Session::getId, id);
        boolean changed = false;
        if (StringUtils.hasText(title)) {
            wrapper.set(Session::getTitle, title.trim());
            changed = true;
        }
        if (isPinned != null && (isPinned == Session.PINNED || isPinned == Session.NOT_PINNED)) {
            wrapper.set(Session::getIsPinned, isPinned);
            changed = true;
        }
        if (!changed) {
            return true;
        }
        return sessionMapper.update(null, wrapper) > 0;
    }

    /**
     * 会话首轮对话后自动生成标题（取首条用户消息前缀）
     * 仅当会话标题仍为默认"新对话"时生效
     *
     * @param id              会话ID
     * @param firstUserMessage 首条用户消息内容
     */
    public void autoTitleIfNeeded(String id, String firstUserMessage) {
        if (!StringUtils.hasText(id) || !StringUtils.hasText(firstUserMessage)) {
            return;
        }
        Session session = sessionMapper.selectById(id);
        if (session == null || !Session.DEFAULT_TITLE.equals(session.getTitle())) {
            return;
        }
        String clean = firstUserMessage.trim().replaceAll("\\s+", " ");
        if (clean.length() > TITLE_PREFIX_LENGTH) {
            clean = clean.substring(0, TITLE_PREFIX_LENGTH) + "...";
        }
        session.setTitle(clean);
        sessionMapper.updateById(session);
    }

    /**
     * 逻辑删除会话（带归属校验：个人按 userId，组织按角色 editor 以上）
     *
     * @param id     会话ID
     * @param userId 当前用户ID
     * @return true-删除成功 false-会话不存在或无权
     */
    public boolean delete(String id, String userId) {
        Session owned = getOwned(id, userId);
        if (owned == null) {
            return false;
        }
        if (StringUtils.hasText(owned.getOrgId())) {
            orgService.requireRole(owned.getOrgId(), userId, Org.ROLE_EDITOR);
        }
        return sessionMapper.deleteById(id) > 0;
    }
}