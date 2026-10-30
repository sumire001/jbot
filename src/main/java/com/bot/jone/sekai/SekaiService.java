package com.bot.jone.sekai;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B30 业务编排：
 *   绑定 -> 拉取 profile（内存 Map 缓存 + TTL）-> B30 计算 -> 渲染成图。
 *
 * 风格对齐 J-One：HTTP 用 HttpURLConnection、JSON 用 fastjson2、缓存用 java 自带 Map、
 * 渲染用本地无头 Chromium（见 SekaiImageRenderer）、回消息用 OneBotApiClient。
 */
@Slf4j
@Service
public class SekaiService {

    private final SekaiConfig config;
    private final SekaiFetcher fetcher;
    private final BindStore bindStore;
    private final ConstantService constantService;
    private final B30Calculator calculator;
    private final SekaiImageRenderer renderer;
    private final JacketService jacketService;

    /** profile 内存缓存：key = region:uid，带时间戳 TTL（java 自带 Map） */
    private final Map<String, CacheEntry> profileCache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final String json;
        final long ts;
        CacheEntry(String json, long ts) {
            this.json = json;
            this.ts = ts;
        }
    }

    public SekaiService(SekaiConfig config, SekaiFetcher fetcher, BindStore bindStore,
                        ConstantService constantService, B30Calculator calculator,
                        SekaiImageRenderer renderer, JacketService jacketService) {
        this.config = config;
        this.fetcher = fetcher;
        this.bindStore = bindStore;
        this.constantService = constantService;
        this.calculator = calculator;
        this.renderer = renderer;
        this.jacketService = jacketService;
    }

    /** 绑定游戏ID（slot 0 = 主账号） */
    public void bind(long qqId, String region, int slot, long gameUid, boolean main) {
        bindStore.bind(qqId, region, slot, gameUid, main);
    }

    /** 生成 B30 图片字节；失败抛异常（由调用方转成提示文本） */
    public byte[] makeB30Image(long qqId, String region, int slot) {
        BindStore.PlayerBind bind = bindStore.get(qqId, region, slot);
        if (bind == null) {
            throw new IllegalStateException("未绑定，请先 /ickb30 bind <游戏ID>");
        }
        String baseUrl = config.getSuiteApiUrl().get(region);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置 " + region + " 的 suite-api-url");
        }
        String json = fetchProfileCached(region, bind.getGameUid());
        DetailedProfile profile = DetailedProfile.fromJson(JSON.parseObject(json));
        return renderProfile(profile, region);
    }

    /** 已拿到 profile 的渲染入口（供测试/复用） */
    public byte[] renderProfile(DetailedProfile profile, String region) {
        if (profile.getUserMusicResults() == null || profile.getUserMusicResults().isEmpty()) {
            throw new IllegalStateException("该玩家暂无成绩数据（可能未游玩 / 未上传到 Haruki 工具箱）");
        }
        B30Result result = calculator.compute(profile.getUserMusicResults(),
                r -> (double) constantService.getLevel(r.getMusicId(), r.getMusicDifficultyType()));
        String html = buildHtml(profile, result, region);
        return renderer.render(html);
    }

    private String fetchProfileCached(String region, long uid) {
        String key = region + ":" + uid;
        CacheEntry e = profileCache.get(key);
        long now = System.currentTimeMillis();
        if (e != null && now - e.ts < config.getProfileCacheTtlMs()) {
            return e.json;
        }
        String json = fetcher.fetchProfile(config.getSuiteApiUrl().get(region), uid);
        profileCache.put(key, new CacheEntry(json, now));
        return json;
    }

    // HTML 模板：渐变底 + 白卡 + 6 列网格；封面 background-image 内联 base64
    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <style>
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
              font-family: -apple-system, "Microsoft YaHei", "Noto Sans CJK SC", "PingFang SC", sans-serif;
              background: linear-gradient(135deg, #ffd9e8 0%, #dde7ff 48%, #ecdcff 100%);
              padding: 22px; color: #2b2b3a;
            }
            .card {
              background: #ffffff; border-radius: 20px; padding: 22px 24px 16px;
              box-shadow: 0 10px 34px rgba(84, 72, 140, 0.14);
            }
            .head { display: flex; align-items: flex-start; gap: 20px; }
            .uid { font-size: 24px; font-weight: 800; letter-spacing: .3px; }
            .sub { font-size: 13px; color: #8a8aa0; margin-top: 7px; line-height: 1.75; }
            .head-right { margin-left: auto; display: flex; align-items: center; gap: 20px; }
            .meta { font-size: 12px; color: #9a9ab0; text-align: right; line-height: 1.9; white-space: nowrap; }
            .rating-label { font-size: 13px; color: #8a8aa0; text-align: right; }
            .rating-v { font-size: 46px; font-weight: 800; line-height: 1.05; }
            .grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-top: 20px; }
            .item {
              position: relative; background: #fbfbfe; border: 1px solid #edeef6;
              border-radius: 14px; padding: 9px; display: flex; gap: 10px; align-items: center; min-width: 0;
            }
            .cover {
              width: 54px; height: 54px; border-radius: 9px; flex: 0 0 auto;
              background: linear-gradient(135deg, #e9e9f6, #d7d7ee);
              background-size: cover; background-position: center;
            }
            .lv {
              position: absolute; top: 5px; left: 5px; font-size: 11px; font-weight: 700; color: #fff;
              padding: 1px 6px; border-radius: 6px; background: #9aa0b8; line-height: 1.4;
            }
            .lv.easy   { background: #4fbf6b; }
            .lv.normal { background: #4f8ef7; }
            .lv.hard   { background: #f5a623; }
            .lv.expert { background: #ff6f91; }
            .lv.master { background: #a35cd0; }
            .lv.append { background: #35bfa8; }
            .txt { flex: 1; min-width: 0; }
            .title {
              font-size: 12.5px; font-weight: 700; line-height: 1.35; height: 34px; overflow: hidden;
              display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; word-break: break-all;
            }
            .stat { font-size: 12px; color: #6f6f85; margin-top: 4px; white-space: nowrap; }
            .stat b { color: #e0447a; font-size: 13.5px; }
            .tag {
              display: inline-block; font-size: 10px; font-weight: 700; letter-spacing: .6px; color: #fff;
              padding: 2px 8px; border-radius: 999px; margin-top: 6px;
            }
            .tag.ap { background: linear-gradient(90deg, #ff9bb2, #ff5c8a); }
            .tag.fc { background: linear-gradient(90deg, #8fb6ff, #5b7cfa); }
            .foot { text-align: center; font-size: 11px; color: #a3a3b8; margin-top: 14px; }
            </style>
            </head>
            <body>
            <div class="card">
              <div class="head">
                <div>
                  <div class="uid">__REGION__ · __UID__</div>
                  <div class="sub">更新时间: __UPDATE__<br>数据来源: Haruki ToolBox</div>
                </div>
                <div class="head-right">
                  <div class="meta">
                    <div>定数更新: __CONST_DATE__</div>
                    <div>定数来源: pjskb30</div>
                    <div>计算方式: 33+ FC-1 / 其余 FC-1.5 / AP±0</div>
                  </div>
                  <div>
                    <div class="rating-label">B30 Rating</div>
                    <div class="rating-v">__B30__</div>
                  </div>
                </div>
              </div>
              <div class="grid">__ROWS__</div>
            </div>
            <div class="foot">Generated by J-One · 曲绘 storage.exmeaning.com</div>
            </body>
            </html>
            """;

    private String buildHtml(DetailedProfile p, B30Result result, String region) {
        // 封面：top30 去重后并行拉取（内存/磁盘缓存）
        List<Integer> ids = new ArrayList<>();
        for (B30Entry e : result.getTop30()) {
            if (!ids.contains(e.getMusicId())) {
                ids.add(e.getMusicId());
            }
        }
        Map<Integer, String> jackets = jacketService.dataUris(region, ids);

        StringBuilder rows = new StringBuilder();
        for (B30Entry e : result.getTop30()) {
            String title = constantService.getOriginalName(e.getMusicId());
            if (title == null || title.isEmpty()) {
                title = "#" + e.getMusicId();
            }
            int lv = (int) Math.round(e.getLevel());
            if (lv <= 0) {
                lv = constantService.getLevel(e.getMusicId(), e.getDiff());
            }
            double constant = e.getConstant() > 0
                    ? e.getConstant()
                    : constantService.get(e.getMusicId(), e.getDiff(), e.getLevel());
            String cover = jackets.get(e.getMusicId());
            String coverStyle = cover != null
                    ? " style='background-image:url(\"" + cover + "\")'"
                    : "";
            String tagText = "ap".equals(e.getResultType()) ? "ALL PERFECT" : "FULL COMBO";
            rows.append("<div class='item'>")
                    .append("<div class='cover'").append(coverStyle).append("></div>")
                    .append("<div class='lv ").append(e.getDiff()).append("'>").append(lv).append("</div>")
                    .append("<div class='txt'>")
                    .append("<div class='title'>").append(escape(title)).append("</div>")
                    .append("<div class='stat'>").append(String.format("%.1f", constant))
                    .append(" → <b>").append(String.format("%.1f", e.getRating())).append("</b></div>")
                    .append("<span class='tag ").append(e.getResultType()).append("'>")
                    .append(tagText).append("</span>")
                    .append("</div></div>");
        }
        // Haruki 公开接口不返回玩家昵称：显示区服 + 游戏 ID
        String uid = p.getUserId() != null ? String.valueOf(p.getUserId()) : "未知";
        String update = "未知";
        if (p.getUploadTime() != null && p.getUploadTime() > 0) {
            Instant t = Instant.ofEpochSecond(p.getUploadTime());
            update = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault()).format(t)
                    + "（" + ChronoUnit.DAYS.between(t, Instant.now()) + " 天前）";
        }
        String constDate = config.getConstantsUpdatedAt();
        if (constDate == null || constDate.isBlank()) {
            constDate = "未知";
        }
        return TEMPLATE
                .replace("__REGION__", region == null ? "" : region.toUpperCase())
                .replace("__UID__", escape(uid))
                .replace("__UPDATE__", escape(update))
                .replace("__CONST_DATE__", escape(constDate))
                .replace("__B30__", String.format("%.2f", result.getB30()))
                .replace("__ROWS__", rows.toString());
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
