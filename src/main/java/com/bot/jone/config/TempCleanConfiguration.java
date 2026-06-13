package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Temp目录清理配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot.temp")
public class TempCleanConfiguration {
    
    /**
     * 文件保留天数（默认7天）
     * 常见配置：
     * - 3天：节省空间，适合频繁使用
     * - 7天：平衡选择（默认）
     * - 14天：保守策略，防止误删
     */
    private int retentionDays = 7;
    
    /**
     * 定时清理的Cron表达式（默认每天凌晨3点）
     * 格式：秒 分 时 日 月 星期
     * 示例：
     * - "0 0 3 * * ?" : 每天凌晨3点
     * - "0 0 0/6 * * ?" : 每6小时
     * - "0 0 0 * * ?" : 每天午夜
     */
    private String cleanCron = "0 0 3 * * ?";
}
