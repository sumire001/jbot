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
}