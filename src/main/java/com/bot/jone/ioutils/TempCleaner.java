package com.bot.jone.ioutils;

import com.bot.jone.config.ImagesConfig;
import com.bot.jone.config.TempCleanConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;

/**
 * Temp目录清理工具
 * 三重保障：时间过期 + 容量上限 + 文件数量限制
 */
@Component
@Slf4j
public class TempCleaner {

    @Autowired
    private TempCleanConfiguration config;

    @Autowired
    private ImagesConfig imagesConfig;

    /**
     * 启动时检查并清理过期文件
     */
    @PostConstruct
    public void init() {
        log.info("[TempCleaner] 初始化，temp配置: 保留{}天, 容量上限{}MB, 定时任务: {}",
            config.getRetentionDays(), config.getMaxDirSizeMb(), config.getCleanCron());
        log.info("[TempCleaner] Pixiv缓存配置: 保留{}小时, 容量上限{}MB, 定时任务: {}",
            config.getPixivRetentionHours(), config.getPixivMaxDirSizeMb(), config.getPixivCleanCron());

        new Thread(() -> {
            try {
                Thread.sleep(5000);
                cleanOldFiles();
                cleanPixivTempFiles();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * 根据Cron表达式执行 temp 目录清理
     * 策略1: 删除超过保留天数的文件
     * 策略2: 如果目录总大小超过上限，强制删除最旧文件
     */
    @Scheduled(cron = "${bot.temp.clean-cron}")
    public void cleanOldFiles() {
        log.info("[Temp清理] 开始清理temp目录过期文件...");

        File dir = new File(imagesConfig.getTempDir());
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[Temp清理] temp目录不存在: {}", imagesConfig.getTempDir());
            return;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.info("[Temp清理] temp目录为空，无需清理");
            return;
        }

        long currentTime = System.currentTimeMillis();
        long retentionMillis = config.getRetentionDays() * 24L * 60 * 60 * 1000;

        int timeDeleteCount = 0;
        long totalDeletedSize = 0;

        // 策略1: 按时间删除过期文件
        for (File file : files) {
            if (file.isFile()) {
                long fileAge = currentTime - file.lastModified();

                if (fileAge > retentionMillis) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        timeDeleteCount++;
                        totalDeletedSize += fileSize;
                        log.debug("[Temp清理] 删除过期文件: {} ({}天前)",
                            file.getName(),
                            fileAge / (24L * 60 * 60 * 1000));
                    } else {
                        log.warn("[Temp清理] 删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        // 策略2: 按目录总大小上限，强制删除最旧文件
        long maxDirBytes = config.getMaxDirSizeMb() * 1024L * 1024;
        long currentDirSize = 0;
        File[] remaining = dir.listFiles();
        if (remaining != null) {
            for (File file : remaining) {
                if (file.isFile()) currentDirSize += file.length();
            }
        }

        int capacityDeleteCount = 0;
        while (currentDirSize > maxDirBytes && remaining != null && remaining.length > 0) {
            File oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (File file : remaining) {
                if (file.isFile() && file.lastModified() < oldestTime) {
                    oldest = file;
                    oldestTime = file.lastModified();
                }
            }
            if (oldest == null) break;

            long oldSize = oldest.length();
            if (oldest.delete()) {
                capacityDeleteCount++;
                totalDeletedSize += oldSize;
                currentDirSize -= oldSize;
                log.warn("[Temp清理] 容量超限强制删除: {} ({:.2f}MB)",
                    oldest.getName(), (double)oldSize / (1024 * 1024));
            } else {
                break;
            }
            remaining = dir.listFiles();
        }

        if (timeDeleteCount + capacityDeleteCount > 0) {
            log.info("[Temp清理] 完成: 过期删除{}个 + 容量超限删除{}个，共释放{:.2f}MB，当前目录: {:.2f}MB",
                timeDeleteCount,
                capacityDeleteCount,
                (double)totalDeletedSize / (1024 * 1024),
                (double)currentDirSize / (1024 * 1024));
        } else {
            log.info("[Temp清理] 无需清理，当前目录: {:.2f}MB / {}MB",
                (double)currentDirSize / (1024 * 1024),
                config.getMaxDirSizeMb());
        }
    }

    /**
     * Pixiv缓存清理（更严格的策略）
     * 策略1: 删除超过保留小时数的文件
     * 策略2: 超过容量上限时，从最旧文件开始删
     */
    @Scheduled(cron = "${bot.temp.pixiv-clean-cron}")
    public void cleanPixivTempFiles() {
        log.info("[Pixiv清理] 开始清理pixiv_temp目录...");

        File dir = new File(imagesConfig.getPixivTempDir());
        if (!dir.exists() || !dir.isDirectory()) {
            log.info("[Pixiv清理] pixiv_temp目录不存在: {}（首次运行正常）", imagesConfig.getPixivTempDir());
            return;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.info("[Pixiv清理] pixiv_temp目录为空，无需清理");
            return;
        }

        long currentTime = System.currentTimeMillis();
        long retentionMillis = config.getPixivRetentionHours() * 60L * 60 * 1000;

        int timeDeleteCount = 0;
        long totalDeletedSize = 0;

        for (File file : files) {
            if (file.isFile()) {
                long fileAge = currentTime - file.lastModified();

                if (fileAge > retentionMillis) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        timeDeleteCount++;
                        totalDeletedSize += fileSize;
                        log.debug("[Pixiv清理] 删除过期文件: {} ({}小时前)",
                            file.getName(),
                            fileAge / (60L * 60 * 1000));
                    } else {
                        log.warn("[Pixiv清理] 删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        long maxDirBytes = config.getPixivMaxDirSizeMb() * 1024L * 1024;
        long currentDirSize = 0;
        File[] remaining = dir.listFiles();
        if (remaining != null) {
            for (File file : remaining) {
                if (file.isFile()) currentDirSize += file.length();
            }
        }

        int capacityDeleteCount = 0;
        while (currentDirSize > maxDirBytes && remaining != null && remaining.length > 0) {
            File oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (File file : remaining) {
                if (file.isFile() && file.lastModified() < oldestTime) {
                    oldest = file;
                    oldestTime = file.lastModified();
                }
            }
            if (oldest == null) break;

            long oldSize = oldest.length();
            if (oldest.delete()) {
                capacityDeleteCount++;
                totalDeletedSize += oldSize;
                currentDirSize -= oldSize;
                log.warn("[Pixiv清理] 容量超限强制删除: {} ({:.2f}MB)",
                    oldest.getName(), (double)oldSize / (1024 * 1024));
            } else {
                break;
            }
            remaining = dir.listFiles();
        }

        if (timeDeleteCount + capacityDeleteCount > 0) {
            log.info("[Pixiv清理] 完成: 过期删除{}个 + 容量超限删除{}个，共释放{:.2f}MB，当前目录: {:.2f}MB / {}MB",
                timeDeleteCount,
                capacityDeleteCount,
                (double)totalDeletedSize / (1024 * 1024),
                (double)currentDirSize / (1024 * 1024),
                config.getPixivMaxDirSizeMb());
        } else {
            log.info("[Pixiv清理] 无需清理，当前目录: {:.2f}MB / {}MB",
                (double)currentDirSize / (1024 * 1024),
                config.getPixivMaxDirSizeMb());
        }
    }

    /**
     * 手动触发清理
     */
    public void manualClean() {
        log.info("手动触发temp目录清理...");
        cleanOldFiles();
    }

    /**
     * 获取两个缓存目录的健康报告
     */
    public String getStatusReport() {
        StringBuilder sb = new StringBuilder("缓存健康报告\n");

        File tempDir = new File(imagesConfig.getTempDir());
        File pixivDir = new File(imagesConfig.getPixivTempDir());

        if (tempDir.exists() && tempDir.isDirectory()) {
            File[] files = tempDir.listFiles();
            long size = 0;
            if (files != null) for (File f : files) if (f.isFile()) size += f.length();
            sb.append(String.format("temp: %d个文件 / %.2fMB / 上限%dMB\n",
                files == null ? 0 : files.length,
                (double)size / (1024 * 1024),
                config.getMaxDirSizeMb()));
        } else {
            sb.append("temp: 目录不存在\n");
        }

        if (pixivDir.exists() && pixivDir.isDirectory()) {
            File[] files = pixivDir.listFiles();
            long size = 0;
            if (files != null) for (File f : files) if (f.isFile()) size += f.length();
            sb.append(String.format("pixiv_temp: %d个文件 / %.2fMB / 上限%dMB",
                files == null ? 0 : files.length,
                (double)size / (1024 * 1024),
                config.getPixivMaxDirSizeMb()));
        } else {
            sb.append("pixiv_temp: 目录不存在");
        }

        return sb.toString();
    }
}
