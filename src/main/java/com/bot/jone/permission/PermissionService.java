package com.bot.jone.permission;

import com.bot.jone.config.BotAdminConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
@Slf4j
public class PermissionService {
    private final Set<Long> adminQqSet;

    public PermissionService(BotAdminConfiguration properties) {
        // 启动时一次性转换，后续只做内存查询
        this.adminQqSet = new HashSet<>(properties.getQqList());
        log.info("✅ 机器人管理员加载完成，共 {} 人: {}", adminQqSet.size(), adminQqSet);
    }

    /**
     * 判断是否为机器人管理员
     * @param userId QQ号
     * @return true=是管理员
     */
    public boolean isAdmin(long userId) {
        return adminQqSet.contains(userId);
    }
}
