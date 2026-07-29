package com.bot.jone.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML 配置文件持久化工具
 * 将 WebUI 修改的配置写入外部 config/application.yml，覆盖 jar 内默认配置
 * Spring Boot 启动时会自动加载 ./config/application.yml 并覆盖同名属性
 */
@Slf4j
public class YamlFileUpdater {

    private static final String CONFIG_DIR = System.getProperty("user.dir") + File.separator + "config";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "application.yml";

    /**
     * 更新指定 key 路径的值（支持嵌套，如 bot.ai.api-key）
     * 如果 value 为 null，则删除该 key（避免 Spring Boot 回退到默认值）
     */
    public static synchronized void update(String keyPath, Object value) {
        try {
            Map<String, Object> root = loadExisting();
            if (value == null) {
                removeNestedValue(root, keyPath);
            } else {
                setNestedValue(root, keyPath, value);
            }
            writeYaml(root);
            log.info("[配置持久化] 已写入 {}: {} = {}", CONFIG_FILE, keyPath, maskValue(keyPath, value));
        } catch (Exception e) {
            log.error("[配置持久化] 写入失败: {}", keyPath, e);
        }
    }

    /**
     * 批量更新多个 key
     */
    public static synchronized void updateAll(Map<String, Object> entries) {
        if (entries == null || entries.isEmpty()) return;
        try {
            Map<String, Object> root = loadExisting();
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                if (e.getValue() == null) {
                    removeNestedValue(root, e.getKey());
                } else {
                    setNestedValue(root, e.getKey(), e.getValue());
                }
            }
            writeYaml(root);
            log.info("[配置持久化] 批量写入 {} 项到 {}", entries.size(), CONFIG_FILE);
        } catch (Exception e) {
            log.error("[配置持久化] 批量写入失败", e);
        }
    }

    /**
     * 从嵌套 Map 中删除指定 key 路径
     */
    @SuppressWarnings("unchecked")
    private static void removeNestedValue(Map<String, Object> root, String keyPath) {
        String[] keys = keyPath.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = current.get(keys[i]);
            if (!(child instanceof Map)) {
                return;
            }
            current = (Map<String, Object>) child;
        }
        current.remove(keys[keys.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadExisting() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        Yaml yaml = new Yaml();
        try (InputStream is = new FileInputStream(file)) {
            Map<String, Object> map = yaml.load(is);
            if (map == null) return new LinkedHashMap<>();
            return new LinkedHashMap<>(map);
        } catch (Exception e) {
            log.warn("[配置持久化] 读取现有配置失败，将创建新文件: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static void setNestedValue(Map<String, Object> root, String keyPath, Object value) {
        String[] keys = keyPath.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = current.get(keys[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<>();
                current.put(keys[i], child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(keys[keys.length - 1], value);
    }

    private static void writeYaml(Map<String, Object> root) throws Exception {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("无法创建目录: " + CONFIG_DIR);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        Yaml yaml = new Yaml(options);
        try (Writer writer = new OutputStreamWriter(new java.io.FileOutputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {
            yaml.dump(root, writer);
        }
    }

    /**
     * 敏感字段日志脱敏
     */
    private static String maskValue(String keyPath, Object value) {
        if (value == null) return "null";
        String key = keyPath.toLowerCase();
        if (key.contains("api-key") || key.contains("apikey") || key.contains("password") || key.contains("secret")) {
            String s = String.valueOf(value);
            if (s.length() <= 8) return "****";
            return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
        }
        return String.valueOf(value);
    }
}
