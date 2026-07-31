package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ConversationManager implements DisposableBean {

    @Autowired
    private AiConfig aiConfig;

    private final Map<String, LinkedList<JSONObject>> conversationHistory = new ConcurrentHashMap<>();

    /** 落盘加载标记：保证进程生命周期内只从磁盘加载一次 */
    private volatile boolean loaded = false;
    private final Object loadLock = new Object();

    /** 距离上次落盘累计的消息条数，达到阈值(轮数*2)后触发刷盘 */
    private volatile int sinceSaveMessages = 0;

    private String getSessionKey(Long userId, String messageType) {
        return messageType + ":" + userId;
    }

    private int getMaxContext() {
        return aiConfig.getHistoryMaxContext();
    }

    private int getSaveThresholdMessages() {
        int rounds = Math.max(1, aiConfig.getConversationSaveEveryRounds());
        return rounds * 2;
    }

    private long getIdleThreshold() {
        return System.currentTimeMillis() - (long) aiConfig.getConversationMaxIdleHours() * 3600_000L;
    }

    /** 进程生命周期内首次访问时按需从磁盘加载（不依赖 Spring 初始化顺序） */
    private void ensureLoaded() {
        if (!loaded) {
            synchronized (loadLock) {
                if (!loaded) {
                    loadFromDisk();
                    loaded = true;
                }
            }
        }
    }

    /**
     * 从磁盘恢复短期记忆。失败仅告警，不影响内存态（从空开始）。
     * 加载时即完成：损坏条目丢弃、超长截断、过期会话淘汰。
     */
    private void loadFromDisk() {
        if (!aiConfig.isConversationPersistEnabled()) {
            return;
        }
        try {
            Path path = Paths.get(aiConfig.getConversationStoragePath());
            if (!Files.exists(path)) {
                return;
            }
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            JSONObject root = JSONObject.parseObject(text);
            if (root == null) {
                return;
            }
            JSONObject sessions = root.getJSONObject("sessions");
            if (sessions == null) {
                return;
            }
            long idleThreshold = getIdleThreshold();
            int maxMsgs = Math.max(1, getMaxContext() * 2);
            int restored = 0;
            for (String key : sessions.keySet()) {
                JSONArray arr = sessions.getJSONArray(key);
                if (arr == null) {
                    continue;
                }
                LinkedList<JSONObject> list = new LinkedList<>();
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    if (m == null) {
                        continue;
                    }
                    if (!m.containsKey("role") || !m.containsKey("content")) {
                        continue; // 丢弃字段缺失的损坏项
                    }
                    list.add(m);
                }
                while (list.size() > maxMsgs) {
                    list.removeFirst(); // 超长截断
                }
                if (list.isEmpty()) {
                    continue;
                }
                long lastTime = getLastTime(list);
                if (lastTime > 0 && lastTime < idleThreshold) {
                    continue; // 过期淘汰
                }
                conversationHistory.put(key, list);
                restored++;
            }
            log.info("[对话历史] 已从 {} 恢复 {} 个会话", aiConfig.getConversationStoragePath(), restored);
        } catch (Exception e) {
            log.warn("[对话历史] 加载失败（从空内存开始）: {}", e.getMessage());
        }
    }

    /**
     * 当前内存态原子落盘。失败仅告警，不影响运行。
     * 落盘时顺带淘汰过期会话，避免文件无限膨胀。
     */
    private void saveToDisk() {
        if (!aiConfig.isConversationPersistEnabled()) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            JSONObject sessions = new JSONObject();
            long idleThreshold = getIdleThreshold();
            int maxMsgs = Math.max(1, getMaxContext() * 2);
            for (Map.Entry<String, LinkedList<JSONObject>> entry : conversationHistory.entrySet()) {
                LinkedList<JSONObject> list = entry.getValue();
                if (list == null || list.isEmpty()) {
                    continue;
                }
                LinkedList<JSONObject> copy = new LinkedList<>(list); // 快照，避免并发修改异常
                while (copy.size() > maxMsgs) {
                    copy.removeFirst();
                }
                long lastTime = getLastTime(copy);
                if (lastTime > 0 && lastTime < idleThreshold) {
                    continue; // 过期会话不写入
                }
                sessions.put(entry.getKey(), new JSONArray(copy));
            }
            root.put("sessions", sessions);

            Path path = Paths.get(aiConfig.getConversationStoragePath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = Paths.get(path + ".tmp");
            Files.write(tmp, root.toJSONString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[对话历史] 落盘失败（不影响运行）: {}", e.getMessage());
        }
    }

    private long getLastTime(LinkedList<JSONObject> list) {
        if (list.isEmpty()) {
            return 0;
        }
        Object t = list.getLast().get("time");
        if (t instanceof Number) {
            return ((Number) t).longValue();
        }
        return 0;
    }

    public JSONArray getHistory(Long userId, String messageType) {
        ensureLoaded();
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
        ensureLoaded();
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

        // 落盘触发：累计达到"每 N 轮"阈值后刷一次盘
        sinceSaveMessages++;
        if (aiConfig.isConversationPersistEnabled() && sinceSaveMessages >= getSaveThresholdMessages()) {
            sinceSaveMessages = 0;
            saveToDisk();
        }
    }

    public void reset(Long userId, String messageType) {
        ensureLoaded();
        String key = getSessionKey(userId, messageType);
        LinkedList<JSONObject> history = conversationHistory.remove(key);
        if (history != null) {
            log.info("[对话重置] 用户 {} 的对话历史已清空，共 {} 条消息", userId, history.size());
        }
        saveToDisk(); // 清空后也立即落盘，保证磁盘与内存一致
    }

    public int getHistoryCount(Long userId, String messageType) {
        ensureLoaded();
        String key = getSessionKey(userId, messageType);
        LinkedList<JSONObject> history = conversationHistory.get(key);
        return history != null ? history.size() : 0;
    }

    /**
     * 获取所有 session 列表（用于归档）
     * @return 列表，每项 [userId, messageType]
     */
    public java.util.List<SessionRef> getAllSessions() {
        ensureLoaded();
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
        ensureLoaded();
        conversationHistory.clear();
        log.info("[对话历史] 全部清空");
        saveToDisk();
    }

    /**
     * Spring 优雅停机钩子：强制全量落盘，保证正常重启几乎不丢记忆。
     */
    @Override
    public void destroy() {
        saveToDisk();
    }
}
