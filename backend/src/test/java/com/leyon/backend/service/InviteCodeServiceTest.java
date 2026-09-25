package com.leyon.backend.service;

import com.leyon.backend.entity.InviteCode;
import com.leyon.backend.mapper.InviteCodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邀请码服务单元测试（v2.37 邀请码制注册）
 * 覆盖：注册模式开关的三态判定、批量生成的可用性与唯一性、领取只认原子 UPDATE 的影响行数
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    @Mock
    private InviteCodeMapper inviteCodeMapper;

    private InviteCodeService service(String mode) {
        return new InviteCodeService(inviteCodeMapper, mode);
    }

    @Test
    void inviteRequired_onlyInInviteMode() {
        assertThat(service("invite").inviteRequired()).isTrue();
        assertThat(service("open").inviteRequired()).isFalse();
        // 未知取值按更严格的一侧处理：不能因为配置写错就变成开放注册
        assertThat(service("INVIT").inviteRequired()).isTrue();
    }

    @Test
    void generate_returnsShareableDistinctCodesAndPersistsEach() {
        List<String> codes = service("invite").generate(5, "admin-1");

        assertThat(codes).hasSize(5).doesNotHaveDuplicates();
        // 人工转发的场景（群里发码）要求肉眼可辨：去掉 0/O/1/I 这些易混字符
        assertThat(codes).allSatisfy(code -> {
            assertThat(code).hasSize(12);
            assertThat(code).doesNotContain("0", "O", "1", "I", "l");
            assertThat(code).matches("[A-HJ-NP-Z2-9]{12}");
        });

        ArgumentCaptor<InviteCode> captor = ArgumentCaptor.forClass(InviteCode.class);
        verify(inviteCodeMapper, times(5)).insert(captor.capture());
        List<InviteCode> saved = captor.getAllValues();
        assertThat(saved).extracting(InviteCode::getCode).containsExactlyElementsOf(codes);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getCreatedBy()).isEqualTo("admin-1");
            // 新建的码必须是未领取态：claim 的 WHERE used_by IS NULL 依赖这一前提
            assertThat(row.getUsedBy()).isNull();
            assertThat(row.getUsedAt()).isNull();
        });
    }

    @Test
    void generate_rejectsOutOfRangeCount_withoutTouchingDb() {
        InviteCodeService service = service("invite");

        assertThatThrownBy(() -> service.generate(0, "admin-1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.generate(51, "admin-1")).isInstanceOf(RuntimeException.class);
        verify(inviteCodeMapper, never()).insert(any(InviteCode.class));
    }

    @Test
    void claim_trueOnlyWhenAtomicUpdateAffectedOneRow() {
        when(inviteCodeMapper.claimByCode("ABC234", "u-1")).thenReturn(1);
        assertThat(service("invite").claim("ABC234", "u-1")).isTrue();

        when(inviteCodeMapper.claimByCode("USED000", "u-2")).thenReturn(0);
        assertThat(service("invite").claim("USED000", "u-2")).isFalse();
    }
}
