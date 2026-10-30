package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型资源管理配置（前缀 bot.models）。
 *
 * 设计：本地优先 + 缺失自动拉取。每个模型只声明 key 与（可选）本地目录，
 * 具体文件清单默认从 {@link ModelCatalogue} 继承；若需自定义（换布局 / 换源），
 * 可在 files 里显式列出覆盖。
 *
 * 下载触发规则：
 *  - 全局 auto-download-enabled=false 时不自动下载，缺失仅标记不可用（降级，不崩）；
 *  - 单模型 auto-download=true 或全局开启时，启动时若本地缺失则后台拉取；
 *  - 也可在 WebUI「模型管理」面板手动触发下载（POST /api/config/models/{key}/download）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot.models")
public class ModelConfig {

    /** 全局开关：模型文件缺失时是否自动联网拉取。默认关闭（只本地优先，缺失则降级）。 */
    private boolean autoDownloadEnabled = false;

    /** 实际管理的模型清单（key 需与 ModelCatalogue 对应以继承默认文件清单；files 留空则继承）。 */
    private List<ModelSpec> models = new ArrayList<>();

    @Data
    public static class ModelSpec {
        /** 模型标识，需与 ModelCatalogue 的 key 对应以继承默认文件清单 */
        private String key;
        /** 是否启用该模型（关闭则 Entity 不参与检索 / 加载） */
        private boolean enabled = true;
        /**
         * 该模型是否允许自动下载。null=继承全局 auto-download-enabled；
         * true=缺失即后台拉取；false=缺失仅降级。
         */
        private Boolean autoDownload;
        /** 本地目录（相对工作目录）；为空则继承 ModelCatalogue.recommendedLocalDir */
        private String localDir;
        /** 显式文件清单；非空则覆盖目录默认。每项含 name(本地名) + url(远程) + 可选 sha256 */
        private List<ModelFile> files = new ArrayList<>();
    }

    @Data
    public static class ModelFile {
        private String name;
        private String url;
        private String sha256;
    }
}
