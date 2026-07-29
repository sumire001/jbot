package com.bot.jone.ai.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class UserMemory {

    private Long qq;
    private String nickname;
    private String traits;
    private Map<Long, String> groupNicknames = new HashMap<>();
    private long lastSeen;
    private int version = 1;

    public void updateTraits(String newTraits) {
        if (newTraits == null || newTraits.trim().isEmpty()) {
            return;
        }
        if (traits == null || traits.isEmpty()) {
            traits = newTraits.trim();
        } else {
            for (String trait : newTraits.trim().split("，")) {
                String t = trait.trim();
                if (!t.isEmpty() && !traits.contains(t)) {
                    traits += "，" + t;
                }
            }
        }
        if (traits.length() > 200) {
            traits = traits.substring(0, 200);
        }
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