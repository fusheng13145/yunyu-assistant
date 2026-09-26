package com.leyon.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聊天记录实体的知识库引用序列化单元测试
 * 覆盖：JSON 字符串列回读为对象、对外 JSON 为对象而非转义字符串、NULL/脏数据不臆造状态
 *
 * @author leyon
 */
class RecordTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void knowledgebaseInfo_readsBackAsObject() {
        Record record = new Record();
        record.setKnowledgebaseInfo("{\"docCount\":2,\"docName\":[\"A.pdf\",\"B.pdf\"],\"failed\":false}");

        Record.Knowledgebase kb = record.getKnowledgebase();
        assertThat(kb).isNotNull();
        assertThat(kb.docCount()).isEqualTo(2);
        assertThat(kb.docName()).containsExactly("A.pdf", "B.pdf");
        assertThat(kb.failed()).isFalse();
    }

    @Test
    void serializedJson_exposesKnowledgebaseAsObject() throws Exception {
        Record record = new Record();
        record.setRole(Record.ROLE_ASSISTANT);
        record.setMessage("回答");
        record.setKnowledgebaseInfo("{\"docCount\":1,\"docName\":[\"A.pdf\"],\"failed\":true}");

        String json = objectMapper.writeValueAsString(record);
        assertThat(json).contains("\"knowledgebase\":{\"docCount\":1,\"docName\":[\"A.pdf\"],\"failed\":true}")
                // DB 层的转义字符串不对外暴露，否则历史回看拿到的是字符串而非对象
                .doesNotContain("\\\"docCount\\\"");
    }

    @Test
    void null_or_malformed_knowledgebaseInfo_readsAsNullNotFabricatedState() {
        Record record = new Record();
        // 未挂知识库的会话：绝不能回读成 {docCount:0, failed:false}，那会被前端渲染成"知识库确实没有相关内容"
        assertThat(record.getKnowledgebase()).isNull();

        record.setKnowledgebaseInfo(null);
        assertThat(record.getKnowledgebase()).isNull();

        record.setKnowledgebaseInfo("   ");
        assertThat(record.getKnowledgebase()).isNull();

        record.setKnowledgebaseInfo("不是 JSON");
        assertThat(record.getKnowledgebase()).isNull();
    }

    @Test
    void knowledgebase_writesCanonicalJsonForTheColumn() {
        Record record = new Record();
        record.setKnowledgebase(new Record.Knowledgebase(1, List.of("A.pdf"), false));

        // 列文本形状与 query_end 帧同名同形：一处定义、读写同源，落库与下发不可能漂移
        assertThat(record.getKnowledgebaseInfo()).isEqualTo("{\"docCount\":1,\"docName\":[\"A.pdf\"],\"failed\":false}");
    }

    @Test
    void dbColumnGetter_stillUsableAsStringForPersistence() {
        Record record = new Record();
        record.setKnowledgebaseInfo("{\"docCount\":0,\"docName\":[],\"failed\":true}");

        // MyBatis-Plus 与归档 SQL 按字符串读写该列，对象化只影响对外 JSON
        assertThat(record.getKnowledgebaseInfo()).isEqualTo("{\"docCount\":0,\"docName\":[],\"failed\":true}");
    }
}
