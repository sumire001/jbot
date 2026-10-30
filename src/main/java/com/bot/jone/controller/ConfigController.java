package com.bot.jone.controller;

import com.bot.jone.ai.AiConfig;
import com.bot.jone.ai.PromptManager;
import com.bot.jone.ai.LongTermMemoryManager;
import com.bot.jone.ai.model.UserMemory;
import com.bot.jone.config.CommandConfig;
import com.bot.jone.config.ConfigHotReloadManager;
import com.bot.jone.config.GalleryConfig;
import com.bot.jone.config.ImagesConfig;
import com.bot.jone.config.YamlFileUpdater;
import com.bot.jone.permission.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 配置热更新API
 */
@RestController
@RequestMapping("/api/config")
@Slf4j
public class ConfigController {

    @Autowired
    private ImagesConfig imagesConfig;

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private PromptManager promptManager;

    @Autowired
    private ConfigHotReloadManager hotReloadManager;

    @Autowired
    private LongTermMemoryManager longTermMemoryManager;

    @Autowired
    private CommandConfig commandConfig;

    @Autowired
    private GalleryConfig galleryConfig;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private com.bot.jone.config.ProxyConfig proxyConfig;

    @Autowired
    private com.bot.jone.config.ModelManager modelManager;

    @Autowired
    private com.bot.jone.config.BaikeConfig baikeConfig;

    @Value("${bot.cache.message-max-size:100}")
    private int messageMaxSize;

