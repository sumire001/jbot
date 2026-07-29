package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.ai.model.GroupMemory;
import com.bot.jone.ai.model.UserMemory;
import com.bot.jone.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LongTermMemoryManager {

    @Autowired
    private MemoryConfig memoryConfig;

    @Autowired
    @Lazy
    private AiService aiService;

    private final ConcurrentMap<Long, UserMemory> userMemoryMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, GroupMemory> groupMemoryMap = new ConcurrentHashMap<>();
    private final Set<Long> dirtyUsers = ConcurrentHashMap.newKeySet();
    private final Set<Long> dirtyGroups = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Long> lastSummaryTimeMap = new ConcurrentHashMap<>();

    private File memoryDir;

    @PostConstruct
    public void init() {
        if (!memoryConfig.isEnabled()) {
            log.info("[长期记忆] 已禁用");
            return;
        }
        memoryDir = new File(memoryConfig.getMemoryDir());
        if (!memoryDir.exists()) {
            boolean created = memoryDir.mkdirs();
            if (created) {
                log.info("[长期记忆] 创建目录: {}", memoryDir.getAbsolutePath());
            }
        }
        loadAllMemory();
        log.info("[长期记忆] 初始化完成，用户画像: {} 个，群记忆: {} 个", 
            userMemoryMap.size(), groupMemoryMap.size());
    }

    private void loadAllMemory() {
        if (memoryDir == null || !memoryDir.exists()) {
            return;
        }
        File[] files = memoryDir.listFiles((dir, name) -> 
            name.matches("(group_|user_)\\d+\\.json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                String content = readFileContent(file);
                if (file.getName().startsWith("user_")) {
                    UserMemory user = JSONObject.parseObject(content, UserMemory.class);
                    if (user != null && user.getQq() != null) {
                        userMemoryMap.put(user.getQq(), user);
                    }
                } else if (file.getName().startsWith("group_")) {
                    GroupMemory group = JSONObject.parseObject(content, GroupMemory.class);
                    if (group != null && group.getGroupId() != null) {
                        groupMemoryMap.put(group.getGroupId(), group);
                    }
                }
            } catch (Exception e) {
                log.warn("[长期记忆] 加载文件失败: {}", file.getName(), e);
            }
        }
    }

    public void triggerSummary(Long sessionId, String messageType, Long senderId, String speaker, JSONArray history) {
        if (!memoryConfig.isEnabled()) {
            return;
        }
        // 私聊用 senderId 做冷却 key，群聊用 groupId
        Long key = "group".equals(messageType) ? sessionId : senderId;
        if (!canTriggerSummary(key)) {
            return;
        }
        if (history == null || history.size() < 4) {
            return;
        }
        try {
            JSONObject summaryResult = generateSummary(history, sessionId, senderId, speaker, messageType);
            if (summaryResult != null) {
                processSummary(sessionId, messageType, senderId, speaker, summaryResult);
                flushDirty();
                lastSummaryTimeMap.put(key, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.error("[长期记忆] 摘要生成失败", e);
        }
    }

    private boolean canTriggerSummary(Long groupId) {
        long cooldownMs = TimeUnit.MINUTES.toMillis(memoryConfig.getSummaryCooldownMinutes());
        Long lastTime = lastSummaryTimeMap.get(groupId);
        return lastTime == null || System.currentTimeMillis() - lastTime > cooldownMs;
    }

    private JSONObject generateSummary(JSONArray history, Long sessionId, Long senderId, String speaker, String messageType) {
        StringBuilder historyText = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            JSONObject msg = history.getJSONObject(i);
            String role = msg.getString("role");
            String content = msg.getString("content");
            historyText.append(role).append(": ").append(content).append("\n");
        }

        // 构造已知用户信息，不让 AI 猜
        String userInfo;
        if ("group".equals(messageType)) {
            userInfo = "当前群聊: groupId=" + sessionId + "\n" +
                "当前发言用户QQ: " + senderId + ", 昵称: " + (speaker != null ? speaker : "未知") + "\n";
        } else {
            userInfo = "当前用户QQ: " + senderId + ", 昵称: " + (speaker != null ? speaker : "未知") + "\n";
        }

        String prompt = "你是一个对话摘要专家。请分析以下对话历史，输出结构化的群摘要和用户画像更新。\n\n" +
            "## 已知用户信息\n" + userInfo +
            "## 对话历史（按时间顺序）\n" + historyText + "\n" +
            "## 输出格式（必须是纯 JSON，无其他文字）\n" +
            "{\n" +
            "  \"groupSummary\": \"用40-80字描述群氛围、常聊话题、群内约定\",\n" +
            "  \"userUpdates\": [\n" +
            "    {\n" +
            "      \"qq\": " + senderId + ",\n" +
            "      \"nickname\": \"" + (speaker != null ? speaker : "未知") + "\",\n" +
            "      \"traits\": \"用20-40字描述用户的兴趣爱好、性格特征、说话风格\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n\n" +
            "## 规则\n" +
            "1. 只更新这轮对话中发言的用户，未发言的不写入 userUpdates\n" +
            "2. qq 必须使用上面给出的已知值 " + senderId + "，不能猜测或编造\n" +
            "3. nickname 必须使用上面给出的已知值，不能猜测或编造\n" +
            "4. traits 是增量更新，保留之前的特征，新增本轮发现的特征\n" +
            "5. 如果用户特征没有变化，不要重复输出\n" +
            "6. 必须是合法 JSON，不能有注释\n";

        String response = aiService.summarize(prompt);
        if (response == null || response.isEmpty()) {
            log.warn("[长期记忆] 摘要返回为空");
            return null;
        }
        try {
            return JSONObject.parseObject(response);
        } catch (Exception e) {
            log.warn("[长期记忆] 解析摘要JSON失败: {}", response);
            return null;
        }
    }

    private void processSummary(Long sessionId, String messageType, Long senderId, String speaker, JSONObject summary) {
        String groupSummary = summary.getString("groupSummary");
        JSONArray userUpdates = summary.getJSONArray("userUpdates");

        if ("group".equals(messageType)) {
            GroupMemory group = groupMemoryMap.computeIfAbsent(sessionId, k -> {
                GroupMemory g = new GroupMemory();
                g.setGroupId(sessionId);
                return g;
            });
            if (groupSummary != null && !groupSummary.isEmpty()) {
                group.updateSummary(groupSummary);
                dirtyGroups.add(sessionId);
            }
        } else {
            // 私聊场景：直接用真实信息绑定用户，不依赖 AI 返回的 qq
            if (speaker != null && !speaker.isEmpty()) {
                UserMemory user = userMemoryMap.computeIfAbsent(senderId, k -> {
                    UserMemory u = new UserMemory();
                    u.setQq(senderId);
                    return u;
                });
                user.setNickname(speaker);
                user.setLastSeen(System.currentTimeMillis());
                dirtyUsers.add(senderId);
            }
        }

        if (userUpdates != null) {
            for (int i = 0; i < userUpdates.size(); i++) {
                JSONObject update = userUpdates.getJSONObject(i);
                // 永远使用系统已知的真实 QQ 号和昵称，不用 AI 猜测的
                Long qq = senderId;
                String nickname = speaker != null && !speaker.isEmpty() ? speaker : "未知";
                String traits = update.getString("traits");

                UserMemory user = userMemoryMap.computeIfAbsent(qq, k -> {
                    UserMemory u = new UserMemory();
                    u.setQq(qq);
                    return u;
                });
                user.setNickname(nickname);
                user.setLastSeen(System.currentTimeMillis());
                if ("group".equals(messageType)) {
                    user.updateGroupNickname(sessionId, nickname);
                }
                if (traits != null && !traits.isEmpty()) {
                    user.updateTraits(traits);
                }
                dirtyUsers.add(qq);

                if ("group".equals(messageType)) {
                    GroupMemory group = groupMemoryMap.get(sessionId);
                    if (group != null) {
                        group.addMember(qq);
                        group.retainTopMembers(memoryConfig.getMaxMembersPerGroup());
                        dirtyGroups.add(sessionId);
                    }
                }
            }
        }
    }

    public String getGroupContext(Long groupId) {
        GroupMemory group = groupMemoryMap.get(groupId);
        if (group == null || group.getSummary() == null) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("# 最近群里的氛围: ");
        context.append(group.getSummary());
        context.append("\n\n# 你认识这些人: ");
        boolean first = true;
        for (Long qq : group.getMemberIds()) {
            UserMemory user = userMemoryMap.get(qq);
            if (user != null && user.getTraits() != null && !user.getTraits().isEmpty()) {
                if (!first) context.append(", ");
                String nickname = user.getNicknameForGroup(groupId);
                context.append(nickname).append("：").append(user.getTraits());
                first = false;
            }
        }
        return context.toString();
    }

    public String getUserContext(Long qq) {
        UserMemory user = userMemoryMap.get(qq);
        if (user == null || user.getTraits() == null || user.getTraits().isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("# 关于这个用户你记得: ");
        context.append(user.getNickname()).append("：").append(user.getTraits());
        return context.toString();
    }

    public UserMemory getUserMemory(Long qq) {
        return userMemoryMap.get(qq);
    }

    public ConcurrentMap<Long, UserMemory> getUserMemoryMap() {
        return userMemoryMap;
    }

    public void updateUserMemory(Long qq, String nickname, String traits) {
        UserMemory user = userMemoryMap.computeIfAbsent(qq, k -> {
            UserMemory u = new UserMemory();
            u.setQq(qq);
            return u;
        });
        if (nickname != null && !nickname.isEmpty()) {
            user.setNickname(nickname);
        }
        if (traits != null) {
            user.setTraits(traits);
        }
        user.setLastSeen(System.currentTimeMillis());
        user.setVersion(user.getVersion() + 1);
        dirtyUsers.add(qq);
    }

    public void clearUserMemory(Long qq) {
        userMemoryMap.remove(qq);
        dirtyUsers.remove(qq);
        File file = new File(memoryDir, "user_" + qq + ".json");
        if (file.exists()) {
            file.delete();
        }
        for (GroupMemory group : groupMemoryMap.values()) {
            group.removeMember(qq);
            dirtyGroups.add(group.getGroupId());
        }
        flushDirty();
    }

    public void clearGroupMemory(Long groupId) {
        groupMemoryMap.remove(groupId);
        dirtyGroups.remove(groupId);
        File file = new File(memoryDir, "group_" + groupId + ".json");
        if (file.exists()) {
            file.delete();
        }
        flushDirty();
    }

    public void clearAllMemory() {
        userMemoryMap.clear();
        groupMemoryMap.clear();
        dirtyUsers.clear();
        dirtyGroups.clear();
        if (memoryDir != null && memoryDir.exists()) {
            File[] files = memoryDir.listFiles((dir, name) -> 
                name.matches("(group_|user_)\\d+\\.json"));
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
        log.info("[长期记忆] 全部清空");
    }

    @Scheduled(fixedDelayString = "${bot.ai.long-term-memory.flush-interval-minutes:5}", 
               timeUnit = TimeUnit.MINUTES)
    public void scheduleFlush() {
        if (!memoryConfig.isEnabled()) {
            return;
        }
        flushDirty();
    }

    public void flushDirty() {
        if (!memoryConfig.isEnabled()) {
            return;
        }
        try {
            for (Long qq : dirtyUsers) {
                UserMemory user = userMemoryMap.get(qq);
                if (user != null) {
                    writeUserToFile(user);
                }
            }
            dirtyUsers.clear();

            for (Long groupId : dirtyGroups) {
                GroupMemory group = groupMemoryMap.get(groupId);
                if (group != null) {
                    writeGroupToFile(group);
                }
            }
            dirtyGroups.clear();
        } catch (Exception e) {
            log.error("[长期记忆] 刷盘失败", e);
        }
    }

    private void writeUserToFile(UserMemory user) {
        File file = new File(memoryDir, "user_" + user.getQq() + ".json");
        writeFileContent(file, JSONObject.toJSONString(user));
    }

    private void writeGroupToFile(GroupMemory group) {
        File file = new File(memoryDir, "group_" + group.getGroupId() + ".json");
        writeFileContent(file, JSONObject.toJSONString(group));
    }

    private void writeFileContent(File file, String content) {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
        } catch (IOException e) {
            log.error("[长期记忆] 写入文件失败: {}", file.getName(), e);
        }
    }

    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }
}