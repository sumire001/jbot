package com.bot.jone.sekai;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * B30 计算核心。对齐 lunabot music.compose_best30_image 中 music.py:1856-1895 的逻辑。
 *
 * 规则（pjsk 真实规则）：
 *   1. 按 (musicId, difficulty) 分组，同一谱面多条记录只取最好成绩（AP > FC > clear），
 *      避免 FC/AP 重复记录各占一个 top30 名额（对齐 lunabot 的 music_results 分组）。
 *   2. 只统计 AP / FC 的歌（普通 clear 不计，对应原代码 rating=None 被跳过）。
 *   3. 定数 = 该 (musicId, difficulty) 的谱面定数；查不到回退显示等级。
 *   4. AP  : rating = 定数
 *      FC  : rating = 定数 - (等级 >= 33 ? 1 : 1.5)
 *   5. 无等级信息的谱面整首跳过（对齐 lunabot 的 level 缺失 continue）。
 *   6. 按 rating 降序取前 30，B30 = 30 首 rating 的均值。
 */
@Service
public class B30Calculator {

    private final ConstantService constantService;

    public B30Calculator(ConstantService constantService) {
        this.constantService = constantService;
    }

    /**
     * @param results      抓包里的 userMusicResults
     * @param levelProvider 提供 (musicId, diff) 的难度等级（来自定数表；查不到时回退）
     */
    public B30Result compute(List<MusicResult> results, Function<MusicResult, Double> levelProvider) {
        // 1) 按 (musicId, difficulty) 分组取最好成绩
        Map<String, MusicResult> best = new LinkedHashMap<>();
        for (MusicResult r : results) {
            if (r == null) {
                continue;
            }
            String key = r.getMusicId() + ":" + (r.getMusicDifficultyType() == null
                    ? "" : r.getMusicDifficultyType().trim().toLowerCase());
            MusicResult cur = best.get(key);
            if (cur == null || rank(r) > rank(cur)) {
                best.put(key, r);
            }
        }

        // 2) 仅 AP/FC 计入，算 rating
        List<B30Entry> candidates = new ArrayList<>();
        for (MusicResult r : best.values()) {
            boolean ap = r.isFullPerfectFlg();
            boolean fc = r.isFullComboFlg();
            if (!ap && !fc) {
                continue;                   // 仅 AP/FC 计入 B30
            }
            double level = levelProvider.apply(r);
            if (level <= 0) {
                continue;                   // 无等级信息的谱面跳过
            }
            double constant = constantService.get(r.getMusicId(), r.getMusicDifficultyType(), level);
            double rating = ap ? constant : constant - (level >= 33 ? 1 : 1.5);

            candidates.add(new B30Entry(r.getMusicId(), r.getMusicDifficultyType(), level, constant, rating, ap ? "ap" : "fc"));
        }

        candidates.sort(Comparator.comparingDouble(B30Entry::getRating).reversed());
        List<B30Entry> top30 = candidates.stream().limit(30).toList();
        double b30 = top30.stream().mapToDouble(B30Entry::getRating).average().orElse(0.0);

        return new B30Result(b30, top30);
    }

    /** 成绩优先级：AP(2) > FC(1) > 其他(0) */
    private static int rank(MusicResult r) {
        if (r.isFullPerfectFlg()) {
            return 2;
        }
        if (r.isFullComboFlg()) {
            return 1;
        }
        return 0;
    }
}
