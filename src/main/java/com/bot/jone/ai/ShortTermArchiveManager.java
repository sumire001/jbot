package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 短期记忆归档管理器
 *
 * 策略：
 * - 每天 0 点定时任务
 * - 归档"3 天前"（即 day = today - 3 days）的所有 session 对话
 * - 文件名格式：{group/private}_{年_月_日}_{groupId/userId}.json
 * - 文件结构：[ {time, sender, content}, ... ]
 * - content 字段：文本存原文，图片/表情/语音等存占位符
 * - 归档后清空对应 session 的内存数据
 * - 启动时自动清理超期未归档的历史文件
 */
@Component
@Slf4j
public class ShortTermArchiveManager {

    @Autowired
    private ConversationManager conversationManager;

    @Autowired
    private MemoryConfig memoryConfig;

    private File archiveDir;
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    @PostConstruct
    public void init() {
        if (!memoryConfig.isEnabled()) {
            log.info("[短期归档] 已禁用");
            return;
        }
        archiveDir = new File(memoryConfig.getMemoryDir(), "archive");
        if (!archiveDir.exists()) {
            boolean created = archiveDir.mkdirs();
            if (created) {
                log.info("[短期归档] 创建目录: {}", archiveDir.getAbsolutePath());
            }
        }
        cleanupExpiredArchives();
    }

    /**
     * 清理超过保留天数的归档文件
     * 默认保留 30 天（可通过配置调整）
     */
    private void cleanupExpiredArchives() {
        if (archiveDir == null || !archiveDir.exists()) {
            return;
        }
        int retentionDays = memoryConfig.getArchiveRetentionDays();
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000;
        File[] files = archiveDir.listFiles();
        if (files == null) return;
        int removed = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("[短期归档] 清理过期文件 {} 个（保留 {} 天）", removed, retentionDays);
        }
    }

    /**
     * 每天凌晨执行归档
     * cron: 每天 0 点 0 分
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Shanghai")
    public void archiveOldSessions() {
        if (!memoryConfig.isEnabled()) {
            return;
        }
        // 归档 3 天前的数据
        LocalDate targetDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(3);
        archiveSessionsOfDate(targetDate);
    }

    /**
     * 归档指定日期的所有 session
     * @param date 要归档的日期
     */
    public void archiveSessionsOfDate(LocalDate date) {
        if (!memoryConfig.isEnabled()) {
            return;
        }
        if (archiveDir == null) {
            init();
        }
        if (archiveDir == null) {
            return;
        }

        String dateStr = date.format(FILE_DATE_FORMAT);
        long dayStart = date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

        List<ConversationManager.SessionRef> sessions = conversationManager.getAllSessions();
        int archived = 0;

        for (ConversationManager.SessionRef session : sessions) {
            JSONArray history = conversationManager.getHistory(session.userId, session.messageType);
            if (history.isEmpty()) continue;

            // 过滤出目标日期的对话
            JSONArray dayMessages = new JSONArray();
            for (int i = 0; i < history.size(); i++) {
                JSONObject msg = history.getJSONObject(i);
                long time = msg.getLongValue("time", 0L);
                if (time >= dayStart && time < dayEnd) {
                    JSONObject archived_msg = new JSONObject();
                    archived_msg.put("time", time);
                    archived_msg.put("sender", msg.getString("speaker"));
                    archived_msg.put("content", convertContent(msg.getString("content")));
                    dayMessages.add(archived_msg);
                }
            }

            if (dayMessages.isEmpty()) continue;

            // 写入归档文件
            String prefix = "group".equals(session.messageType) ? "group" : "private";
            String fileName = String.format("%s_%s_%d.json", prefix, dateStr, session.userId);
            File archiveFile = new File(archiveDir, fileName);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(archiveFile), StandardCharsets.UTF_8))) {
                writer.write(JSONObject.toJSONString(dayMessages, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
                archived++;
                log.info("[短期归档] {} → {} 条", fileName, dayMessages.size());
            } catch (IOException e) {
                log.error("[短期归档] 写入失败: {}", fileName, e);
            }
        }

        if (archived > 0) {
            log.info("[短期归档] 日期 {} 共归档 {} 个 session", dateStr, archived);
        }
    }

    /**
     * 转换 content 字段
     * - 文本保持原样
     * - 已包含占位符的图片/表情保持原样
     */
    private String convertContent(String content) {
        if (content == null) return "";
        // ConversationManager 内部已经把图片转成 [图片:url] 格式了
        // 这里无需额外处理
        return content;
    }

    /**
     * 立即归档所有内存中的对话（用于手动触发或测试）
     */
    public void archiveAllNow() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        // 归档今天 + 昨天
        archiveSessionsOfDate(today);
        archiveSessionsOfDate(today.minusDays(1));
        archiveSessionsOfDate(today.minusDays(2));
    }
}
