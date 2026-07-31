package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bot.ai.long-term-memory")
public class MemoryConfig {

    private boolean enabled = true;
    private String memoryDir = "./data/memory";
    private int flushIntervalMinutes = 5;
    private int maxMembersPerGroup = 50;
    private int inactiveDaysToRemove = 30;
    private int summaryCooldownMinutes = 10;
    private int archiveRetentionDays = 30;
    /**
     * 单用户画像(traits)落盘的最大字符数。
     * 仅作安全阀：防止 LLM 抽风写出超长文本；与“每条消息 token 成本”无关
     * （聊天上下文注入另有 max-group-context-chars 控制）。字符串存储本身不是问题。
     */
    private int maxTraitsLength = 300;
    /**
     * 注入到聊天 system prompt 的「群成员画像」总长上限（字符）。
     * 这是真正控制每条消息 token 成本的点：getGroupContext 会遍历全群成员拼接 traits，
     * 大群会随人数线性膨胀，超出后按人头截断并提示省略。
     */
    private int maxGroupContextChars = 600;
}