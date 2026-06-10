package com.leyon.backend.entity;

import java.util.List;

public class ChatMessage {

    private String role;
    private String message;
    private Long costTime;
    private KnowledgebaseInfo knowledgebase;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getCostTime() {
        return costTime;
    }

    public void setCostTime(Long costTime) {
        this.costTime = costTime;
    }

    public KnowledgebaseInfo getKnowledgebase() {
        return knowledgebase;
    }

    public void setKnowledgebase(KnowledgebaseInfo knowledgebase) {
        this.knowledgebase = knowledgebase;
    }

    public static class KnowledgebaseInfo {
        private int docCount;
        private List<String> docName;

        public int getDocCount() {
            return docCount;
        }

        public void setDocCount(int docCount) {
            this.docCount = docCount;
        }

        public List<String> getDocName() {
            return docName;
        }

        public void setDocName(List<String> docName) {
            this.docName = docName;
        }
    }
}