    /**
     * 获取所有配置
     */
    @GetMapping("/all")
    public Map<String, Object> getAllConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);

        // 图片下载配置
        Map<String, Object> download = new HashMap<>();
        download.put("maxSizeMb", imagesConfig.getDownload().getMaxSizeMb());
        result.put("imageDownload", download);

        // 图片压缩配置
        Map<String, Object> compression = new HashMap<>();
        compression.put("sizeThresholdMb", imagesConfig.getCompression().getSizeThresholdMb());
        compression.put("maxDimension", imagesConfig.getCompression().getMaxDimension());
        compression.put("jpegQuality", imagesConfig.getCompression().getJpegQuality());
        compression.put("skipGif", imagesConfig.getCompression().isSkipGif());
        result.put("imageCompression", compression);

        // AI配置
        Map<String, Object> ai = new HashMap<>();
        ai.put("enabled", aiConfig.isEnabled());
        ai.put("apiKey", aiConfig.getApiKey());
        ai.put("apiUrl", aiConfig.getApiUrl());
        ai.put("model", aiConfig.getModel());
        ai.put("maxTokens", aiConfig.getMaxTokens());
        ai.put("temperature", aiConfig.getTemperature());
        ai.put("timeoutSeconds", aiConfig.getTimeoutSeconds());
        ai.put("historyMaxContext", aiConfig.getHistoryMaxContext());
        ai.put("searchEngine", aiConfig.getSearchEngine());
        // 系统提示词：优先用配置中的，否则用默认值
        String currentPrompt = aiConfig.getSystemPrompt();
        if (currentPrompt == null || currentPrompt.trim().isEmpty()) {
            currentPrompt = promptManager.getDefaultSystemPrompt();
        }
        ai.put("systemPrompt", currentPrompt);
        result.put("ai", ai);

        // 画廊配置
        Map<String, Object> gallery = new HashMap<>();
        gallery.put("enabled", galleryConfig.isEnabled());
        gallery.put("apiUrl", galleryConfig.getApiUrl());
        gallery.put("apiUser", galleryConfig.getApiUser());
        gallery.put("apiKey", galleryConfig.getApiKey());
        gallery.put("cols", galleryConfig.getCols());
        gallery.put("rows", galleryConfig.getRows());
        gallery.put("width", galleryConfig.getWidth());
        gallery.put("scale", galleryConfig.getScale());
        gallery.put("thumbDim", galleryConfig.getThumbDim());
        result.put("gallery", gallery);

        // 命令配置（合并所有命令，确保 WebUI 显示完整列表）
        Map<String, Object> command = new HashMap<>();
        command.put("prefix", commandConfig.getPrefix());
        command.put("aliases", commandConfig.getFullCommandView());
        command.put("permissions", commandConfig.getFullPermissionView());
        result.put("command", command);

        // 缓存配置
        Map<String, Object> cache = new HashMap<>();
        cache.put("messageMaxSize", messageMaxSize);
        result.put("cache", cache);

        // 管理员配置
        Map<String, Object> admin = new HashMap<>();
        admin.put("qqList", permissionService.getAdminList());
        result.put("admin", admin);

        // 代理配置（password 不返回，避免明文回显；前端密码框留空表示不修改）
        Map<String, Object> proxy = new HashMap<>();
        proxy.put("enabled", proxyConfig.isEnabled());
        proxy.put("type", proxyConfig.getType());
        proxy.put("host", proxyConfig.getHost());
        proxy.put("port", proxyConfig.getPort());
        proxy.put("username", proxyConfig.getUsername());
        proxy.put("nonProxyHosts", proxyConfig.getNonProxyHosts());
        result.put("proxy", proxy);

        // 百度百科（不回显明文 Key，只回是否已配置及来源 config/env/none）
        Map<String, Object> baike = new HashMap<>();
        baike.put("enabled", baikeConfig.isEnabled());
        baike.put("configured", baikeConfig.resolveApiKey() != null);
        baike.put("source", baikeConfig.apiKeySource());
        result.put("baike", baike);

        return result;
    }

    /**
     * 获取命令配置（前缀 + 别名列表）
     */
    @GetMapping("/command")
    public Map<String, Object> getCommandConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("prefix", commandConfig.getPrefix());
        result.put("aliases", commandConfig.getFullCommandView());
        result.put("permissions", commandConfig.getFullPermissionView());
        return result;
    }

    /**
     * 更新命令配置
     * Body: { "prefix": "/", "aliases": {"偷一下": "steal"}, "permissions": {"偷一下": true} }
     */
    @PostMapping("/command")
    public Map<String, Object> updateCommandConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            Object prefixObj = body == null ? null : body.get("prefix");
            String prefix = prefixObj == null ? "" : String.valueOf(prefixObj).trim();
            if (prefix.isEmpty()) {
                result.put("success", false);
                result.put("message", "命令前缀不能为空");
                return result;
            }
            if (prefix.length() > 4) {
                result.put("success", false);
                result.put("message", "命令前缀长度不能超过4个字符");
                return result;
            }
            Map<String, String> cleanedAliases = new LinkedHashMap<>();
            Object aliasesObj = body.get("aliases");
            if (aliasesObj instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) aliasesObj).entrySet()) {
                    String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
                    if (key.isEmpty()) continue;
                    String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
                    // 只持久化非空别名，空别名依赖 @PostConstruct 默认值填充
                    if (!value.isEmpty()) {
                        cleanedAliases.put(key, value);
                    }
                }
            }
            Map<String, Boolean> cleanedPermissions = new LinkedHashMap<>();
            Object permObj = body.get("permissions");
            if (permObj instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) permObj).entrySet()) {
                    String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
                    if (key.isEmpty()) continue;
                    Object val = entry.getValue();
                    Boolean b = null;
                    if (val instanceof Boolean) b = (Boolean) val;
                    else if (val != null) b = Boolean.parseBoolean(val.toString());
                    if (b != null) cleanedPermissions.put(key, b);
                }
            }
            hotReloadManager.updateCommandConfig(prefix, cleanedAliases, cleanedPermissions);
            result.put("success", true);
            result.put("message", "命令配置已更新并持久化");
        } catch (Exception e) {
            log.error("更新命令配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新图片下载配置
     */
    @PostMapping("/image-download")
    public Map<String, Object> updateImageDownload(@RequestParam int maxSizeMb) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (maxSizeMb < 1 || maxSizeMb > 100) {
                result.put("success", false);
                result.put("message", "大小必须在1-100MB之间");
                return result;
            }
            hotReloadManager.updateImageDownloadConfig(maxSizeMb);
            result.put("success", true);
            result.put("message", "配置已更新");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新图片压缩配置
     */
    @PostMapping("/image-compression")
    public Map<String, Object> updateImageCompression(
            @RequestParam int sizeThresholdMb,
            @RequestParam int maxDimension,
            @RequestParam float jpegQuality,
            @RequestParam boolean skipGif) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (sizeThresholdMb < 1 || sizeThresholdMb > 10) {
                result.put("success", false);
                result.put("message", "压缩阈值必须在1-10MB之间");
                return result;
            }
            if (maxDimension < 800 || maxDimension > 4000) {
                result.put("success", false);
                result.put("message", "分辨率必须在800-4000之间");
                return result;
            }
            if (jpegQuality < 0.1 || jpegQuality > 1.0) {
                result.put("success", false);
                result.put("message", "JPEG质量必须在0.1-1.0之间");
                return result;
            }
            hotReloadManager.updateImageCompressionConfig(sizeThresholdMb, maxDimension, jpegQuality, skipGif);
            result.put("success", true);
            result.put("message", "配置已更新");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新AI配置
     * @param apiKey API Key，留空则不修改
     */
    @PostMapping("/ai")
    public Map<String, Object> updateAi(
            @RequestParam boolean enabled,
            @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String apiUrl,
            @RequestParam String model,
            @RequestParam int maxTokens,
            @RequestParam double temperature,
            @RequestParam int timeoutSeconds,
            @RequestParam int historyMaxContext) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (maxTokens < 100 || maxTokens > 8000) {
                result.put("success", false);
                result.put("message", "maxTokens必须在100-8000之间");
                return result;
            }
            if (temperature < 0 || temperature > 2) {
                result.put("success", false);
                result.put("message", "temperature必须在0-2之间");
                return result;
            }
            if (timeoutSeconds < 5 || timeoutSeconds > 300) {
                result.put("success", false);
                result.put("message", "timeout必须在5-300秒之间");
                return result;
            }
            if (historyMaxContext < 1 || historyMaxContext > 50) {
                result.put("success", false);
                result.put("message", "历史对话轮数必须在1-50之间");
                return result;
            }
            hotReloadManager.updateAiConfig(enabled, apiKey, apiUrl, model, maxTokens, temperature, timeoutSeconds, historyMaxContext);
            result.put("success", true);
            result.put("message", "配置已更新并持久化");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新画廊配置
     * @param apiUrl / apiUser / apiKey 留空则不修改（避免误清空）
     */
    @PostMapping("/gallery")
    public Map<String, Object> updateGallery(
            @RequestParam boolean enabled,
            @RequestParam(required = false) String apiUrl,
            @RequestParam(required = false) String apiUser,
            @RequestParam(required = false) String apiKey,
            @RequestParam int cols,
            @RequestParam int rows,
            @RequestParam int width,
            @RequestParam int scale,
            @RequestParam int thumbDim) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (cols < 1 || cols > 20) {
                result.put("success", false);
                result.put("message", "列数必须在1-20之间");
                return result;
            }
            if (rows < 1 || rows > 30) {
                result.put("success", false);
                result.put("message", "行数必须在1-30之间");
                return result;
            }
            if (width < 300 || width > 2000) {
                result.put("success", false);
                result.put("message", "页宽必须在300-2000之间");
                return result;
            }
            if (scale < 1 || scale > 3) {
                result.put("success", false);
                result.put("message", "清晰度必须在1-3之间");
                return result;
            }
            if (thumbDim < 0 || thumbDim > 2000) {
                result.put("success", false);
                result.put("message", "缩略尺寸必须在0-2000之间");
                return result;
            }
            hotReloadManager.updateGalleryConfig(enabled, apiUrl, apiUser, apiKey, cols, rows, width, scale, thumbDim);
            result.put("success", true);
            result.put("message", "画廊配置已更新并持久化");
        } catch (Exception e) {
            log.error("更新画廊配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取默认系统提示词
     */
    @GetMapping("/ai-default-prompt")
    public Map<String, Object> getDefaultPrompt() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("systemPrompt", promptManager.getDefaultSystemPrompt());
        return result;
    }

    /**
     * 更新AI系统提示词
     */
    @PostMapping("/ai-system-prompt")
    public Map<String, Object> updateAiSystemPrompt(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String systemPrompt = body != null ? body.get("systemPrompt") : null;
            if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "系统提示词不能为空");
                return result;
            }
            if (systemPrompt.length() > 10000) {
                result.put("success", false);
                result.put("message", "系统提示词长度不能超过10000字符");
                return result;
            }
            hotReloadManager.updateAiSystemPrompt(systemPrompt);
            result.put("success", true);
            result.put("message", "系统提示词已更新并持久化");
        } catch (Exception e) {
            log.error("更新系统提示词失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新消息缓存大小
     */
    @PostMapping("/cache")
    public Map<String, Object> updateCache(@RequestParam int messageMaxSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (messageMaxSize < 10 || messageMaxSize > 1000) {
                result.put("success", false);
                result.put("message", "缓存大小必须在10-1000之间");
                return result;
            }
            hotReloadManager.updateCacheConfig(messageMaxSize);
            result.put("success", true);
            result.put("message", "配置已更新（需要重启生效）");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取管理员QQ列表
     */
    @GetMapping("/admin")
    public Map<String, Object> getAdminConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("qqList", permissionService.getAdminList());
        return result;
    }

    /**
     * 更新管理员QQ列表（热更新内存 + 持久化到 ./config/application.yml）
     */
    @PostMapping("/admin")
    public Map<String, Object> updateAdminConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            Object listObj = body == null ? null : body.get("qqList");
            List<Long> newList = new ArrayList<>();
            if (listObj instanceof List) {
                for (Object o : (List<?>) listObj) {
                    if (o == null) continue;
                    try {
                        long qq = Long.parseLong(o.toString().trim());
                        if (qq > 0) newList.add(qq);
                    } catch (NumberFormatException ignore) {}
                }
            }
            if (newList.isEmpty()) {
                result.put("success", false);
                result.put("message", "请至少提供一个有效的 QQ 号");
                return result;
            }
            YamlFileUpdater.update("bot.admin.qq-list", newList);
            permissionService.updateAdminList(newList);
            result.put("success", true);
            result.put("message", "管理员列表已更新并持久化");
        } catch (Exception e) {
            log.error("更新管理员配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新出站代理配置（热更新内存 + 持久化 + 重新安装 ProxySelector 立即生效）
     * @param password 留空则不修改现有密码
     */
    @PostMapping("/proxy")
    public Map<String, Object> updateProxy(
            @RequestParam boolean enabled,
            @RequestParam String type,
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password,
            @RequestParam String nonProxyHosts) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (port < 1 || port > 65535) {
                result.put("success", false);
                result.put("message", "端口必须在 1-65535 之间");
                return result;
            }
            if (!"http".equalsIgnoreCase(type) && !"https".equalsIgnoreCase(type) && !"socks".equalsIgnoreCase(type)) {
                result.put("success", false);
                result.put("message", "类型必须是 http / https / socks");
                return result;
            }
            if (nonProxyHosts == null) nonProxyHosts = "";
            hotReloadManager.updateProxyConfig(enabled, type, host, port, username, password, nonProxyHosts);
            result.put("success", true);
            result.put("message", "代理配置已更新并持久化，已热生效");
        } catch (Exception e) {
            log.error("更新代理配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新网页搜索后端（热更新，无需重启）
     * @param engine 可选值：bing / duckduckgo / baidu
     */
    @PostMapping("/search-engine")
    public Map<String, Object> updateSearchEngine(@RequestParam String engine) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (!"bing".equalsIgnoreCase(engine)
                    && !"duckduckgo".equalsIgnoreCase(engine)
                    && !"baidu".equalsIgnoreCase(engine)) {
                result.put("success", false);
                result.put("message", "搜索后端只能是 bing / duckduckgo / baidu");
                return result;
            }
            hotReloadManager.updateSearchEngineConfig(engine);
            result.put("success", true);
            result.put("message", "搜索后端已切换为 " + engine + "（立即生效）");
        } catch (Exception e) {
            log.error("更新搜索后端失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新百度百科配置（热更新，无需重启）
     * @param apiKey 留空则不修改（继续使用配置中已有的 Key 或环境变量 BAIDU_API_KEY）
     */
    @PostMapping("/baike")
    public Map<String, Object> updateBaike(
            @RequestParam boolean enabled,
            @RequestParam(required = false) String apiKey) {
        Map<String, Object> result = new HashMap<>();
        try {
            hotReloadManager.updateBaikeConfig(enabled, apiKey);
            result.put("success", true);
            result.put("message", "百度百科配置已更新并持久化");
        } catch (Exception e) {
            log.error("更新百度百科配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取模型管理状态：已配置模型（含本地就绪状态/缺失文件） + 可下载模型目录。
     */
    @GetMapping("/models")
    public Map<String, Object> getModels() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> configured = new ArrayList<>();
            for (com.bot.jone.config.ModelManager.Entry e : modelManager.getEntries()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", e.getKey());
                m.put("type", e.getType());
                m.put("dim", e.getDim());
                m.put("description", e.getDescription());
                m.put("enabled", e.isEnabled());
                m.put("autoDownload", e.isAutoDownload());
                m.put("status", e.getStatus().name());
                m.put("message", e.getMessage());
                m.put("localDir", e.getLocalDir());
                List<Map<String, Object>> files = new ArrayList<>();
                for (com.bot.jone.config.ModelManager.ResolvedFile rf : e.getFiles()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", rf.getName());
                    f.put("url", rf.getUrl());
                    f.put("present", rf.isPresent());
                    files.add(f);
                }
                m.put("files", files);
                configured.add(m);
            }
            List<Map<String, Object>> catalogue = new ArrayList<>();
            for (com.bot.jone.config.ModelCatalogue.ModelInfo info : com.bot.jone.config.ModelCatalogue.all()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", info.getKey());
                c.put("type", info.getType());
                c.put("dim", info.getDim());
                c.put("description", info.getDescription());
                c.put("recommendedLocalDir", info.getRecommendedLocalDir());
                List<Map<String, Object>> cf = new ArrayList<>();
                for (com.bot.jone.config.ModelCatalogue.FileInfo fi : info.getFiles()) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("name", fi.getName());
                    x.put("url", fi.getUrl());
                    cf.add(x);
                }
                c.put("files", cf);
                catalogue.add(c);
            }
            result.put("success", true);
            result.put("configured", configured);
            result.put("catalogue", catalogue);
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 手动触发某个模型的下载（即使未开启 auto-download）。后台异步进行，立即返回已触发。
     */
    @PostMapping("/models/{key}/download")
    public Map<String, Object> downloadModel(@PathVariable String key) {
        Map<String, Object> result = new HashMap<>();
        try {
            CompletableFuture<Boolean> fut = modelManager.downloadAsync(key);
            fut.whenComplete((ok, ex) -> {
                if (ex != null) log.error("模型 {} 下载异常", key, ex);
            });
            result.put("success", true);
            result.put("message", "已触发模型 " + key + " 的下载（后台进行，可在面板查看状态）");
        } catch (Exception e) {
            log.error("触发模型下载失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取所有用户画像列表
     */
    @GetMapping("/long-term-memory/users")
    public Map<String, Object> getAllUserMemories() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> users = longTermMemoryManager.getUserMemoryMap().entrySet().stream()
                .map(entry -> {
                    Map<String, Object> user = new HashMap<>();
                    UserMemory m = entry.getValue();
                    user.put("qq", m.getQq());
                    user.put("nickname", m.getNickname());
                    user.put("traits", m.getTraits());
                    user.put("lastSeen", m.getLastSeen());
                    return user;
                })
                .collect(Collectors.toList());
            result.put("success", true);
            result.put("users", users);
        } catch (Exception e) {
            log.error("获取用户画像失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取指定用户画像
     */
    @GetMapping("/long-term-memory/users/{qq}")
    public Map<String, Object> getUserMemory(@PathVariable Long qq) {
        Map<String, Object> result = new HashMap<>();
        try {
            UserMemory user = longTermMemoryManager.getUserMemory(qq);
            if (user == null) {
                result.put("success", false);
                result.put("message", "未找到用户画像");
                return result;
            }
            result.put("success", true);
            result.put("qq", user.getQq());
            result.put("nickname", user.getNickname());
            result.put("traits", user.getTraits());
            result.put("groupNicknames", user.getGroupNicknames());
            result.put("lastSeen", user.getLastSeen());
        } catch (Exception e) {
            log.error("获取用户画像失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新用户画像
     */
    @PostMapping("/long-term-memory/users/{qq}")
    public Map<String, Object> updateUserMemory(@PathVariable Long qq, @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String nickname = body.get("nickname");
            String traits = body.get("traits");
            if (nickname == null && traits == null) {
                result.put("success", false);
                result.put("message", "至少需要提供 nickname 或 traits");
                return result;
            }
            longTermMemoryManager.updateUserMemory(qq, nickname, traits);
            longTermMemoryManager.flushDirty();
            result.put("success", true);
            result.put("message", "用户画像已更新");
        } catch (Exception e) {
            log.error("更新用户画像失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 删除用户画像
     */
    @DeleteMapping("/long-term-memory/users/{qq}")
    public Map<String, Object> deleteUserMemory(@PathVariable Long qq) {
        Map<String, Object> result = new HashMap<>();
        try {
            longTermMemoryManager.clearUserMemory(qq);
            result.put("success", true);
            result.put("message", "用户画像已删除");
        } catch (Exception e) {
            log.error("删除用户画像失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 清空全部长期记忆
     */
    @DeleteMapping("/long-term-memory/all")
    public Map<String, Object> clearAllMemory() {
        Map<String, Object> result = new HashMap<>();
        try {
            longTermMemoryManager.clearAllMemory();
            result.put("success", true);
            result.put("message", "全部记忆已清空");
        } catch (Exception e) {
            log.error("清空记忆失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}