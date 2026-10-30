package com.bot.jone.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型资源管理器：本地优先 + 缺失自动拉取（可降级）。
 *
 * 启动(@PostConstruct)时扫描 bot.models.models 中每个模型：
 *  - 解析本地目录与文件清单（files 为空则继承 ModelCatalogue）；
 *  - 文件齐全 → READY；
 *  - 缺失且允许自动下载 → 后台拉取（DOWNLOADING→READY/ERROR）；
 *  - 缺失且不允许自动下载 → MISSING（仅记录，不影响 bot 启动）。
 *
 * 后续 EmbeddingService 通过 {@link #resolveFile(String, String)} 取得本地路径加载 ONNX。
 */
@Slf4j
@Component
public class ModelManager {

    @Autowired
    private ModelConfig modelConfig;
    @Autowired
    private ModelDownloader modelDownloader;

    private final Path workDir = Paths.get(System.getProperty("user.dir"));
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public enum Status { READY, MISSING, DOWNLOADING, ERROR, DISABLED }

    @Getter
    public static class ResolvedFile {
        private final String name;
        private final Path localPath;
        private final String url;
        private final String sha256;

        public ResolvedFile(String name, Path localPath, String url, String sha256) {
            this.name = name;
            this.localPath = localPath;
            this.url = url;
            this.sha256 = sha256;
        }

        /** 实时检测磁盘，避免下载后仍返回启动时的快照值。 */
        public boolean isPresent() {
            File f = localPath.toFile();
            return Files.exists(localPath) && f.length() > 0;
        }
    }

    @Getter
    public static class Entry {
        private final String key;
        private final String type;
        private final int dim;
        private final String description;
        private final boolean enabled;
        private final boolean autoDownload;
        private final String localDir;
        private final List<ResolvedFile> files;
        private volatile Status status;
        private volatile String message;

        public Entry(String key, String type, int dim, String description, boolean enabled,
                     boolean autoDownload, String localDir, List<ResolvedFile> files, Status status, String message) {
            this.key = key;
            this.type = type;
            this.dim = dim;
            this.description = description;
            this.enabled = enabled;
            this.autoDownload = autoDownload;
            this.localDir = localDir;
            this.files = files;
            this.status = status;
            this.message = message;
        }
    }

    @PostConstruct
    public void init() {
        for (ModelConfig.ModelSpec spec : modelConfig.getModels()) {
            if (spec.getKey() == null || spec.getKey().isBlank()) {
                log.warn("[model] 发现未配置 key 的模型项，已跳过");
                continue;
            }
            try {
                resolveAndRegister(spec);
            } catch (Exception e) {
                log.error("[model] 解析模型 {} 失败", spec.getKey(), e);
            }
        }
        log.info("[model] 模型管理器初始化完成，共登记 {} 个模型", entries.size());
    }

    /** 解析单个 spec 并登记条目（按是否允许自动下载决定是否立即后台拉取）。 */
    private void resolveAndRegister(ModelConfig.ModelSpec spec) {
        ModelCatalogue.ModelInfo info = ModelCatalogue.get(spec.getKey());
        String type = info != null ? info.getType() : spec.getKey();
        int dim = info != null ? info.getDim() : 0;
        String description = info != null ? info.getDescription() : "";
        String localDir = (spec.getLocalDir() == null || spec.getLocalDir().isBlank())
                ? (info != null ? info.getRecommendedLocalDir() : "data/models/" + spec.getKey())
                : spec.getLocalDir();

        boolean effectiveAuto = spec.getAutoDownload() != null
                ? spec.getAutoDownload() : modelConfig.isAutoDownloadEnabled();

        List<ResolvedFile> resolved = new ArrayList<>();
        List<ModelConfig.ModelFile> explicit = spec.getFiles();
        if (explicit != null && !explicit.isEmpty()) {
            for (ModelConfig.ModelFile f : explicit) {
                Path p = workDir.resolve(localDir).resolve(f.getName());
                resolved.add(new ResolvedFile(f.getName(), p, f.getUrl(), f.getSha256()));
            }
        } else if (info != null) {
            for (ModelCatalogue.FileInfo f : info.getFiles()) {
                Path p = workDir.resolve(localDir).resolve(f.getName());
                resolved.add(new ResolvedFile(f.getName(), p, f.getUrl(), f.getSha256()));
            }
        } else {
            log.warn("[model] 模型 {} 既无显式 files 也无目录默认文件，无法管理", spec.getKey());
        }

        boolean allPresent = resolved.stream().allMatch(ResolvedFile::isPresent);
        Status status;
        String message;
        if (!spec.isEnabled()) {
            status = Status.DISABLED;
            message = "已禁用";
        } else if (allPresent) {
            status = Status.READY;
            message = "本地就绪";
        } else if (effectiveAuto) {
            status = Status.DOWNLOADING;
            message = "缺失，后台下载中";
        } else {
            status = Status.MISSING;
            message = "本地缺失，未开启自动下载（请手动放置权重，或在面板触发下载 / 开启 auto-download）";
        }

        Entry entry = new Entry(spec.getKey(), type, dim, description, spec.isEnabled(),
                effectiveAuto, localDir, resolved, status, message);
        entries.put(spec.getKey(), entry);
        log.info("[model] 模型 {} : {}", spec.getKey(), message);

        if (status == Status.DOWNLOADING && effectiveAuto) {
            startDownload(spec.getKey());
        }
    }

    private boolean isPresent(Path p) {
        File f = p.toFile();
        return Files.exists(p) && f.length() > 0;
    }

    private void startDownload(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return;
        entry.status = Status.DOWNLOADING;
        entry.message = "后台下载中";
        CompletableFuture.runAsync(() -> doDownload(entry));
    }

    /** 执行下载（被后台线程与手动触发共用） */
    private void doDownload(Entry entry) {
        try {
            final long[] lastLog = {0};
            for (ResolvedFile rf : entry.getFiles()) {
                if (isPresent(rf.getLocalPath())) continue;
                if (rf.getUrl() == null || rf.getUrl().isBlank()) {
                    throw new IllegalStateException("文件 " + rf.getName() + " 无下载 URL");
                }
                log.info("[model] 开始下载 {} -> {}", rf.getUrl(), rf.getLocalPath());
                modelDownloader.download(rf.getUrl(), rf.getLocalPath().toFile(), rf.getSha256(),
                        (done, total) -> {
                            if (done - lastLog[0] >= 16L * 1024 * 1024) {
                                lastLog[0] = done;
                                log.info("[model] {} 下载进度 {}/{}", rf.getName(), human(done), total > 0 ? human(total) : "?");
                            }
                        });
            }
            boolean ok = entry.getFiles().stream().allMatch(rf -> isPresent(rf.getLocalPath()));
            entry.status = ok ? Status.READY : Status.ERROR;
            entry.message = ok ? "下载完成，本地就绪" : "下载后仍有文件缺失";
        } catch (Exception e) {
            entry.status = Status.ERROR;
            entry.message = "下载失败: " + e.getMessage();
            log.error("[model] 模型 {} 下载失败", entry.getKey(), e);
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        int u = 0;
        String[] uu = {"B", "KB", "MB", "GB", "TB"};
        double d = bytes;
        while (d >= 1024 && u < uu.length - 1) {
            d /= 1024;
            u++;
        }
        return String.format("%.1f%s", d, uu[u]);
    }

    public List<Entry> getEntries() {
        return new ArrayList<>(entries.values());
    }

    public Entry getEntry(String key) {
        return entries.get(key);
    }

    public boolean isReady(String key) {
        Entry e = entries.get(key);
        return e != null && e.getStatus() == Status.READY;
    }

    /** 供未来 EmbeddingService 加载 ONNX：返回某模型某文件的本地绝对路径（不保证存在）。 */
    public Path resolveFile(String key, String fileName) {
        Entry e = entries.get(key);
        if (e == null) return null;
        for (ResolvedFile rf : e.getFiles()) {
            if (rf.getName().equals(fileName)) return rf.getLocalPath();
        }
        return workDir.resolve(e.getLocalDir()).resolve(fileName);
    }

    /** WebUI 手动触发下载（即使未开启 auto-download） */
    public synchronized CompletableFuture<Boolean> downloadAsync(String key) {
        Entry e = entries.get(key);
        if (e == null) return CompletableFuture.completedFuture(false);
        boolean allPresent = e.getFiles().stream().allMatch(ResolvedFile::isPresent);
        if (allPresent) return CompletableFuture.completedFuture(true);
        e.status = Status.DOWNLOADING;
        e.message = "下载中（手动触发）";
        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            doDownload(e);
            fut.complete(e.getStatus() == Status.READY);
        });
        return fut;
    }
}
