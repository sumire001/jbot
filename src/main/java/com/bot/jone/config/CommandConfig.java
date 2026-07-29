package com.bot.jone.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令配置：前缀和别名
 *
 * 注意：
 * 1) 默认别名在代码中设置（不在 classpath application.yml 中硬编码），
 *    避免 Spring Boot 的 Map 合并机制导致用户修改被 jar 内默认值覆盖。
 * 2) Spring 的 @ConfigurationProperties 绑定器使用 ConfigurationPropertyName，
 *    只接受 [a-z0-9-.] 字符，含中文等非 ASCII 字符的 Map key 会被静默丢弃。
 *    因此 aliases 不依赖 Spring 的 Map 绑定结果，而是在 initDefaults 中
 *    直接解析外部 YAML 文件，以文件内容作为用户配置的权威来源。
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "bot.command")
public class CommandConfig {

    /**
     * 所有内部命令名（按注册顺序）
     * WebUI 渲染时以此为准，确保所有命令都显示，即使用户没配置别名
     */
    public static final List<String> ALL_COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "测试",
            "戳一戳",
            "偷一下",
            "随机图",
            "enable",
            "reset",
            "history",
            "summary",
            "profile",
            "clear-memory",
            "categories",
            "peek",
            "look"
    ));

    /**
     * 命令前缀，默认 /
     */
    private String prefix = "/";

    /**
     * 命令别名映射：主命令名 -> 别名（逗号分隔多个别名）
     * key 是内部命令名（不含前缀），value 是用户自定义的别名
     */
    private Map<String, String> aliases = new LinkedHashMap<>();

    /**
     * 默认别名映射（仅当用户未配置时使用）
     * 如果用户在 config/application.yml 中持久化了别名，则以用户配置为准
     */
    public static final Map<String, String> DEFAULT_ALIASES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("测试", "test");
        m.put("戳一戳", "poke");
        m.put("偷一下", "steal");
        m.put("随机图", "random");
        m.put("enable", "ai开关");
        m.put("reset", "重置");
        m.put("history", "历史");
        m.put("summary", "摘要");
        m.put("profile", "画像");
        m.put("clear-memory", "清空记忆");
        m.put("categories", "分类列表");
        m.put("peek", "peek");
        m.put("look", "look");
        DEFAULT_ALIASES = Collections.unmodifiableMap(m);
    }

    @PostConstruct
    public void initDefaults() {
        String userDir = System.getProperty("user.dir");
        File configFile = new File(userDir + File.separator + "config" + File.separator + "application.yml");
        log.info("[CommandConfig] user.dir={}, 外部配置文件存在={}, 路径={}",
                userDir, configFile.exists(), configFile.getAbsolutePath());

        // 记录 Spring @ConfigurationProperties 绑定到的结果。
        // 注意：Spring 的 ConfigurationPropertyName 只允许 [a-z0-9-.] 字符，
        // 中文等非 ASCII 的 Map key 会被绑定器静默丢弃，因此下面这个结果是不完整的。
        Map<String, String> springBound = new LinkedHashMap<>(this.aliases);
        log.info("[CommandConfig] Spring 绑定到的 aliases（中文等非ASCII键会被丢弃）: {}", springBound);

        // 直接解析外部配置文件，作为用户配置的权威来源（绕过 Spring 的 key 字符限制）
        Map<String, String> fileAliases = loadAliasesFromYaml(configFile);
        if (fileAliases.isEmpty()) {
            // 回退：尝试 classpath 内的 application.yml
            fileAliases = loadAliasesFromYaml(getClass().getResourceAsStream("/application.yml"));
        }
        log.info("[CommandConfig] 从配置文件解析到的 aliases（含中文键）: {}", fileAliases);

        // 以文件配置为准重建 aliases（文件里的非空值覆盖，空格被裁剪）
        this.aliases.clear();
        for (Map.Entry<String, String> e : fileAliases.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.isEmpty()) {
                this.aliases.put(e.getKey(), v);
            }
        }

        // 仅为未配置的命令填充默认别名（用户在 YAML 中已配置的保持不变）
        int filled = 0;
        for (Map.Entry<String, String> e : DEFAULT_ALIASES.entrySet()) {
            if (!aliases.containsKey(e.getKey())
                    || aliases.get(e.getKey()) == null
                    || aliases.get(e.getKey()).isEmpty()) {
                aliases.put(e.getKey(), e.getValue());
                filled++;
            }
        }
        log.info("[CommandConfig] initDefaults 填充了 {} 个默认别名，最终 aliases: {}", filled, aliases);
    }

    /**
     * 直接从 YAML 文件/流解析 bot.command.aliases，
     * 避免依赖 Spring @ConfigurationProperties 对 Map key 的字符限制（中文键会被丢弃）。
     * 任何解析异常都返回空 Map，不影响后续默认别名兜底。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> loadAliasesFromYaml(File file) {
        if (file == null || !file.exists()) {
            return new LinkedHashMap<>();
        }
        try {
            return loadAliasesFromYaml(new FileInputStream(file));
        } catch (Exception e) {
            log.warn("[CommandConfig] 读取配置文件失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadAliasesFromYaml(InputStream is) {
        Map<String, String> result = new LinkedHashMap<>();
        if (is == null) {
            return result;
        }
        try {
            Yaml yaml = new Yaml();
            Object root = yaml.load(is);
            if (!(root instanceof Map)) {
                return result;
            }
            Object bot = ((Map<?, ?>) root).get("bot");
            if (!(bot instanceof Map)) {
                return result;
            }
            Object command = ((Map<?, ?>) bot).get("command");
            if (!(command instanceof Map)) {
                return result;
            }
            Object aliasesObj = ((Map<?, ?>) command).get("aliases");
            if (!(aliasesObj instanceof Map)) {
                return result;
            }
            for (Map.Entry<?, ?> en : ((Map<?, ?>) aliasesObj).entrySet()) {
                String k = en.getKey() == null ? null : String.valueOf(en.getKey());
                if (k == null || k.isEmpty()) {
                    continue;
                }
                Object v = en.getValue();
                result.put(k, v == null ? "" : String.valueOf(v));
            }
        } catch (Exception e) {
            log.warn("[CommandConfig] 解析 aliases 失败: {}", e.getMessage());
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 获取完整的命令配置视图（用于 WebUI 显示）
     * 以 ALL_COMMANDS 为基准，合并 aliases 中的配置，确保所有命令都显示
     * @return 有序 Map：命令名 -> 别名（可能为空字符串）
     */
    public Map<String, String> getFullCommandView() {
        Map<String, String> view = new LinkedHashMap<>();
        for (String cmd : ALL_COMMANDS) {
            String alias = aliases.get(cmd);
            view.put(cmd, alias == null ? "" : alias);
        }
        return view;
    }

    /**
     * 检查内容是否以命令前缀开头
     */
    public boolean hasPrefix(String content) {
        return content != null && content.startsWith(prefix);
    }

    /**
     * 去掉命令前缀
     */
    public String stripPrefix(String content) {
        if (content != null && content.startsWith(prefix)) {
            return content.substring(prefix.length());
        }
        return content;
    }

    /**
     * 获取命令的所有匹配名（主名 + 别名）
     * @param commandName 内部命令名
     * @return 所有可匹配的名称列表
     */
    public java.util.List<String> getMatchNames(String commandName) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(commandName);
        String aliasStr = aliases.get(commandName);
        if (aliasStr != null && !aliasStr.isEmpty()) {
            for (String alias : aliasStr.split(",")) {
                String a = alias.trim();
                if (!a.isEmpty()) {
                    names.add(a);
                }
            }
        }
        return names;
    }

    /**
     * 检查给定的命令体是否匹配某个命令（主名或别名）
     * @param commandBody 去掉前缀后的命令体（可能含参数）
     * @param commandName 内部命令名
     * @return 是否匹配
     */
    public boolean matches(String commandBody, String commandName) {
        if (commandBody == null) {
            return false;
        }
        for (String name : getMatchNames(commandName)) {
            if (commandBody.equals(name) || commandBody.startsWith(name + " ")) {
                return true;
            }
        }
        return false;
    }
}
