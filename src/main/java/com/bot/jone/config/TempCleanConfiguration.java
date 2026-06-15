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

    /**
     * 单个文件最大大小（MB），超过这个大小的文件优先级更高（即更容易被清理）
     * 默认50MB
     */
    private int maxFileSizeMb = 50;

    /**
     * 目录总大小上限（MB），超过后强制删除最旧文件
     * 默认 200MB
     */
    private int maxDirSizeMb = 200;

    /**
     * Pixiv缓存文件保留小时数（默认24小时）
     * Pixiv图片通常很大，独立缓存池清理更频繁
     */
    private int pixivRetentionHours = 24;

    /**
     * Pixiv缓存清理的Cron表达式（默认每小时）
     */
    private String pixivCleanCron = "0 0 * * * ?";

    /**
     * Pixiv缓存目录总大小上限（MB）
     * Pixiv图片体积大，默认 500MB
     */
    private int pixivMaxDirSizeMb = 500;
}
