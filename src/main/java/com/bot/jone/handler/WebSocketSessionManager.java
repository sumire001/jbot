package com.bot.jone.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket会话管理器
 * 用于管理所有活跃的WebSocket连接
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /**
     * 获取所有活跃的WebSocket会话
     */
    public CopyOnWriteArraySet<Session> getSessions() {
        return OneBotWebSocketHandler.getSessions();
    }

    /**
     * 获取第一个可用的WebSocket会话（连接中且已打开）
     * 会自动过滤掉已关闭的session
     */
    public Session getFirstSession() {
        CopyOnWriteArraySet<Session> sessions = getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        for (Session session : sessions) {
            if (session.isOpen()) {
                return session;
            } else {
                log.warn("发现已关闭的session: {}, 自动过滤", session.getId());
            }
        }
        return null;
    }

    /**
     * 检查是否有真正活跃的WebSocket连接
     */
    public boolean hasActiveSession() {
        return getFirstSession() != null;
    }

    /**
     * 获取总连接数（包括可能已关闭的）
     */
    public int getSessionCount() {
        CopyOnWriteArraySet<Session> sessions = getSessions();
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * 获取真正活跃的连接数
     */
    public int getActiveSessionCount() {
        int count = 0;
        CopyOnWriteArraySet<Session> sessions = getSessions();
        if (sessions != null) {
            for (Session session : sessions) {
                if (session.isOpen()) {
                    count++;
                }
            }
        }
        return count;
    }
}