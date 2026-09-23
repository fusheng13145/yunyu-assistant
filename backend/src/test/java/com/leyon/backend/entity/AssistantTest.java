package com.leyon.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手实体的工具白名单序列化单元测试
 * 覆盖：数组入库存 JSON 字符串、空数组落 "[]"（保证可更新）、NULL/脏数据回退"全部可用"、对外 JSON 为数组
 *
 * @author leyon
 */
class AssistantTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolList_isStoredAsJsonString() {
        Assistant assistant = new Assistant();
        assistant.setToolList(java.util.List.of("get_weather", "hangup"));

        assertThat(assistant.getTools()).isEqualTo("[\"get_weather\",\"hangup\"]");
        assertThat(assistant.getToolList()).containsExactly("get_weather", "hangup");
    }

    @Test
    void emptyToolList_writesBlankArrayMeaningAllTools() {
        Assistant assistant = new Assistant();
        assistant.setToolList(java.util.List.of());

        assertThat(assistant.getTools()).isEqualTo("[]");
        assertThat(assistant.getToolList()).isEmpty();
    }

    @Test
    void null_or_malformed_tools_readAsEmptyList() {
        Assistant assistant = new Assistant();
        assertThat(assistant.getToolList()).isEmpty();

        assistant.setTools(null);
        assertThat(assistant.getToolList()).isEmpty();

        assistant.setTools("不是 JSON");
        assertThat(assistant.getToolList()).isEmpty();
    }

    @Test
    void serializedJson_exposesToolsAsArray() throws Exception {
        Assistant assistant = new Assistant();
        assistant.setName("测试助手");
        assistant.setToolList(java.util.List.of("get_weather"));

        assertThat(objectMapper.writeValueAsString(assistant))
                .contains("\"tools\":[\"get_weather\"]")
                // DB 层的 JSON 字符串不对外暴露
                .doesNotContain("[\\\"get_weather\\\"]");
    }
}
