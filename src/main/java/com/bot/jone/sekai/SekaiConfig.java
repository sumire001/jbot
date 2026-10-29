package com.bot.jone.sekai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * pjsk 相关配置。对应 lunabot 的 gameapi.GameApiConfig（按 region 配 suite_api_url）。
 * 字段均为 ASCII 键，可直接被 Spring @ConfigurationProperties 绑定（不受 CommandConfig 那种中文键限制）。
 *
 * 数据源改为 Haruki 工具箱「公有读」接口（公开读，无需 token / OAuth2）：
 *   GET https://suite-api.haruki.seiunx.com/public/{region}/suite/{uid}
 * 后续若要换自建 gameapi 后端，只需改 suiteApiUrl（并视情况填 apiToken）。
 */
@Data
@Component
@EnableScheduling
@ConfigurationProperties("bot.sekai")
public class SekaiConfig {

    /** 各服数据源 base URL，支持 {uid} 占位符；按区服填 Haruki 公有接口 */
    private Map<String, String> suiteApiUrl;

    /**
     * 数据源鉴权 token。Haruki 公有读接口无需 token（留空即可）；
     * 仅当 suiteApiUrl 指向需要 Bearer 鉴权的自建 gameapi 后端时才填。
     */
    private String apiToken;

    /**
     * 默认获取模式（default / latest / local / haruki）。
     * 注意：Haruki 公有读接口不支持 mode / filter 参数，此字段仅预留给自建后端，对公有读无效。
     */
    private String defaultMode = "default";

    /** 定数表 JSON 地址（sekai.best masterdata 或 pjskb30），定时拉取 */
    private String constantUrl;

    /** 定数表刷新间隔(ms)，默认 1 天 */
    private long constantRefreshMs = 86_400_000L;

    /** profile 内存缓存 TTL(ms)，默认 1 小时 */
    private long profileCacheTtlMs = 3_600_000L;

    /** 数据目录（绑定/定数表落盘），默认 ./data/sekai */
    private String dataDir = "./data/sekai";

    /** 定数表数据日期（展示在图头部，如 "2026-10-24"） */
    private String constantsUpdatedAt = "";

    // ---- 曲绘封面（storage.exmeaning.com 镜像的 pjsk  assets）----

    /** 封面图 URL 模板：{region}=区服, {id}=musicId(3位补零)。置空则关闭封面 */
    private String jacketUrlTemplate =
            "https://storage.exmeaning.com/sekai-{region}-assets/thumbnail/music_jacket/jacket_s_{id}.webp";

    /** 封面磁盘缓存目录，默认 ./data/sekai/jackets */
    private String jacketCacheDir = "./data/sekai/jackets";

    /** 单张封面下载超时(ms)，默认 8000 */
    private long jacketTimeoutMs = 8000;

    /** 封面并行下载线程数，默认 8 */
    private int jacketParallelism = 8;

    // ---- B30 图片渲染（本地无头 Chromium，见 SekaiImageRenderer）----

    /** 渲染图宽度(px)，默认 960（6 列网格） */
    private int renderWidth = 960;

    /**
     * proot-distro 二进制路径。部署在 Android/Termux 时经它调 Debian 容器内的 chromium；
     * 若在普通 Linux（chromium 直接在 PATH）运行，置空即可走裸 chromium。
     */
    private String renderProotBin = "/data/data/com.termux/files/usr/bin/proot-distro";

    /** proot 容器名（chromium 装在哪个 proot-distro 容器里），默认 debian */
    private String renderContainer = "debian";

    /** 渲染临时目录（HTML/PNG/profile），需在 Termux home 下以便 proot 容器读写，默认 ./data/sekai/render */
    private String renderTempDir = "./data/sekai/render";
}
