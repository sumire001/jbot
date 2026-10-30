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
     * 主页（概览 Dashboard）
     */
    @GetMapping({"/", "/index", "/webui", "/admin"})
    public String index() {
        return "index";
    }

    /**
     * 监控页（实时监控 Monitor）
     */
    @GetMapping("/monitor-page")
    public String monitorPage() {
        return "monitor";
    }

    /**
     * 命令设置页
     */
    @GetMapping("/command-page")
    public String commandPage() {
        return "command";
    }

    /**
     * AI 模型配置页（本地向量模型 + AI 聊天 API）
     */
    @GetMapping("/ai-models-page")
    public String aiModelsPage() {
        return "ai-models";
    }

    /**
     * 搜索源管理页
     */
    @GetMapping("/search-source-page")
    public String searchSourcePage() {
        return "search-source";
    }

    /**
     * Meme管理页（表情包列表）
     */
    @GetMapping("/memes-page")
    public String memesPage() {
        return "memes";
    }

    /**
     * 批量上传页
     */
    @GetMapping("/batch-upload-page")
    public String batchUploadPage() {
        return "batch-upload";
    }

    /**
     * 通用设置页（原 config 页拆分后的剩余配置）
     */
    @GetMapping("/general-settings-page")
    public String generalSettingsPage() {
        return "general-settings";
    }
}
