package com.bot.jone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置热更新管理器
 * 运行时动态修改内存配置（立即生效）+ 持久化到外部 config/application.yml（重启后生效）
 */
@Component
@Slf4j
public class ConfigHotReloadManager {

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ImagesConfig imagesConfig;

    @Autowired
    private com.bot.jone.ai.AiConfig aiConfig;

    @Autowired
    private CommandConfig commandConfig;

    @Autowired
    private GalleryConfig galleryConfig;

    @Autowired
    private com.bot.jone.config.ProxyConfig proxyConfig;

    @Autowired
    private BaikeConfig baikeConfig;

    /**
     * 更新命令配置（前缀 + 别名）
     * 内存立即生效 + 持久化到 config/application.yml
     */
    public void updateCommandConfig(String prefix, Map<String, String> aliases, Map<String, Boolean> permissions) {
        commandConfig.setPrefix(prefix);
        commandConfig.getAliases().clear();
        commandConfig.getAliases().putAll(aliases);
        // 为未配置的命令补充默认别名（保持与启动时 @PostConstruct 行为一致）
        for (Map.Entry<String, String> e : CommandConfig.DEFAULT_ALIASES.entrySet()) {
            if (!commandConfig.getAliases().containsKey(e.getKey())
                    || commandConfig.getAliases().get(e.getKey()) == null
                    || commandConfig.getAliases().get(e.getKey()).isEmpty()) {
                commandConfig.getAliases().put(e.getKey(), e.getValue());
            }
        }

        // 命令权限（以用户提交为准，未提交命令用默认兜底）
        if (permissions != null) {
            commandConfig.setCommandPermissions(permissions);
        }

        addOrReplaceProperty("bot.command.prefix", prefix);

        // 持久化时只保存用户实际配置的非空别名（默认别名由 @PostConstruct 兜底）
        Map<String, String> persistAliases = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                persistAliases.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.command.prefix", prefix);
        persist.put("bot.command.aliases", persistAliases);
        persist.put("bot.command.permissions", commandConfig.getCommandPermissions());
        YamlFileUpdater.updateAll(persist);

        log.info("[配置热更新] 命令前缀='{}', 用户配置别名={}, 权限={}",
                prefix, persistAliases.size(), commandConfig.getCommandPermissions().size());
    }

    /**
     * 更新图片下载配置
     */
    public void updateImageDownloadConfig(int maxSizeMb) {
        imagesConfig.getDownload().setMaxSizeMb(maxSizeMb);
        addOrReplaceProperty("bot.images.download.max-size-mb", maxSizeMb);
        YamlFileUpdater.update("bot.images.download.max-size-mb", maxSizeMb);
        log.info("[配置热更新] 图片下载最大大小: {}MB", maxSizeMb);
    }

    /**
     * 更新图片压缩配置
     */
    public void updateImageCompressionConfig(int sizeThresholdMb, int maxDimension,
                                              float jpegQuality, boolean skipGif) {
        imagesConfig.getCompression().setSizeThresholdMb(sizeThresholdMb);
        imagesConfig.getCompression().setMaxDimension(maxDimension);
        imagesConfig.getCompression().setJpegQuality(jpegQuality);
        imagesConfig.getCompression().setSkipGif(skipGif);

        addOrReplaceProperty("bot.images.compression.size-threshold-mb", sizeThresholdMb);
        addOrReplaceProperty("bot.images.compression.max-dimension", maxDimension);
        addOrReplaceProperty("bot.images.compression.jpeg-quality", jpegQuality);
        addOrReplaceProperty("bot.images.compression.skip-gif", skipGif);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.images.compression.size-threshold-mb", sizeThresholdMb);
        persist.put("bot.images.compression.max-dimension", maxDimension);
        persist.put("bot.images.compression.jpeg-quality", jpegQuality);
        persist.put("bot.images.compression.skip-gif", skipGif);
        YamlFileUpdater.updateAll(persist);

        log.info("[配置热更新] 图片压缩: 阈值{}MB, 分辨率{}px, 质量{}, 跳过GIF: {}",
            sizeThresholdMb, maxDimension, jpegQuality, skipGif);
    }

