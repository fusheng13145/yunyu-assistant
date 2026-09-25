package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.InviteCode;
import com.leyon.backend.mapper.InviteCodeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 注册邀请码服务（v2.37 邀请码制注册）
 * <p>
 * 注册开放度的第一阶段的闸门：{@code app.registration.mode=invite}（默认）时注册必须带一个
 * 未被使用的一次性码；{@code open} 时忽略邀请码（第二阶段放开注册的开关，见手册 6.7 决策 2）。
 * 模式判定集中在这里，注册链路与 {@code GET /api/auth/register-config} 共用同一份，避免前后端两处判断漂移。
 *
 * @author leyon
 */
@Service
public class InviteCodeService {

    /** 邀请码制注册（默认）：公网首阶段，注册成本 = 一个一次性码 */
    public static final String MODE_INVITE = "invite";
    /** 开放注册：第二阶段，条件成熟后改此值并重启 */
    public static final String MODE_OPEN = "open";

    /** 单次批量生成上限：防止管理员一次生成上千个码到处发 */
    public static final int MAX_GENERATE_PER_REQUEST = 50;

    /**
     * 码字符集：大写字母去掉易混的 I/O，数字去掉 0/1 与小写 l —— 邀请码要经人工转发，
     * 肉眼分不清的字符等于把"码无效"变成客服工单。32 字符 × 12 位 ≈ 1.1e18 组合，
     * 且注册端点在 AUTH 限流桶（5 次/分钟）内，枚举不成立。
     */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 12;

    private final InviteCodeMapper inviteCodeMapper;
    private final String registrationMode;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeService(InviteCodeMapper inviteCodeMapper,
                             @Value("${app.registration.mode:invite}") String registrationMode) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.registrationMode = registrationMode;
    }

    /**
     * 是否需要邀请码：只认显式的 open，其余取值（含写错的拼写）一律按更严格的 invite 处理
     */
    public boolean inviteRequired() {
        return !MODE_OPEN.equalsIgnoreCase(registrationMode);
    }

    /**
     * 批量生成邀请码（管理端）
     *
     * @param count       生成数量，1~{@value #MAX_GENERATE_PER_REQUEST}
     * @param operatorId  操作管理员的用户ID，落库便于追责
     * @return 生成的码列表（可直接转发）
     * @throws RuntimeException 数量越界
     */
    public List<String> generate(int count, String operatorId) {
        if (count < 1 || count > MAX_GENERATE_PER_REQUEST) {
            throw new RuntimeException("单次生成数量需在 1~" + MAX_GENERATE_PER_REQUEST + " 之间");
        }
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < count) {
            codes.add(randomCode());
        }
        List<String> result = new ArrayList<>(codes);
        for (String code : result) {
            InviteCode row = new InviteCode();
            row.setCode(code);
            row.setCreatedBy(operatorId);
            inviteCodeMapper.insert(row);
        }
        return result;
    }

    /**
     * 领取邀请码（注册链路）：大小写不敏感，只有原子 UPDATE 影响 1 行才算本次独占成功
     *
     * @return true=领取成功 false=码不存在或已被使用（不区分二者，避免变成邀请码探测接口）
     */
    public boolean claim(String code, String userId) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(userId)) {
            return false;
        }
        return inviteCodeMapper.claimByCode(code.trim().toUpperCase(Locale.ROOT), userId) == 1;
    }

    /**
     * 邀请码分页列表（管理端）：未使用的排前面，便于运营看清"还能发几个"
     */
    public Map<String, Object> listPage(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) (safePage - 1) * safeSize;

        LambdaQueryWrapper<InviteCode> wrapper = new LambdaQueryWrapper<InviteCode>()
                .orderByAsc(InviteCode::getUsedBy)
                .orderByDesc(InviteCode::getCreatedAt);
        List<InviteCode> list = inviteCodeMapper.selectList(
                wrapper.clone().last("LIMIT " + safeSize + " OFFSET " + offset));
        long total = inviteCodeMapper.selectCount(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
