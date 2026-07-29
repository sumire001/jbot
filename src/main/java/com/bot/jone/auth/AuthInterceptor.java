package com.bot.jone.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器
 * 检查用户是否已登录，未登录则重定向到登录页面
 */
@Component
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private PasswordManager passwordManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();

        // 放行静态资源
        if (requestURI.startsWith("/static/") ||
            requestURI.equals("/favicon.ico") ||
            requestURI.equals("/favicon.png") ||
            requestURI.startsWith("/actuator/")) {
            return true;
        }

        // 放行登录页面和认证相关接口
        if (requestURI.equals("/login") || 
            requestURI.startsWith("/api/auth/") || 
            requestURI.startsWith("/api/vocabulary/")) {
            return true;
        }

        // 如果还没有设置密码，其他页面重定向到登录页（设置密码）
        if (!passwordManager.hasPassword()) {
            response.sendRedirect("/login");
            return false;
        }

        // 已设置密码，检查登录状态
        HttpSession session = request.getSession(false);
        boolean authenticated = session != null && session.getAttribute("authenticated") != null;

        if (!authenticated) {
            // API请求返回未授权
            if (requestURI.startsWith("/api/")) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\":false,\"message\":\"请先登录\"}");
                return false;
            }
            // 页面请求重定向到登录页
            response.sendRedirect("/login");
            return false;
        }

        return true;
    }
}