    /**
     * 更新AI配置
     * @param apiKey 若为 null 则不修改 API Key
     */
    public void updateAiConfig(boolean enabled, String apiKey, String apiUrl, String model, int maxTokens,
                                double temperature, int timeoutSeconds, int historyMaxContext) {
        aiConfig.setEnabled(enabled);
        if (apiKey != null && !apiKey.isEmpty()) {
            aiConfig.setApiKey(apiKey);
        }
        aiConfig.setApiUrl(apiUrl);
        aiConfig.setModel(model);
        aiConfig.setMaxTokens(maxTokens);
        aiConfig.setTemperature(temperature);
        aiConfig.setTimeoutSeconds(timeoutSeconds);
        aiConfig.setHistoryMaxContext(historyMaxContext);

        addOrReplaceProperty("bot.ai.enabled", enabled);
        addOrReplaceProperty("bot.ai.api-url", apiUrl);
        addOrReplaceProperty("bot.ai.model", model);
        addOrReplaceProperty("bot.ai.max-tokens", maxTokens);
        addOrReplaceProperty("bot.ai.temperature", temperature);
        addOrReplaceProperty("bot.ai.timeout-seconds", timeoutSeconds);
        addOrReplaceProperty("bot.ai.history-max-context", historyMaxContext);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.ai.enabled", enabled);
        persist.put("bot.ai.api-url", apiUrl);
        persist.put("bot.ai.model", model);
        persist.put("bot.ai.max-tokens", maxTokens);
        persist.put("bot.ai.temperature", temperature);
        persist.put("bot.ai.timeout-seconds", timeoutSeconds);
        persist.put("bot.ai.history-max-context", historyMaxContext);
        if (apiKey != null && !apiKey.isEmpty()) {
            persist.put("bot.ai.api-key", apiKey);
        }
        YamlFileUpdater.updateAll(persist);

        log.info("[配置热更新] AI: 启用={}, 地址={}, 模型={}, tokens={}, temperature={}, 超时={}s, 历史轮数={}, apiKey更新={}",
            enabled, apiUrl, model, maxTokens, temperature, timeoutSeconds, historyMaxContext, apiKey != null && !apiKey.isEmpty());
    }

    /**
     * 更新AI系统提示词
     */
    public void updateAiSystemPrompt(String systemPrompt) {
        aiConfig.setSystemPrompt(systemPrompt);
        addOrReplaceProperty("bot.ai.system-prompt", systemPrompt);
        YamlFileUpdater.update("bot.ai.system-prompt", systemPrompt);
        log.info("[配置热更新] AI系统提示词已更新，长度: {}", systemPrompt != null ? systemPrompt.length() : 0);
    }

    /**
     * 更新画廊配置
     * @param apiUrl / apiUser / apiKey 若为 null 或空则不修改（避免误清空）
     */
    public void updateGalleryConfig(boolean enabled, String apiUrl, String apiUser, String apiKey,
                                    int cols, int rows, int width, int scale, int thumbDim) {
        galleryConfig.setEnabled(enabled);
        if (apiUrl != null && !apiUrl.isEmpty()) {
            galleryConfig.setApiUrl(apiUrl);
        }
        if (apiUser != null && !apiUser.isEmpty()) {
            galleryConfig.setApiUser(apiUser);
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            galleryConfig.setApiKey(apiKey);
        }
        galleryConfig.setCols(cols);
        galleryConfig.setRows(rows);
        galleryConfig.setWidth(width);
        galleryConfig.setScale(scale);
        galleryConfig.setThumbDim(thumbDim);

        addOrReplaceProperty("bot.gallery.enabled", enabled);
        if (apiUrl != null && !apiUrl.isEmpty()) addOrReplaceProperty("bot.gallery.api-url", apiUrl);
        if (apiUser != null && !apiUser.isEmpty()) addOrReplaceProperty("bot.gallery.api-user", apiUser);
        if (apiKey != null && !apiKey.isEmpty()) addOrReplaceProperty("bot.gallery.api-key", apiKey);
        addOrReplaceProperty("bot.gallery.cols", cols);
        addOrReplaceProperty("bot.gallery.rows", rows);
        addOrReplaceProperty("bot.gallery.width", width);
        addOrReplaceProperty("bot.gallery.scale", scale);
        addOrReplaceProperty("bot.gallery.thumb-dim", thumbDim);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.gallery.enabled", enabled);
        if (apiUrl != null && !apiUrl.isEmpty()) persist.put("bot.gallery.api-url", apiUrl);
        if (apiUser != null && !apiUser.isEmpty()) persist.put("bot.gallery.api-user", apiUser);
        if (apiKey != null && !apiKey.isEmpty()) persist.put("bot.gallery.api-key", apiKey);
        persist.put("bot.gallery.cols", cols);
        persist.put("bot.gallery.rows", rows);
        persist.put("bot.gallery.width", width);
        persist.put("bot.gallery.scale", scale);
        persist.put("bot.gallery.thumb-dim", thumbDim);
        YamlFileUpdater.updateAll(persist);

        log.info("[配置热更新] 画廊: 启用={}, 地址={}, 用户={}, 列={}, 行={}, 宽={}, 比例={}, 缩略={}",
            enabled, apiUrl, apiUser, cols, rows, width, scale, thumbDim);
    }

