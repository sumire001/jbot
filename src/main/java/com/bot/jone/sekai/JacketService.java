package com.bot.jone.sekai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 曲绘封面服务：拉取 pjsk 封面缩略图，转 base64 data URI 供 HTML 内联（渲染时零网络依赖）。
 *
 * 数据源：storage.exmeaning.com 的 sekai assets 镜像（Moesekai 同源），缩略图约 70KB：
 *   https://storage.exmeaning.com/sekai-{region}-assets/thumbnail/music_jacket/jacket_s_{id}.webp
 * {id} 为 musicId 三位补零（jacket_s_075）。区服资源缺失时自动回退 jp 源。
 *
 * 缓存：内存 Map（带 10 分钟失败重试 TTL）+ 磁盘（data/sekai/jackets/{region}/），
 * 首次查询并行下载，之后命中磁盘。失败返回 null（模板退化为渐变占位），不打断 B30 主流程。
 */
@Slf4j
@Service
public class JacketService {

    /** 失败缓存 TTL：10 分钟内不重复请求已知缺失的封面 */
    private static final long MISS_TTL_MS = 600_000L;

    private final SekaiConfig config;

    private static class Entry {
        final String uri;      // 空串 = 缺失
        final long ts;
        Entry(String uri, long ts) {
            this.uri = uri;
            this.ts = ts;
        }
    }

    /** region:id -> Entry */
    private final Map<String, Entry> memCache = new ConcurrentHashMap<>();

    /** per-key 下载锁，避免同 key 并发重复下载，又不会串行化不同 key */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    public JacketService(SekaiConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(config.getJacketCacheDir()));
        } catch (Exception e) {
            log.warn("[Sekai] 封面缓存目录创建失败 {}", config.getJacketCacheDir());
        }
    }

    /**
     * 批量取封面的 data URI（并行下载）。
     * @return musicId -> data URI；取不到的 musicId 不出现在 map 里
     */
    public Map<Integer, String> dataUris(String region, Collection<Integer> musicIds) {
        Map<Integer, String> out = new ConcurrentHashMap<>();
        if (config.getJacketUrlTemplate() == null || config.getJacketUrlTemplate().isBlank()) {
            return out;
        }
        int par = Math.max(1, config.getJacketParallelism());
        ExecutorService pool = Executors.newFixedThreadPool(par);
        try {
            Future<?>[] fs = musicIds.stream()
                    .map(id -> pool.submit(() -> {
                        String uri = dataUri(region, id);
                        if (uri != null) {
                            out.put(id, uri);
                        }
                    }))
                    .toArray(Future[]::new);
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Sekai] 封面并行下载被中断: {}", e.getMessage());
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** 单个封面的 data URI；失败返回 null */
    public String dataUri(String region, int musicId) {
        String key = (region == null || region.isBlank() ? "jp" : region) + ":" + musicId;
        Entry e = memCache.get(key);
        long now = System.currentTimeMillis();
        if (e != null && (!e.uri.isEmpty() || now - e.ts < MISS_TTL_MS)) {
            return e.uri.isEmpty() ? null : e.uri;
        }
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // double-check：等锁期间可能已被别的线程填好
            e = memCache.get(key);
            if (e != null && (!e.uri.isEmpty() || now - e.ts < MISS_TTL_MS)) {
                return e.uri.isEmpty() ? null : e.uri;
            }
            byte[] bytes = loadFromDisk(region, musicId);
            if (bytes == null) {
                bytes = download(region, musicId);
            }
            String uri = bytes != null && bytes.length > 0 ? toDataUri(bytes) : "";
            memCache.put(key, new Entry(uri, System.currentTimeMillis()));
            return uri.isEmpty() ? null : uri;
        }
    }

    private byte[] loadFromDisk(String region, int musicId) {
        try {
            Path p = diskPath(region, musicId);
            if (Files.exists(p) && Files.size(p) > 0) {
                return Files.readAllBytes(p);
            }
        } catch (Exception ignore) {
            // 读盘失败就当没有，走下载
        }
        return null;
    }

    private byte[] download(String region, int musicId) {
        // 区服源 -> jp 源回退
        for (String r : new String[]{region == null || region.isBlank() ? "jp" : region, "jp"}) {
            if (r.equals("jp") && "jp".equals(region)) {
                continue; // 同一个源不试两次
            }
            String url = config.getJacketUrlTemplate()
                    .replace("{region}", r)
                    .replace("{id}", String.format("%03d", musicId));
            byte[] bytes = httpGet(url);
            if (bytes != null && bytes.length > 0) {
                saveToDisk(r, musicId, bytes);
                return bytes;
            }
        }
        return null;
    }

    private void saveToDisk(String region, int musicId, byte[] bytes) {
        try {
            Path p = diskPath(region, musicId);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (Exception e) {
            log.warn("[Sekai] 封面落盘失败 {}/{}: {}", region, musicId, e.getMessage());
        }
    }

    private Path diskPath(String region, int musicId) {
        String r = region == null || region.isBlank() ? "jp" : region;
        return Paths.get(config.getJacketCacheDir(), r, "jacket_s_" + String.format("%03d", musicId) + ".webp");
    }

    private byte[] httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) Math.max(2000, config.getJacketTimeoutMs()));
            conn.setReadTimeout((int) Math.max(2000, config.getJacketTimeoutMs()));
            conn.setRequestProperty("User-Agent", "J-One-Sekai/1.0");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("[Sekai] 封面下载失败 {}: {}", urlStr, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String toDataUri(byte[] bytes) {
        return "data:image/webp;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
