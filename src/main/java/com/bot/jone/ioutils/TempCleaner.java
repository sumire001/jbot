package com.bot.jone.ioutils;

import com.bot.jone.config.ImagesConfig;
import com.bot.jone.config.TempCleanConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.Arrays;

/**
 * Temp目录清理工具
 * 定期清理超过指定天数的临时图片文件
 */
@Component
@Slf4j
public class TempCleaner {
    
    @Autowired
    private TempCleanConfiguration config;

    @Autowired
    private ImagesConfig imagesConfig;
    
    /**
     * 启动时检查并清理过期文件（防止长时间停机后积压）
     */
    @PostConstruct
    public void init() {
        log.info("TempCleaner 初始化，配置: 保留{}天, 定时任务: {}", 
            config.getRetentionDays(), config.getCleanCron());
        // 延迟5秒执行，避免影响启动速度
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                cleanOldFiles();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 根据配置的Cron表达式执行清理任务
     */
    @Scheduled(cron = "${bot.temp.clean-cron}")
    public void cleanOldFiles() {
        log.info("开始清理temp目录过期文件...");
        
        File dir = new File(imagesConfig.getTempDir());
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("temp目录不存在: {}", imagesConfig.getTempDir());
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.info("temp目录为空，无需清理");
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long retentionMillis = config.getRetentionDays() * 24L * 60 * 60 * 1000;
        
        int deletedCount = 0;
        long totalSize = 0;
        
        for (File file : files) {
            if (file.isFile()) {
                long fileAge = currentTime - file.lastModified();
                
                if (fileAge > retentionMillis) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        deletedCount++;
                        totalSize += fileSize;
                        log.debug("删除过期文件: {} ({}天)", file.getName(), 
                            fileAge / (24L * 60 * 60 * 1000));
                    } else {
                        log.warn("删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (deletedCount > 0) {
            log.info("清理完成: 删除 {} 个文件，释放 {} KB 空间", 
                deletedCount, totalSize / 1024);
        } else {
            log.info("没有需要清理的文件");
        }
    }
    
    /**
     * 手动触发清理（可用于测试或紧急清理）
     */
    public void manualClean() {
        log.info("手动触发temp目录清理...");
        cleanOldFiles();
    }
    
    /**
     * 获取temp目录当前状态
     * @return 文件数量和总大小
     */
    public String getTempStatus() {
        File dir = new File(imagesConfig.getTempDir());
        if (!dir.exists() || !dir.isDirectory()) {
            return "temp目录不存在";
        }
        
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "temp目录为空";
        }
        
        long totalSize = 0;
        for (File file : files) {
            if (file.isFile()) {
                totalSize += file.length();
            }
        }
        
        return String.format("共 %d 个文件，总大小 %.2f MB", 
            files.length, totalSize / (1024.0 * 1024.0));
    }
}
