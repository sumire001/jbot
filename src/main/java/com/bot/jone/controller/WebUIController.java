package com.bot.jone.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * WebUI页面路由
 */
@Controller
public class WebUIController {

    /**
     * 登录页面
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * 主页
     */
    @GetMapping({"/", "/index", "/webui", "/admin"})
    public String index() {
        return "index";
    }

    /**
     * 监控页
     */
    @GetMapping("/monitor-page")
    public String monitorPage() {
        return "monitor";
    }

    /**
     * 配置管理页
     */
    @GetMapping("/config-page")
    public String configPage() {
        return "config";
    }

    /**
     * Meme管理页
     */
    @GetMapping("/memes-page")
    public String memesPage() {
        return "memes";
    }
}