package com.bot.jone.ai.model;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class GroupMemory {

    private Long groupId;
    private String summary;
    private Set<Long> memberIds = new HashSet<>();
    private long lastSummaryTime;
    private int version = 1;

    public void addMember(Long qq) {
        memberIds.add(qq);
    }

    public void removeMember(Long qq) {
        memberIds.remove(qq);
    }

    public void updateSummary(String newSummary) {
        if (newSummary != null && !newSummary.trim().isEmpty()) {
            this.summary = newSummary.trim();
            this.lastSummaryTime = System.currentTimeMillis();
            version++;
        }
    }

    public void retainTopMembers(int maxCount) {
        if (memberIds.size() <= maxCount) {
            return;
        }
        Set<Long> toRemove = new HashSet<>();
        int i = 0;
        for (Long qq : memberIds) {
            if (i >= maxCount) {
                toRemove.add(qq);
            }
            i++;
        }
        memberIds.removeAll(toRemove);
    }
}