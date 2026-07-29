package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ConversationManager {

    @Autowired
    private AiConfig aiConfig;

    private final Map<String, LinkedList<JSONObject>> conversationHistory = new ConcurrentHashMap<>();

    private String getSessionKey(Long userId, String messageType) {
        return messageType + ":" + userId;
    }

    private int getMaxContext() {
        return aiConfig.getHistoryMaxContext();
    }

    public JSONArray getHistory(Long userId, String messageType) {
        String key = getSessionKey(userId, messageType);
        LinkedList<JSONObject> history = conversationHistory.get(key);
        if (history == null || history.isEmpty()) {
            return new JSONArray();
        }
        return new JSONArray(history);
    }

    public void addMessage(Long userId, String messageType, String role, String content) {
        addMessage(userId, messageType, role, content, null);
    }

    public void addMessage(Long userId, String messageType, String role, String content, String speaker) {
        String key = getSessionKey(userId, messageType);
        conversationHistory.computeIfAbsent(key, k -> new LinkedList<>());
        
        LinkedList<JSONObject> history = conversationHistory.get(key);
        
        String formattedContent = content;
        if (speaker != null && !speaker.isEmpty() && !"assistant".equals(role)) {
            formattedContent = "[" + speaker + "]: " + content;
        }
        
        history.add(new JSONObject()
            .fluentPut("role", role)
            .fluentPut("content", formattedContent)
            .fluentPut("speaker", speaker)
            .fluentPut("time", System.currentTimeMillis()));
        
        int maxContext = getMaxContext();
        while (history.size() > maxContext * 2) {
            history.removeFirst();
        }
        
        log.debug("[对话历史] 用户 {} 添加消息: {} -> {}", userId, role, content.length() > 30 ? content.substring(0, 30) + "..." : content);
    }

    public void reset(Long userId, String messageType) {
        String key = getSessionKey(userId, messageType);
        LinkedList<JSONObject> history = conversationHistory.remove(key);
        if (history != null) {
            log.info("[对话重置] 用户 {} 的对话历史已清空，共 {} 条消息", userId, history.size());
        }
    }

    public int getHistoryCount(Long userId, String messageType) {
        String key = getSessionKey(userId, messageType);
        LinkedList<JSONObject> history = conversationHistory.get(key);
        return history != null ? history.size() : 0;
    }

    /**
     * 获取所有 session 列表（用于归档）
     * @return 列表，每项 [userId, messageType]
     */
    public java.util.List<SessionRef> getAllSessions() {
        java.util.List<SessionRef> sessions = new java.util.ArrayList<>();
        for (String key : conversationHistory.keySet()) {
            int idx = key.indexOf(':');
            if (idx > 0) {
                try {
                    String messageType = key.substring(0, idx);
                    Long userId = Long.parseLong(key.substring(idx + 1));
                    sessions.add(new SessionRef(userId, messageType));
                } catch (NumberFormatException ignored) {}
            }
        }
        return sessions;
    }

    public static class SessionRef {
        public final Long userId;
        public final String messageType;
        public SessionRef(Long userId, String messageType) {
            this.userId = userId;
            this.messageType = messageType;
        }
    }

    public void clearAll() {
        conversationHistory.clear();
        log.info("[对话历史] 全部清空");
    }
}