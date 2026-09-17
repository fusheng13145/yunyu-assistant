package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.RecordMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天记录服务分页单测
 * 覆盖：会话消息倒序分页、总数统计、空参数防护
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordServiceTest {

    @Mock
    private RecordMapper recordMapper;

    private RecordService recordService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Record.class);
        recordService = new RecordService(recordMapper);
    }

    @Test
    void pageBySessionIdDesc_emptyIdReturnsEmpty() {
        assertThat(recordService.pageBySessionIdDesc("", 0, 50)).isEmpty();
        assertThat(recordService.pageBySessionIdDesc(null, 0, 50)).isEmpty();
    }

    @Test
    void pageBySessionIdDesc_delegatesWithOwnerFilter() {
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThat(recordService.pageBySessionIdDesc("s1", 50, 50)).isEmpty();
        verify(recordMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void countBySessionId_delegates() {
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(250L);
        assertThat(recordService.countBySessionId("s1")).isEqualTo(250L);
        verify(recordMapper).selectCount(any(LambdaQueryWrapper.class));
    }

    @Test
    void countBySessionId_emptyIdReturnsZero() {
        assertThat(recordService.countBySessionId("")).isZero();
        assertThat(recordService.countBySessionId(null)).isZero();
    }

    @Test
    void listBySessionIdLimit_reversesToChronological() {
        Record r1 = new Record();
        r1.setId("newest");
        Record r2 = new Record();
        r2.setId("oldest");
        // Mapper 返回可变列表（reverse 需要）
        when(recordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(r1, r2)));
        List<Record> result = recordService.listBySessionIdLimit("s1", 10);
        assertThat(result.get(0).getId()).isEqualTo("oldest");
        assertThat(result.get(1).getId()).isEqualTo("newest");
    }
}