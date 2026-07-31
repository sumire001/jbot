package com.bot.jone.ai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class UserMemory {

    private Long qq;
    private String nickname;
    private String traits;
    private Map<Long, String> groupNicknames = new HashMap<>();
    private long lastSeen;
    private int version = 1;

    /**
     * 更新用户特征。
     * 设计说明：generateSummary 已把【已有画像】注入 prompt，并要求 LLM 返回
     * 「合并去重后的完整 traits」，因此这里直接以 LLM 返回值为准（覆盖式存储），
     * 不再做基于旧值的 contains 去重——否则新特征会被旧值判定为“已存在”而丢弃。
     * 本方法仅负责：原子片段拆分 + 长短片段吸收去重 + 安全截断（不在片段中途切断）。
     *
     * @param maxLength 落盘字符上限（来自 MemoryConfig.maxTraitsLength，仅作安全阀）
     */
    public void updateTraits(String newTraits, int maxLength) {
        if (newTraits == null || newTraits.trim().isEmpty()) {
            return;
        }
        // 1. 拆成原子片段
        String[] raw = newTraits.trim().split("[，；、,;]");
        List<String> parts = new ArrayList<>();
        for (String r : raw) {
            String t = r.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 若某片段是另一更长片段的子串，则丢弃较短者，保留信息量更大的那个
            boolean absorbed = false;
            for (int i = 0; i < parts.size(); i++) {
                String existing = parts.get(i);
                if (existing.contains(t) && t.length() < existing.length()) {
                    absorbed = true; // 当前较短，已被较长片段涵盖
                    break;
                } else if (t.contains(existing) && existing.length() < t.length()) {
                    parts.set(i, t); // 当前较长，吸收并替换较短片段
                    absorbed = true;
                    break;
                }
            }
            if (!absorbed) {
                parts.add(t);
            }
        }
        String merged = String.join("，", parts);

        // 2. 安全截断：不超过上限，且不在片段中间切断（避免留下“和”这类半截话）
        if (merged.length() > maxLength) {
            merged = merged.substring(0, maxLength);
            int lastSep = merged.lastIndexOf("，");
            if (lastSep > 0) {
                merged = merged.substring(0, lastSep);
            }
        }

        // 3. 内容未变则跳过赋值与版本递增，避免“照抄旧画像”导致的版本号假更新
        if (merged.equals(this.traits)) {
            return;
        }
        this.traits = merged;
        version++;
    }

    public void updateGroupNickname(Long groupId, String nickname) {
        if (groupId != null && nickname != null && !nickname.isEmpty()) {
            groupNicknames.put(groupId, nickname);
        }
    }

    public String getNicknameForGroup(Long groupId) {
        if (groupId != null && groupNicknames.containsKey(groupId)) {
            return groupNicknames.get(groupId);
        }
        return nickname;
    }
}