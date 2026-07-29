package com.bot.jone.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证 API Controller
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    @Autowired
    private PasswordManager passwordManager;

    /**
     * 检查认证状态
     */
    @GetMapping("/status")
    public Map<String, Object> checkStatus(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession(false);
        boolean authenticated = session != null && session.getAttribute("authenticated") != null;

        result.put("success", true);
        result.put("hasPassword", passwordManager.hasPassword());
        result.put("authenticated", authenticated);

        return result;
    }

    /**
     * 设置初始密码
     */
    @PostMapping("/setup")
    public Map<String, Object> setupPassword(@RequestParam String password, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        if (passwordManager.hasPassword()) {
            result.put("success", false);
            result.put("message", "密码已设置，请直接登录");
            return result;
        }

        if (password == null || password.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "密码不能为空");
            return result;
        }

        if (password.length() < 4) {
            result.put("success", false);
            result.put("message", "密码至少需要4个字符");
            return result;
        }

        if (passwordManager.setPassword(password)) {
            HttpSession session = request.getSession();
            session.setAttribute("authenticated", true);
            session.setMaxInactiveInterval(3600);

            result.put("success", true);
            result.put("message", "密码设置成功");
            log.info("[Auth] 用户设置了初始密码");
        } else {
            result.put("success", false);
            result.put("message", "密码设置失败");
        }

        return result;
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String password, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        if (!passwordManager.hasPassword()) {
            result.put("success", false);
            result.put("message", "请先设置初始密码");
            return result;
        }

        if (passwordManager.verifyPassword(password)) {
            HttpSession session = request.getSession();
            session.setAttribute("authenticated", true);
            session.setMaxInactiveInterval(3600);

            result.put("success", true);
            result.put("message", "登录成功");
            log.info("[Auth] 用户登录成功");
        } else {
            result.put("success", false);
            result.put("message", "密码错误");
            log.warn("[Auth] 用户登录失败，密码错误");
        }

        return result;
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        result.put("success", true);
        result.put("message", "已登出");
        log.info("[Auth] 用户登出");
        return result;
    }

    /**
     * 修改密码
     */
    @PostMapping("/change")
    public Map<String, Object> changePassword(
            @RequestParam String oldPassword,
            @RequestParam String newPassword,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("authenticated") == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }

        if (!passwordManager.verifyPassword(oldPassword)) {
            result.put("success", false);
            result.put("message", "旧密码错误");
            return result;
        }

        if (newPassword == null || newPassword.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "新密码不能为空");
            return result;
        }

        if (newPassword.length() < 4) {
            result.put("success", false);
            result.put("message", "新密码至少需要4个字符");
            return result;
        }

        if (passwordManager.setPassword(newPassword)) {
            result.put("success", true);
            result.put("message", "密码修改成功");
            log.info("[Auth] 用户修改了密码");
        } else {
            result.put("success", false);
            result.put("message", "密码修改失败");
        }

        return result;
    }


}