    /**
     * 更新消息缓存大小
     */
    public void updateCacheConfig(int messageMaxSize) {
        addOrReplaceProperty("bot.cache.message-max-size", messageMaxSize);
        YamlFileUpdater.update("bot.cache.message-max-size", messageMaxSize);
        log.info("[配置热更新] 消息缓存大小: {} (需重启后完全生效)", messageMaxSize);
    }

    /**
     * 更新出站代理配置
     * 内存立即生效（重新安装 JVM ProxySelector）+ 持久化到 config/application.yml。
     * @param password 为空则不修改现有密码（避免误清空）
     */
    public void updateProxyConfig(boolean enabled, String type, String host, int port,
                                   String username, String password, String nonProxyHosts) {
        boolean changePwd = password != null && !password.isEmpty();

        proxyConfig.setEnabled(enabled);
        proxyConfig.setType(type);
        proxyConfig.setHost(host);
        proxyConfig.setPort(port);
        if (username != null) proxyConfig.setUsername(username);
        if (changePwd) proxyConfig.setPassword(password);
        proxyConfig.setNonProxyHosts(nonProxyHosts);

        addOrReplaceProperty("bot.proxy.enabled", enabled);
        addOrReplaceProperty("bot.proxy.type", type);
        addOrReplaceProperty("bot.proxy.host", host);
        addOrReplaceProperty("bot.proxy.port", port);
        if (username != null) addOrReplaceProperty("bot.proxy.username", username);
        if (changePwd) addOrReplaceProperty("bot.proxy.password", password);
        addOrReplaceProperty("bot.proxy.non-proxy-hosts", nonProxyHosts);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.proxy.enabled", enabled);
        persist.put("bot.proxy.type", type);
        persist.put("bot.proxy.host", host);
        persist.put("bot.proxy.port", port);
        if (username != null) persist.put("bot.proxy.username", username);
        if (changePwd) persist.put("bot.proxy.password", password);
        persist.put("bot.proxy.non-proxy-hosts", nonProxyHosts);
        YamlFileUpdater.updateAll(persist);

        // 重新安装 JVM ProxySelector，使本次修改立即生效（无需重启）
        proxyConfig.reapply();

        log.info("[配置热更新] 代理: 启用={}, 类型={}, 地址={}:{}, 非代理={}", enabled, type, host, port, nonProxyHosts);
    }

    /**
     * 更新网页搜索后端（bing / duckduckgo / baidu）
     * 内存立即生效（WebSearchTool 每次搜索实时读取 aiConfig.getSearchEngine()）+ 持久化到 config/application.yml。
     * 无需 reapply：搜索引擎是普通字段，WebSearchTool 在 execute() 中实时读取。
     */
    public void updateSearchEngineConfig(String engine) {
        aiConfig.setSearchEngine(engine);
        addOrReplaceProperty("bot.ai.search-engine", engine);
        YamlFileUpdater.update("bot.ai.search-engine", engine);
        log.info("[配置热更新] 网页搜索后端: {}", engine);
    }

    /**
     * 更新百度百科配置（启用开关 + API Key）
     * 内存立即生效（BaiduBaikeTool 每次调用实时读取 BaikeConfig）+ 持久化到 config/application.yml。
     * @param apiKey 为 null 或空则不修改现有 Key（留空即继续使用配置中已有的值或环境变量 BAIDU_API_KEY）
     */
    public void updateBaikeConfig(boolean enabled, String apiKey) {
        boolean changeKey = apiKey != null && !apiKey.trim().isEmpty();
        baikeConfig.setEnabled(enabled);
        if (changeKey) {
            baikeConfig.setApiKey(apiKey.trim());
        }

        addOrReplaceProperty("bot.baike.enabled", enabled);
        if (changeKey) addOrReplaceProperty("bot.baike.api-key", apiKey.trim());

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("bot.baike.enabled", enabled);
        if (changeKey) persist.put("bot.baike.api-key", apiKey.trim());
        YamlFileUpdater.updateAll(persist);

        log.info("[配置热更新] 百度百科: 启用={}, apiKey更新={}", enabled, changeKey);
    }

    /**
     * 添加或替换PropertySource中的属性（内存热更新）
     */
    private void addOrReplaceProperty(String key, Object value) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(key, value);
        MapPropertySource propertySource = new MapPropertySource("hotReload", properties);
        environment.getPropertySources().addFirst(propertySource);
    }
}
