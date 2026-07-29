package com.bot.jone.permission;

import com.bot.jone.config.BotAdminConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class PermissionService {
    /**
     * 管理员 QQ 集合，使用 volatile 引用保证热更新时的可见性（赋值为已构建好的不可变快照）
     */
    private volatile Set<Long> adminQqSet;

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

    /**
     * 获取当前管理员 QQ 列表快照
     */
    public List<Long> getAdminList() {
        return new ArrayList<>(adminQqSet);
    }

    /**
     * 热更新管理员列表（WebUI 修改后调用，立即生效）
     */
    public void updateAdminList(List<Long> newList) {
        Set<Long> next = new HashSet<>(newList);
        this.adminQqSet = next;
        log.info("✅ 管理员列表已热更新，共 {} 人: {}", next.size(), next);
    }
}
