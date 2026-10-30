package com.bot.jone.config;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 已知嵌入 / 重排模型目录（内置静态表）。
 *
 * 用途：
 *  1. 作为 {@code bot.models.models} 中未显式列出 files 时的默认文件清单来源（本地优先 + 缺失自动拉取都依赖它）；
 *  2. 供 WebUI「模型管理」面板展示「可下载模型目录」，让用户清楚有哪些模型、各是什么、去哪下载。
 *
 * 所有 URL 均指向 ModelScope 的 ONNX 权重直链（{@code resolve/master/...}），可直接经 JVM 的
 * ProxySelector 下载（魔搭为国内站，可在 bot.proxy.non-proxy-hosts 追加 modelscope.cn 以直连提速）。
 *
 * 注意：模型权重体积较大（bge-m3 约 2.3GB、bge-small-zh 约 120MB），下载请按需开启
 * auto-download 或在面板手动触发，不要无脑全局开启。
 */
public final class ModelCatalogue {

    @Getter
    public static class FileInfo {
        /** 本地文件名（落在 localDir 下） */
        private final String name;
        /** 远程下载 URL（ModelScope 直链） */
        private final String url;
        /** 可选 sha256（空则不校验） */
        private final String sha256;

        public FileInfo(String name, String url, String sha256) {
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
        }

        public FileInfo(String name, String url) {
            this(name, url, null);
        }
    }

    @Getter
    public static class ModelInfo {
        private final String key;
        private final String type;
        /** 向量维度；cross-encoder(reranker) 为 0（输出的是相关性分数而非定长向量） */
        private final int dim;
        private final String description;
        private final String recommendedLocalDir;
        private final List<FileInfo> files;

        public ModelInfo(String key, String type, int dim, String description,
                         String recommendedLocalDir, List<FileInfo> files) {
            this.key = key;
            this.type = type;
            this.dim = dim;
            this.description = description;
            this.recommendedLocalDir = recommendedLocalDir;
            this.files = files;
        }
    }

    private static final String M3_BASE = "https://modelscope.cn/models/tiansz/bge-m3-onnx/resolve/master/";
    private static final String SMALL_BASE = "https://modelscope.cn/models/Maiteka/bge-small-zh-v1.5-onnx/resolve/master/";
    private static final String RERANK_BASE = "https://modelscope.cn/models/onnx-community/bge-reranker-v2-m3-ONNX/resolve/master/";

    private static final Map<String, ModelInfo> CATALOGUE = new LinkedHashMap<>();

    static {
        CATALOGUE.put("bge-m3", new ModelInfo(
                "bge-m3", "bge-m3", 1024,
                "BAAI 多功能向量模型：一次前向同时输出 dense(1024维) / sparse / 多向量 三路信号，"
                        + "最契合混合检索；支持 8192 长上下文，适合切块对话。权重约 2.3GB，建议本地放置。",
                "data/models/bge-m3",
                Arrays.asList(
                        new FileInfo("model.onnx", M3_BASE + "model.onnx"),
                        new FileInfo("model.onnx.data", M3_BASE + "model.onnx.data"),
                        new FileInfo("tokenizer.json", M3_BASE + "tokenizer.json"),
                        new FileInfo("sentencepiece.bpe.model", M3_BASE + "sentencepiece.bpe.model"),
                        new FileInfo("config.json", M3_BASE + "config.json"),
                        new FileInfo("tokenizer_config.json", M3_BASE + "tokenizer_config.json"),
                        new FileInfo("special_tokens_map.json", M3_BASE + "special_tokens_map.json")
                )));

        CATALOGUE.put("bge-small-zh", new ModelInfo(
                "bge-small-zh", "bge-small-zh-v1.5", 512,
                "BAAI 轻量中文向量模型（512维），速度快、内存占用小，适合做实时粗排召回；"
                        + "默认用 INT8 量化版(model_qint8.onnx，约 120MB)。",
                "data/models/bge-small-zh",
                Arrays.asList(
                        new FileInfo("model_qint8.onnx", SMALL_BASE + "model_qint8.onnx"),
                        new FileInfo("tokenizer.json", SMALL_BASE + "tokenizer.json"),
                        new FileInfo("vocab.txt", SMALL_BASE + "vocab.txt"),
                        new FileInfo("config.json", SMALL_BASE + "config.json"),
                        new FileInfo("tokenizer_config.json", SMALL_BASE + "tokenizer_config.json"),
                        new FileInfo("special_tokens_map.json", SMALL_BASE + "special_tokens_map.json")
                )));

        CATALOGUE.put("bge-reranker-v2-m3", new ModelInfo(
                "bge-reranker-v2-m3", "bge-reranker-v2-m3", 0,
                "BAAI 交叉编码器重排模型：输入(query, doc)对输出相关性分数，用于对已召回结果精排，"
                        + "精度高于 bge-m3 自带回排。注意：ModelScope 上 ONNX 版本的具体文件名/路径可能变动，"
                        + "启用前请在 ModelScope 页面核对 onnx 权重与 tokenizer 的实际路径。",
                "data/models/bge-reranker-v2-m3",
                Arrays.asList(
                        // 以下路径为常见布局猜测，启用前务必在 ModelScope 核对
                        new FileInfo("onnx/model_int8.onnx", RERANK_BASE + "onnx/model_int8.onnx"),
                        new FileInfo("onnx/tokenizer.json", RERANK_BASE + "onnx/tokenizer.json"),
                        new FileInfo("onnx/config.json", RERANK_BASE + "onnx/config.json")
                )));
    }

    public static ModelInfo get(String key) {
        return CATALOGUE.get(key);
    }

    /** 返回目录中所有已知模型（含未启用的），供 WebUI「可下载模型」展示 */
    public static List<ModelInfo> all() {
        return List.copyOf(CATALOGUE.values());
    }
}
