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
}