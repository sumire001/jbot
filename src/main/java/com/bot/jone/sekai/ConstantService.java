package com.bot.jone.sekai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定数表服务。对应 lunabot music.get_music_constants()。
 *
 * 数据源（bot.sekai.constant-url，逗号分隔可填多个，后者覆盖前者）：
 *   - pjskb30 维护的 Google Sheet CSV 导出（base + override 两张表）
 *       CSV 列：0 社区曲名,1 原名,2 定数(diffConstant),3 显示等级(diffLevel),
 *               4 物量,5 难度(Expert/Master/Append),6 songId
 *   - 也兼容历史 JSON（顶层平铺 "musicId:diff" -> 定数）
 *   - 三种前缀：
 *       classpath:xxx      从 jar 内 resources 读（自托管 CSV 放这里最稳）
 *       file:/abs/xxx.csv  从本地文件系统读
 *       http(s)://...      远程拉取（首次拉后落盘，断网可用）
 *
 * 键统一归一化为小写：easy/normal/hard/expert/master/append（与抓包 musicDifficultyType 对齐）。
 * 存储：内存 ConcurrentHashMap（定数 + 显示等级）+ JSON 落盘（与 J-One 一致）。
 */
@Slf4j
@Service
public class ConstantService {

    private final SekaiConfig config;
    private volatile Map<String, Double> constants = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> levels = new ConcurrentHashMap<>();
    /** 等级显示文本（保留 "37+" / "APD 38" 原样），key 同 constants */
    private volatile Map<String, String> levelTexts = new ConcurrentHashMap<>();
    /** 歌名（中文优先，回退 JP/社区英文），key = musicId */
    private volatile Map<String, String> names = new ConcurrentHashMap<>();
    /** 日文原名（回退社区英文名），key = musicId */
    private volatile Map<String, String> jpNames = new ConcurrentHashMap<>();

    public ConstantService(SekaiConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        loadFromFile();   // 先加载本地缓存（断网也能跑）
        refresh();       // 再异步拉取最新
    }

    @Scheduled(fixedDelayString = "${bot.sekai.constant-refresh-ms:86400000}")
    public void refresh() {
        String url = config.getConstantUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Double> nextC = new ConcurrentHashMap<>();
        Map<String, Integer> nextL = new ConcurrentHashMap<>();
        Map<String, String> nextLT = new ConcurrentHashMap<>();
        Map<String, String> nextN = new ConcurrentHashMap<>();
        Map<String, String> nextJp = new ConcurrentHashMap<>();
        boolean any = false;
        for (String src : url.split(",")) {
            src = src.trim();
            if (src.isEmpty()) {
                continue;
            }
            try {
                String text = readSource(src);
                if (text == null || text.isBlank()) {
                    continue;
                }
                loadInto(text, nextC, nextL, nextLT, nextN, nextJp);
                any = true;
            } catch (Exception e) {
                log.warn("[Sekai] 定数源读取失败 {}: {}", src, e.getMessage());
            }
        }
        if (!any) {
            return;
        }
        constants = nextC;
        levels = nextL;
        levelTexts = nextLT;
        names = nextN;
        jpNames = nextJp;
        saveToFile();
        log.info("[Sekai] 定数表刷新完成，共 {} 条", nextC.size());
    }

    /** 读取一个来源：classpath / file / http(s) */
    private String readSource(String src) throws IOException {
        if (src.startsWith("classpath:")) {
            String path = src.substring("classpath:".length());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    log.warn("[Sekai] classpath 资源不存在: {}", path);
                    return null;
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (src.startsWith("file:") || !src.contains("://")) {
            String p = src.startsWith("file:") ? src.substring("file:".length()) : src;
            java.nio.file.Path pp = Paths.get(p);
            if (!Files.exists(pp)) {
                log.warn("[Sekai] 本地文件不存在: {}", p);
                return null;
            }
            return Files.readString(pp, StandardCharsets.UTF_8);
        }
        // 远程 http(s)
        HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "J-One-Sekai/1.0");
        try {
            if (conn.getResponseCode() != 200) {
                return null;
            }
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /** 嗅探格式：CSV（pjskb30）或 历史 JSON，解析进 map */
    private void loadInto(String text, Map<String, Double> outC, Map<String, Integer> outL,
                          Map<String, String> outLT, Map<String, String> outN, Map<String, String> outJp) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            parseJsonConstants(JSON.parseObject(trimmed), outC);
            return;
        }
        parseCsv(text, outC, outL, outLT, outN, outJp);
    }

