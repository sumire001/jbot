package com.bot.jone.status;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 状态面板（/状态 命令）渲染配置。
 * 截图链路与 SekaiImageRenderer 相同：HTML -> 本地无头 Chromium -> PNG。
 */
@Data
@Component
@ConfigurationProperties("bot.status")
public class StatusConfig {

    /** 截图窗口宽度(px)，模板面板 920 宽 + 左右各 20px 留白 */
    private int renderWidth = 960;

    /** 截图窗口高度(px)，模板面板最小 580 高 + 上下留白 */
    private int renderHeight = 620;

    /**
     * proot-distro 二进制路径。部署在 Android/Termux 时经它调 Debian 容器内的 chromium；
     * 若在普通 Linux（chromium 直接在 PATH）运行，置空即可走裸 chromium。
     */
    private String renderProotBin = "/data/data/com.termux/files/usr/bin/proot-distro";

    /** proot 容器名（chromium 装在哪个 proot-distro 容器里），默认 debian */
    private String renderContainer = "debian";

    /** 渲染临时目录（HTML/PNG/profile），需在 Termux home 下以便容器读写，默认 ./data/status-render */
    private String renderTempDir = "./data/status-render";
}
