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

        // 提取已有画像，注入提示词用于增量更新（否则旧特征/旧群氛围会被覆盖丢失）
        GroupMemory existingGroup = "group".equals(messageType) ? groupMemoryMap.get(sessionId) : null;
        UserMemory existingUser = userMemoryMap.get(senderId);
        String oldGroupSummary = (existingGroup != null && existingGroup.getSummary() != null && !existingGroup.getSummary().isEmpty())
                ? existingGroup.getSummary() : "（暂无）";
        String oldTraits = (existingUser != null && existingUser.getTraits() != null && !existingUser.getTraits().isEmpty())
                ? existingUser.getTraits() : "（暂无）";

        String prompt = "你是一个对话摘要专家。请分析以下对话历史，输出结构化的群摘要和用户画像更新。\n\n" +
            "## 已知用户信息\n" + userInfo +
            "## 对话历史（按时间顺序）\n" + historyText + "\n" +
            "## 已有画像（必须在此基础增量，禁止丢弃旧信息）\n" +
            "群摘要现状: " + oldGroupSummary + "\n" +
            "用户特征现状: " + oldTraits + "\n" +
            "## 输出格式（必须是纯 JSON，无其他文字）\n" +
            "{\n" +
            "  \"groupSummary\": \"用40-80字描述群氛围、常聊话题、群内约定\",\n" +
            "  \"userUpdates\": [\n" +
            "    {\n" +
            "      \"qq\": " + senderId + ",\n" +
            "      \"nickname\": \"" + (speaker != null ? speaker : "未知") + "\",\n" +
            "      \"traits\": \"原子化、去重后的用户特征列表（如'喜欢用拟声词呃呃'、'偏好猫系语气词喵'）。要求：1) 同一含义禁止用多种说法重复（如不要同时写'说话极简'与'说话简洁'）；2) 每条必须是完整自洽的短语，禁止半截话（不得出现以'和'、'与'、'喜欢'等词结尾的残缺表述）；3) 总长度严格不超过 100 字；4) 即使本轮未揭示新特征，也必须在'用户特征现状'基础上整体整理后输出（合并去重、补全半截表述、裁到100字以内），不得原样照抄残缺或重复的条目，也不得丢失仍成立的旧特征\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n\n" +
            "## 规则\n" +
            "1. 只更新这轮对话中发言的用户，未发言的不写入 userUpdates\n" +
            "2. qq 必须使用上面给出的已知值 " + senderId + "，不能猜测或编造\n" +
            "3. nickname 必须使用上面给出的已知值，不能猜测或编造\n" +
            "4. 基于【已有画像】+【新对话】，输出合并去重后的【完整】groupSummary 与 traits，不得只保留近期内容\n" +
            "5. 已有画像里已存在的相同特征不要重复输出；群摘要需融合旧群摘要与新观察，旧约定/氛围若仍成立必须保留\n" +
            "6. traits 必须去重：同一含义的不同表述（如'说话极简'/'说话简洁'/'说话短促'/'说话简短'，或'性格迷糊'/'性格迷糊可爱'）只保留一条信息量最大、最具体的，禁止并列；'用户特征现状'里已有的特征，不要换个说法再次列出\n" +
            "7. 每条 traits 必须是完整的短语，绝不在词中途截断（如'喜欢用拟声词和'这种半截话一律禁止）；若篇幅受限，宁可少写几条完整特征，也不要写半截。traits 总长度严格不超过 100 字，超出时优先保留最新、最具体的特征，删掉冗余旧表述\n" +
            "8. 必须是合法 JSON，不能有注释\n";

        String response = aiService.summarize(prompt);
        if (response == null) {
            // 调用层失败（网络异常 / HTTP 非 200），具体原因见上方 [AI] 请求失败日志
            log.warn("[长期记忆] 摘要调用失败（AI 服务异常或 HTTP 非200），请查看上方 [AI] 请求失败日志");
            return null;
        }
        if (response.isEmpty()) {
            // HTTP 200 但 content 为空：最常见于内容安全过滤拦截、模型返回空、或响应无 choices
            log.warn("[长期记忆] 摘要返回空内容：AI 返回 HTTP 200 但 content 为空。"
                + "常见原因=对话含敏感词被安全过滤 / 模型返回空 / 响应无 choices。请查看上方 [AI] 原始响应日志");
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
                    user.updateTraits(traits, memoryConfig.getMaxTraitsLength());
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
        context.append("# 以下是对本群/用户的推测印象（由对话自动归纳，可能不准确，仅供参考，不要当作确定事实复述）:\n");
        context.append("# 最近群里的氛围: ");
        context.append(group.getSummary());
        context.append("\n\n# 你认识这些人: ");
        // 群成员画像按人头拼接会随人数线性膨胀，是每条消息 token 成本的主要来源。
        // 用 maxGroupContextChars 做预算：超出后停止追加并提示省略（在成员边界截断，不切断单条画像）。
        int budget = memoryConfig.getMaxGroupContextChars();
        int used = 0;
        boolean first = true;
        boolean truncated = false;
        for (Long qq : group.getMemberIds()) {
            UserMemory user = userMemoryMap.get(qq);
            if (user != null && user.getTraits() != null && !user.getTraits().isEmpty()) {
                String nickname = user.getNicknameForGroup(groupId);
                String entry = (first ? "" : "，") + nickname + "：" + user.getTraits();
                if (used + entry.length() > budget) {
                    truncated = true;
                    break;
                }
                context.append(entry);
                used += entry.length();
                first = false;
            }
        }
        if (truncated) {
            context.append(" …（更多成员画像已省略以控制上下文长度）");
        }
        return context.toString();
    }

    public String getUserContext(Long qq) {
        UserMemory user = userMemoryMap.get(qq);
        if (user == null || user.getTraits() == null || user.getTraits().isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("# 关于这个用户（以下为推测印象，可能不准，仅供参考，不要当作确定事实复述）: ");
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