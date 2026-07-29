package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 画廊命令配置：调用外部 HTML→图片 API（htmlcsstoimage / hcti.io）渲染画廊。
 * 对应 config/application.yml 的 bot.gallery 段。
 *
 * 注意：
 * - 全部为 ASCII 键名，不会被 Spring 的 @ConfigurationProperties 绑定器丢弃。
 * - apiKey / apiUser 需要到 hcti.io 注册后填写；未填写时命令会提示配置。
 */
@Data
@Component
@ConfigurationProperties("bot.gallery")
public class GalleryConfig {

    /** 是否启用画廊命令 */
    private boolean enabled = true;

    /** 渲染 API 地址（hcti.io v1 image 端点） */
    private String apiUrl = "https://hcti.io/v1/image";

    /** hcti 账号 user_id（Basic Auth 用户名） */
    private String apiUser = "";

    /** hcti API Key（Basic Auth 密码） */
    private String apiKey = "";

    /** 每页列数 */
    private int cols = 6;

    /** 每页行数（每页张数 = cols * rows） */
    private int rows = 7;

    /** 渲染视口宽度（像素），对应 demo 的 897 */
    private int width = 897;

    /** 设备像素比（hcti device_scale）：1=严格页宽，2=视网膜清晰但文件更大 */
    private int scale = 1;

    /** 内嵌前把图片最长边缩放到此值（像素），0=不缩放。控制 base64 体积 */
    private int thumbDim = 360;
}
