package com.bot.jone.sekai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * QQ ↔ 游戏ID 绑定存储。对应 lunabot 的 get_player_bind_id 体系。
 *
 * 风格对齐 J-One 现有存储：JSON 文本文件落盘 + 内存 ConcurrentHashMap 缓存
 * （用户项目无 Redis/MySQL，统一用"java 自带 Map + 文件"）。
 * 支持多账号(slot)、主账号(main)、多区服(region)。
 */
@Slf4j
@Component
public class BindStore {

    @Data
    public static class PlayerBind {
        private long qqId;
        private String region;     // jp / cn / en / tw / kr
        private long gameUid;      // 游戏内 ID
        private int slot = 0;      // 账号序号，0 = 主账号
        private boolean main = false;
    }

    private final SekaiConfig config;
    private final Map<String, List<PlayerBind>> cache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public BindStore(SekaiConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        File dir = new File(config.getDataDir());
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File[] files = dir.listFiles((d, n) -> n.startsWith("bind_") && n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try {
                    String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    List<PlayerBind> list = JSON.parseArray(json, PlayerBind.class);
                    if (list != null && !list.isEmpty()) {
                        cache.put(key(list.get(0).getQqId(), list.get(0).getRegion()), list);
                    }
                } catch (Exception e) {
                    log.warn("[Sekai] 加载绑定失败: {}", f.getName());
                }
            }
        }
        log.info("[Sekai] 绑定数据加载完成，共 {} 个用户", cache.size());
    }

    private String key(long qqId, String region) {
        return qqId + ":" + region;
    }

    public PlayerBind get(long qqId, String region, int slot) {
        List<PlayerBind> list = cache.get(key(qqId, region));
        if (list == null) {
            return null;
        }
        return list.stream().filter(b -> b.getSlot() == slot).findFirst().orElse(null);
    }

    /** 取主账号；若无 main 标记则取列表首个 */
    public PlayerBind getMain(long qqId, String region) {
        List<PlayerBind> list = cache.get(key(qqId, region));
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream().filter(PlayerBind::isMain).findFirst().orElse(list.get(0));
    }

    /** 绑定/覆盖某账号，并落盘 */
    public void bind(long qqId, String region, int slot, long gameUid, boolean main) {
        lock.writeLock().lock();
        try {
            String k = key(qqId, region);
            List<PlayerBind> list = cache.computeIfAbsent(k, x -> new ArrayList<>());
            list.removeIf(b -> b.getSlot() == slot);
            if (main) {
                list.forEach(b -> b.setMain(false));
            }
            PlayerBind b = new PlayerBind();
            b.setQqId(qqId);
            b.setRegion(region);
            b.setGameUid(gameUid);
            b.setSlot(slot);
            b.setMain(main);
            list.add(b);
            save(k, list);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save(String k, List<PlayerBind> list) {
        try {
            Path p = Paths.get(config.getDataDir(), "bind_" + k.replace(":", "_") + ".json");
            String json = JSON.toJSONString(list, JSONWriter.Feature.PrettyFormat);
            Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[Sekai] 保存绑定失败", e);
        }
    }
}