    // ---- CSV（pjskb30 列结构）----
    // 0 communityName(EN), 1 originalName(JP), 2 diffConstant, 3 diffLevel(如 37+/APD 38),
    // 4 noteCount, 5 difficulty(Expert/Master/Append), 6 songId, 7 zhTitle(自托管合并时追加)
    private void parseCsv(String text, Map<String, Double> outC, Map<String, Integer> outL,
                          Map<String, String> outLT, Map<String, String> outN, Map<String, String> outJp) {
        List<String[]> rows = parseCsvRows(text);
        if (rows.isEmpty()) {
            return;
        }
        int start = isHeader(rows.get(0)) ? 1 : 0;
        for (int i = start; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length <= 6) {
                continue;
            }
            String songId = r[6].trim();
            String diff = normalizeDiff(r[5]);
            Double constant = tryDouble(r[2]);
            if (songId.isEmpty() || constant == null) {
                continue;
            }
            String key = songId + ":" + diff;
            outC.put(key, constant);
            String lvInt = leadingInt(r[3]);
            if (lvInt != null) {
                outL.put(key, Integer.parseInt(lvInt));
                outLT.put(key, r[3].trim());
            }
            // 歌名：中文 > JP 原名 > 社区英文名（同一首歌多行只取首次）
            String zh = r.length > 7 ? r[7].trim() : "";
            String jp = r.length > 1 ? r[1].trim() : "";
            String en = r.length > 0 ? r[0].trim() : "";
            String display = !zh.isEmpty() ? zh : (!jp.isEmpty() ? jp : en);
            if (!display.isEmpty()) {
                outN.putIfAbsent(songId, display);
            }
            // 日文原名：JP 优先，回退社区英文名
            String jpDisplay = !jp.isEmpty() ? jp : en;
            if (!jpDisplay.isEmpty()) {
                outJp.putIfAbsent(songId, jpDisplay);
            }
        }
    }

    /** 从等级文本中取首个整数："37+"->"37", "APD 38"->"38", "26"->"26"；取不到返回 null */
    private static String leadingInt(String lv) {
        if (lv == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(lv);
        return m.find() ? m.group(1) : null;
    }

    private boolean isHeader(String[] r) {
        return r.length > 6 && !isInteger(r[6].trim());
    }

    // ---- 历史 JSON：顶层平铺 "musicId:diff" -> 定数 ----
    private void parseJsonConstants(JSONObject root, Map<String, Double> outC) {
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number) {
                outC.put(e.getKey(), ((Number) v).doubleValue());
            }
        }
    }

    /** 取定数；查不到回退等级，再否则 fallback。 */
    public double get(int musicId, String diff, double fallbackLevel) {
        String k = key(musicId, diff);
        Double c = constants.get(k);
        if (c != null) {
            return c;
        }
        Integer lv = levels.get(k);
        return lv != null ? lv : fallbackLevel;
    }

    /** 取显示等级（整数）；查不到返回 0。 */
    public int getLevel(int musicId, String diff) {
        Integer lv = levels.get(key(musicId, diff));
        return lv != null ? lv : 0;
    }

    /** 取等级显示文本（如 "37+" / "APD 38"）；查不到返回 null。 */
    public String getLevelText(int musicId, String diff) {
        return levelTexts.get(key(musicId, diff));
    }

    /** 取歌名（中文优先）；查不到返回 null。 */
    public String getName(int musicId) {
        return names.get(String.valueOf(musicId));
    }

    /** 取日文原名（回退社区英文名）；查不到返回 null。 */
    public String getOriginalName(int musicId) {
        return jpNames.get(String.valueOf(musicId));
    }

    private static String key(int musicId, String diff) {
        return musicId + ":" + normalizeDiff(diff);
    }

    private static String normalizeDiff(String d) {
        if (d == null) {
            return "master";
        }
        switch (d.trim().toLowerCase()) {
            case "easy":   return "easy";
            case "normal": return "normal";
            case "hard":   return "hard";
            case "expert": return "expert";
            case "append": return "append";
            default:       return "master";
        }
    }

    // ---- RFC4180 轻量 CSV 行解析（支持引号包裹与 "" 转义；跨行字段按简单处理）----
    private static List<String[]> parseCsvRows(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(cur.toString());
                cur.setLength(0);
                rows.add(fields.toArray(new String[0]));
                fields.clear();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !fields.isEmpty()) {
            fields.add(cur.toString());
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

    private static Double tryDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer tryInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 落盘 / 读取 ----
    private void saveToFile() {
        try {
            JSONObject holder = new JSONObject();
            holder.put("constants", constants);
            holder.put("levels", levels);
            holder.put("levelTexts", levelTexts);
            holder.put("names", names);
            holder.put("jpNames", jpNames);
            File dir = new File(config.getDataDir());
            dir.mkdirs();
            Files.write(Paths.get(config.getDataDir(), "constants.json"),
                    holder.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[Sekai] 定数表落盘失败", e);
        }
    }

    private void loadFromFile() {
        try {
            java.nio.file.Path p = Paths.get(config.getDataDir(), "constants.json");
            if (!Files.exists(p)) {
                return;
            }
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            JSONObject h = JSON.parseObject(json);
            if (h == null) {
                return;
            }
            JSONObject c = h.getJSONObject("constants");
            if (c != null) {
                Map<String, Double> mc = new ConcurrentHashMap<>();
                for (Map.Entry<String, Object> e : c.entrySet()) {
                    if (e.getValue() instanceof Number) {
                        mc.put(e.getKey(), ((Number) e.getValue()).doubleValue());
                    }
                }
                constants = mc;
            }
            JSONObject l = h.getJSONObject("levels");
            if (l != null) {
                Map<String, Integer> ml = new ConcurrentHashMap<>();
                for (Map.Entry<String, Object> e : l.entrySet()) {
                    if (e.getValue() instanceof Number) {
                        ml.put(e.getKey(), ((Number) e.getValue()).intValue());
                    }
                }
                levels = ml;
            }
            levelTexts = new ConcurrentHashMap<>(loadStringMap(h.getJSONObject("levelTexts")));
            names = new ConcurrentHashMap<>(loadStringMap(h.getJSONObject("names")));
            jpNames = new ConcurrentHashMap<>(loadStringMap(h.getJSONObject("jpNames")));
            log.info("[Sekai] 定数表从本地加载，共 {} 条", constants.size());
        } catch (Exception e) {
            log.warn("[Sekai] 定数表读取失败", e);
        }
    }

    private static Map<String, String> loadStringMap(JSONObject obj) {
        Map<String, String> m = new ConcurrentHashMap<>();
        if (obj == null) {
            return m;
        }
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (e.getValue() != null) {
                m.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return m;
    }
}
