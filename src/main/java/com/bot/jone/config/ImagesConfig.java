package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片存储路径配置
 * 对应 application.yml 中的 bot.images
 */
@Data
@Component
@ConfigurationProperties("bot.images")
public class ImagesConfig {

    /** 图片根目录，默认 ./data/images */
    private String rootDir = "./data/images";

    /** 图片下载配置 */
    private DownloadConfig download = new DownloadConfig();

    /** 图片压缩配置 */
    private CompressionConfig compression = new CompressionConfig();

    /**
     * 获取temp目录路径
     */
    public String getTempDir() {
        return rootDir + "/temp";
    }

    /**
     * 获取Pixiv图片缓存目录路径（独立于普通temp）
     */
    public String getPixivTempDir() {
        return rootDir + "/pixiv_temp";
    }

    /**
     * 图片下载配置
     */
    @Data
    public static class DownloadConfig {
        /** 单张图片最大下载大小（MB） */
        private int maxSizeMb = 10;

        /**
         * 获取最大下载大小（字节）
         */
        public long getMaxSizeBytes() {
            return (long) maxSizeMb * 1024 * 1024;
        }
    }

    /**
     * 图片压缩配置
     */
    @Data
    public static class CompressionConfig {
        /** 超过此大小的图片才会被压缩（MB） */
        private int sizeThresholdMb = 1;

        /** 最大分辨率（最长边像素数） */
        private int maxDimension = 1600;

        /** JPEG压缩质量（0.0-1.0） */
        private float jpegQuality = 0.75f;

        /** 是否跳过GIF文件（可能是动画） */
        private boolean skipGif = true;

        /**
         * 获取压缩阈值（字节）
         */
        public long getSizeThresholdBytes() {
            return (long) sizeThresholdMb * 1024 * 1024;
        }
    }
}