package com.leyon.backend.service;

import com.leyon.backend.entity.UserMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * save_memory 工具闭包单测（v2.85 · C-161，收口候选 ⑫）
 * 锁的是"userId 来自装配时的闭包，不来自模型输入"与"入参形状归一"
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryToolServiceTest {

    @Mock
    private UserMemoryService userMemoryService;

    private MemoryToolService service;

    @BeforeEach
    void setUp() {
        service = new MemoryToolService(userMemoryService);
    }

    @Test
    void toolFor_blankUserId_returnsEmpty() {
        assertThat(service.toolFor(null)).isEmpty();
        assertThat(service.toolFor(" ")).isEmpty();
    }

    @Test
    void toolInputAsJson_savesUnderClosedOverUserId() throws Exception {
        Optional<org.springframework.ai.tool.ToolCallback> tool = service.toolFor("u-1");
        assertThat(tool).isPresent();

        tool.get().call("{\"content\":\"用户偏好简洁回答\"}");

        // userId 来自闭包（装配时的握手身份），模型输入里没有"我是谁"的余地
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(userMemoryService).save(eq("u-1"), captor.capture());
        assertThat(captor.getValue()).isEqualTo("用户偏好简洁回答");
    }

    @Test
    void saveFailure_convergesToTextResult() throws Exception {
        Optional<org.springframework.ai.tool.ToolCallback> tool = service.toolFor("u-1");
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(userMemoryService).save(any(), any());
        String result = tool.get().call("{\"content\":\"x\"}");
        // 工具失败收敛为文本结果（模型可继续对话），不炸整轮
        assertThat(result).contains("记忆保存失败");
    }

    @Test
    void toolName_isSaveMemory() throws Exception {
        Optional<org.springframework.ai.tool.ToolCallback> tool = service.toolFor("u-1");
        assertThat(tool.get().getToolDefinition().name()).isEqualTo(MemoryToolService.TOOL_NAME);
    }
}
