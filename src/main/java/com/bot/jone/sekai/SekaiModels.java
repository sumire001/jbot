package com.bot.jone.sekai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * pjsk B30 相关领域模型。对应 Haruki 工具箱公有接口返回结构做兼容解析。
 *
 * Haruki 返回的 profile 关键字段（fastjson2 直接映射 camelCase）：
 *   - userMusicResults[] : 每首歌游玩记录
 *       musicId / musicDifficultyType / playResult / fullComboFlg / fullPerfectFlg
 *   - source             : 数据来源（"haruki" 等），顶层
 *   - upload_time        : 上传时间(ms)，snake_case 顶层
 *   - user / userGamedata: 嵌套对象，内有 name / userId（玩家昵称）
 *
 * 为兼容不同数据源的大小写/嵌套差异，顶层与单项均走 fromJson 兜底解析。
 */
@Data
class DetailedProfile {
    private Long userId;
    private String name;
    private Long uploadTime;
    private String source;
    private List<MusicResult> userMusicResults;

    /** 从 Haruki profile 的 JSONObject 容错解析（兼容 camelCase / snake_case / 嵌套 name）。 */
    static DetailedProfile fromJson(JSONObject root) {
        DetailedProfile p = new DetailedProfile();
        if (root == null) {
            return p;
        }
        // 玩家昵称/ID：兼容 user.name / userGamedata.name / userProfile.userId（Haruki 公开读用 userProfile）
        JSONObject user = root.getJSONObject("user");
        if (user == null) {
            user = root.getJSONObject("userGamedata");
        }
        if (user == null) {
            user = root.getJSONObject("userProfile");
        }
        if (user != null) {
            p.setName(user.getString("name"));
            if (user.containsKey("userId")) {
                p.setUserId(user.getLong("userId"));
            }
        }
        if (p.getName() == null) {
            p.setName(root.getString("name"));
        }
        if (p.getUserId() == null && root.containsKey("userId")) {
            p.setUserId(root.getLong("userId"));
        }
        // 上传时间：兼容 upload_time（snake_case）与 uploadTime
        if (root.containsKey("upload_time")) {
            p.setUploadTime(root.getLong("upload_time"));
        } else if (root.containsKey("uploadTime")) {
            p.setUploadTime(root.getLong("uploadTime"));
        }
        p.setSource(root.getString("source"));

        // 成绩列表：兼容 userMusicResults / user_music_results
        JSONArray arr = root.getJSONArray("userMusicResults");
        if (arr == null) {
            arr = root.getJSONArray("user_music_results");
        }
        List<MusicResult> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o instanceof JSONObject jo) {
                    list.add(MusicResult.fromJson(jo));
                }
            }
        }
        p.setUserMusicResults(list);
        return p;
    }
}

/** userMusicResults 单项：每首歌的游玩记录 */
@Data
class MusicResult {
    private int musicId;
    private String musicDifficultyType;   // easy/normal/hard/expert/master/append
    private String playResult;            // clear/full_combo/full_perfect/not_clear
    private boolean fullComboFlg;
    private boolean fullPerfectFlg;

    /** 从容错 JSONObject 解析；兼容 camelCase 与 snake_case（full_combo_flg / all_perfect_flg 等）。 */
    static MusicResult fromJson(JSONObject o) {
        MusicResult r = new MusicResult();
        if (o == null) {
            return r;
        }
        if (o.containsKey("musicId")) {
            r.setMusicId(o.getIntValue("musicId"));
        } else if (o.containsKey("music_id")) {
            r.setMusicId(o.getIntValue("music_id"));
        }
        r.setMusicDifficultyType(pick(o, "musicDifficultyType", "music_difficulty_type", "musicDifficulty"));
        r.setPlayResult(pick(o, "playResult", "play_result"));

        // 布尔标记：兼容 fullComboFlg / full_combo_flg / fullCombo / isFullCombo 等命名
        r.setFullComboFlg(boolOf(o, "fullComboFlg", "full_combo_flg", "fullCombo", "isFullCombo"));
        r.setFullPerfectFlg(boolOf(o, "fullPerfectFlg", "all_perfect_flg", "allPerfect", "isAllPerfect"));
        return r;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.containsKey(k) && o.getString(k) != null) {
                return o.getString(k);
            }
        }
        return null;
    }

    private static boolean boolOf(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.containsKey(k)) {
                return o.getBooleanValue(k);
            }
        }
        return false;
    }
}

/** B30 候选单项 */
@Data
class B30Entry {
    private int musicId;
    private String diff;
    private double level;
    private double constant;
    private double rating;
    private String resultType;            // ap / fc

    public B30Entry() {}

    public B30Entry(int musicId, String diff, double level, double constant, double rating, String resultType) {
        this.musicId = musicId;
        this.diff = diff;
        this.level = level;
        this.constant = constant;
        this.rating = rating;
        this.resultType = resultType;
    }
}

/** B30 计算结果 */
@Data
class B30Result {
    private double b30;
    private List<B30Entry> top30;

    public B30Result() {}

    public B30Result(double b30, List<B30Entry> top30) {
        this.b30 = b30;
        this.top30 = top30;
    }
}
