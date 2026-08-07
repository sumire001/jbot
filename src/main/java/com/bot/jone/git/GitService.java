package com.bot.jone.git;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 远程 git 仓库查询服务（纯 JGit 实现，默认不依赖任何平台 API）。
 *
 * 能力划分：
 *  - 分支 / 标签 / PR refs：通过 ls-remote 轻量获取，无需克隆。
 *  - 提交记录 / 读文件 / diff：维护一个浅克隆缓存（首次克隆后仅 fetch 更新），直接在对象库里读取。
 *  - PR 列表：GitHub/GitLab 会把 PR 以 refs/pull/<n>/head、refs/merge-requests/<n>/head 暴露在 git 协议里，
 *            同样用 ls-remote 即可拿到，无需平台 API。
 *  - issues：平台概念，git 协议中没有；默认关闭，开启后仅此处调用平台 API。
 *
 * 鉴权：HTTPS + 个人访问令牌（token 作密码）。公开库无需 token。
 */
@Slf4j
@Service
public class GitService {

    private final GitConfig config;

    /** 读文件 / 单条结果的最大字符数（ToolExecutor 还会再截断到 3000，这里给足余量）。 */
    private static final int MAX_FILE_CHARS = 3000;

    @Autowired
    public GitService(GitConfig config) {
        this.config = config;
    }

    // ===================== 公开方法 =====================

    /** 列出分支（ls-remote，轻量）。 */
    public String getBranches(String url) {
        try {
            Collection<Ref> refs = lsRemote(url);
            List<String> branches = new ArrayList<>();
            for (Ref r : refs) {
                if (r.getName().startsWith("refs/heads/")) {
                    branches.add(r.getName().substring("refs/heads/".length())
                            + "  (" + shortSha(r.getObjectId().getName()) + ")");
                }
            }
            if (branches.isEmpty()) {
                return "[无分支] 未从 " + url + " 解析到任何分支（可能无权限或地址有误）。";
            }
            return "分支列表（共 " + branches.size() + " 条）：\n" + String.join("\n", branches);
        } catch (Exception e) {
            return "[查询失败] 获取分支异常: " + e.getMessage();
        }
    }

    /** 列出标签（ls-remote，轻量）。 */
    public String getTags(String url) {
        try {
            Collection<Ref> refs = lsRemote(url);
            List<String> tags = new ArrayList<>();
            for (Ref r : refs) {
                if (r.getName().startsWith("refs/tags/")) {
                    tags.add(r.getName().substring("refs/tags/".length())
                            + "  (" + shortSha(r.getObjectId().getName()) + ")");
                }
            }
            if (tags.isEmpty()) {
                return "[无标签] 未从 " + url + " 解析到任何标签。";
            }
            return "标签列表（共 " + tags.size() + " 条）：\n" + String.join("\n", tags);
        } catch (Exception e) {
            return "[查询失败] 获取标签异常: " + e.getMessage();
        }
    }

    /**
     * 列出 PR / MR。借助平台在 git 协议中暴露的 PR refs（无需平台 API）。
     * GitHub: refs/pull/<n>/head ；GitLab: refs/merge-requests/<n>/head ；Gitee: refs/pull/<n>/head。
     */
    public String getPrList(String url) {
        try {
            String platform = detectPlatform(url);
            Collection<Ref> refs = lsRemote(url);
            List<String> prs = new ArrayList<>();
            for (Ref r : refs) {
                String name = r.getName();
                if (isPrRef(name, platform)) {
                    prs.add(name + "  (" + shortSha(r.getObjectId().getName()) + ")");
                }
            }
            if (prs.isEmpty()) {
                if ("generic".equals(platform)) {
                    return "[不支持] 未能识别仓库平台，无法定位 PR refs。可尝试在 URL 中使用 github/gitlab/gitee 域名。";
                }
                return "[无 PR/MR] 该仓库未暴露 PR refs（可能没有任何打开的 PR，或平台不支持）。";
            }
            return "PR/MR 列表（共 " + prs.size() + " 条，head 引用）：\n" + String.join("\n", prs);
        } catch (Exception e) {
            return "[查询失败] 获取 PR/MR 异常: " + e.getMessage();
        }
    }

    /** 提交记录（基于浅克隆缓存，默认分支）。 */
    public String getLog(String url, String branch, int limit) {
        try (Git git = ensureClone(url)) {
            Repository repo = git.getRepository();
            ObjectId start = repo.resolve(branch != null && !branch.isEmpty() ? branch : "HEAD");
            if (start == null) {
                return "[解析失败] 无法解析分支/引用: " + (branch == null ? "HEAD" : branch);
            }
            int max = limit > 0 ? limit : 10;
            Iterable<RevCommit> commits = git.log().add(start).setMaxCount(max).call();
            StringBuilder sb = new StringBuilder("提交记录（最近 ").append(max).append(" 条）：\n");
            int n = 1;
            for (RevCommit c : commits) {
                sb.append(n++).append(". ").append(shortSha(c.getId().getName()))
                        .append("  ").append(c.getShortMessage()).append("\n")
                        .append("   作者: ").append(c.getAuthorIdent().getName())
                        .append("  ").append(c.getAuthorIdent().getWhen().toInstant().toString()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "[查询失败] 获取提交记录异常: " + e.getMessage();
        }
    }

    /** 读取远程文件内容（基于浅克隆缓存，直接从对象库读取，无需 checkout 工作区）。 */
    public String readFile(String url, String path, String ref) {
        if (path == null || path.trim().isEmpty()) {
            return "[参数缺失] read_file 需要 path 参数（仓库内相对路径）。";
        }
        String cleanPath = path.replaceFirst("^/+", "");
        try (Git git = ensureClone(url)) {
            Repository repo = git.getRepository();
            ObjectId commitId = repo.resolve(ref != null && !ref.isEmpty() ? ref : "HEAD");
            if (commitId == null) {
                return "[解析失败] 无法解析引用: " + (ref == null ? "HEAD" : ref);
            }
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(commitId);
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(commit.getTree());
                    tw.setRecursive(true);
                    tw.setFilter(PathFilter.create(cleanPath));
                    if (!tw.next()) {
                        return "[未找到文件] 在引用 " + (ref == null ? "HEAD" : ref) + " 下找不到路径: " + path;
                    }
                    ObjectId blobId = tw.getObjectId(0);
                    try (ObjectReader reader = repo.newObjectReader()) {
                        ObjectLoader loader = reader.open(blobId);
                        byte[] data = loader.getBytes();
                        String content = new String(data, StandardCharsets.UTF_8);
                        if (content.length() > MAX_FILE_CHARS) {
                            content = content.substring(0, MAX_FILE_CHARS)
                                    + "\n...[文件过长已截断，仅保留前 " + MAX_FILE_CHARS + " 字]";
                        }
                        return "文件 " + path + " 内容（引用 " + (ref == null ? "HEAD" : ref) + "）：\n```\n"
                                + content + "\n```";
                    }
                }
            }
        } catch (Exception e) {
            return "[查询失败] 读取文件异常: " + e.getMessage()
                    + "（注：浅克隆仅含默认分支近期历史，读历史/其他分支文件可能失败，可调大 cloneDepth）";
        }
    }

    /** 两个引用之间的变更文件清单（名称级 diff，不展开 patch）。 */
    public String getDiff(String url, String baseRef, String headRef) {
        try (Git git = ensureClone(url)) {
            Repository repo = git.getRepository();
            ObjectId base = repo.resolve(baseRef != null && !baseRef.isEmpty() ? baseRef : "HEAD~1");
            ObjectId head = repo.resolve(headRef != null && !headRef.isEmpty() ? headRef : "HEAD");
            if (base == null || head == null) {
                return "[解析失败] 无法解析 diff 的 base/head 引用。";
            }
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(prepareTreeParser(repo, base))
                    .setNewTree(prepareTreeParser(repo, head))
                    .call();
            if (diffs.isEmpty()) {
                return "[无差异] " + (baseRef == null ? "HEAD~1" : baseRef) + " 与 "
                        + (headRef == null ? "HEAD" : headRef) + " 之间没有差异。";
            }
            StringBuilder sb = new StringBuilder("变更文件（共 ").append(diffs.size()).append(" 个）：\n");
            for (DiffEntry d : diffs) {
                sb.append("[").append(d.getChangeType()).append("] ")
                        .append(d.getChangeType() == DiffEntry.ChangeType.DELETE
                                ? d.getOldPath() : d.getNewPath()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "[查询失败] 获取 diff 异常: " + e.getMessage();
        }
    }

    /**
     * issues 查询——唯一用到平台 API 的地方，默认关闭。
     * 支持 GitHub 与 GitLab（含自托管）。开启条件：config.issuesEnabled=true 且存在 token。
     */
    public String getIssues(String url) {
        if (!config.isIssuesEnabled()) {
            return "[未启用] issues 查询默认关闭（避免依赖平台 API）。如需启用，请在 bot.git 配置 "
                    + "issues.enabled=true 并提供 token（环境变量 GIT_TOKEN 或 bot.git.token）。";
        }
        String token = resolveToken();
        if (token == null || token.isEmpty()) {
            return "[未配置] issues 已启用但缺少 token，无法调用平台 API。";
        }
        try {
            String platform = detectPlatform(url);
            String apiUrl;
            String authHeader;
            if ("gitlab".equals(platform)) {
                String project = projectPath(url);
                apiUrl = hostOf(url) + "/api/v4/projects/" + URLEncoder.encode(project, "UTF-8")
                        + "/issues?state=open&per_page=" + config.getIssuesMax();
                authHeader = "PRIVATE-TOKEN";
            } else { // github / gitee 走 GitHub 风格 REST
                String[] og = ownerRepo(url);
                apiUrl = "https://api.github.com/repos/" + og[0] + "/" + og[1]
                        + "/issues?state=open&per_page=" + config.getIssuesMax();
                authHeader = "Authorization";
            }
            String json = httpGet(apiUrl, authHeader, token);
            return formatIssues(json, platform);
        } catch (Exception e) {
            return "[查询失败] 获取 issues 异常: " + e.getMessage();
        }
    }

    // ===================== 内部工具 =====================

    private Collection<Ref> lsRemote(String url) throws Exception {
        CredentialsProvider cp = credentials(url);
        return Git.lsRemoteRepository()
                .setRemote(url)
                .setCredentialsProvider(cp)
                .setTimeout(config.getTimeoutSeconds())
                .call();
    }

    /** 确保本地有浅克隆缓存；已存在则 fetch 更新。返回 Git（调用方用 try-with-resources 关闭）。 */
    private Git ensureClone(String url) throws Exception {
        File dir = repoDir(url);
        CredentialsProvider cp = credentials(url);
        if (dir.exists() && new File(dir, ".git").isDirectory()) {
            Git git = Git.open(dir);
            try {
                git.fetch().setCredentialsProvider(cp)
                        .setTimeout(config.getTimeoutSeconds())
                        .setDepth(config.getCloneDepth())
                        .call();
                // 关键修复：git fetch 只更新 origin/<branch> 远程跟踪引用，不会前移本地分支。
                // 若只读本地 HEAD/master，会一直停留在首次克隆时的旧提交（"fetch 了却看不到新提交"）。
                // 这里把本地分支快进到对应的 origin 分支，确保 getLog/readFile/diff 看到最新提交。
                fastForwardLocalBranches(git);
            } catch (Exception e) {
                log.warn("[git] fetch 更新失败，沿用已有克隆: {}", e.getMessage());
            }
            return git;
        }
        Files.createDirectories(dir.getParentFile().toPath());
        return Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setCredentialsProvider(cp)
                .setTimeout(config.getTimeoutSeconds())
                .setDepth(config.getCloneDepth())
                .call();
    }

    /** fetch 后把本地分支快进到对应的 origin 远程跟踪分支，解决“缓存里看不到新提交”的问题。 */
    private void fastForwardLocalBranches(Git git) throws Exception {
        Repository repo = git.getRepository();
        java.util.Map<String, Ref> remoteRefs = repo.getRefDatabase().getRefs("refs/remotes/origin/");
        for (Ref remoteRef : remoteRefs.values()) {
            String name = remoteRef.getName();
            if (!name.startsWith("refs/remotes/origin/")) {
                continue;
            }
            String branch = name.substring("refs/remotes/origin/".length());
            if (branch.isEmpty() || "HEAD".equals(branch)) {
                continue; // 跳过 origin/HEAD 符号引用
            }
            RefUpdate update = repo.updateRef("refs/heads/" + branch);
            update.setNewObjectId(remoteRef.getObjectId());
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (result == RefUpdate.Result.REJECTED || result == RefUpdate.Result.LOCK_FAILURE
                    || result == RefUpdate.Result.IO_FAILURE) {
                log.warn("[git] 本地分支 {} 快进结果: {}", branch, result);
            }
        }
    }

    private File repoDir(String url) {
        Path base = Paths.get(config.getCacheDir());
        String safe = url.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        safe = safe + "_" + Math.abs(url.hashCode());
        return base.resolve(safe).toFile();
    }

    private CredentialsProvider credentials(String url) {
        String token = resolveToken();
        if (token == null || token.isEmpty()) {
            return null; // 公开库
        }
        return new UsernamePasswordCredentialsProvider(config.getUsername(), token);
    }

    private String resolveToken() {
        String env = System.getenv("GIT_TOKEN");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return config.getToken();
    }

    private static String detectPlatform(String url) {
        String u = url.toLowerCase();
        if (u.contains("github.com")) {
            return "github";
        }
        if (u.contains("gitlab")) {
            return "gitlab";
        }
        if (u.contains("gitee.com")) {
            return "gitee";
        }
        return "generic";
    }

    private static boolean isPrRef(String refName, String platform) {
        if ("github".equals(platform) || "gitee".equals(platform)) {
            return refName.startsWith("refs/pull/");
        }
        if ("gitlab".equals(platform)) {
            return refName.startsWith("refs/merge-requests/");
        }
        // generic：两种都试
        return refName.startsWith("refs/pull/") || refName.startsWith("refs/merge-requests/");
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }

    private static String hostOf(String url) {
        // https://gitlab.example.com/... -> https://gitlab.example.com
        int idx = url.indexOf("://");
        int start = idx >= 0 ? idx + 3 : 0;
        int end = url.indexOf('/', start);
        return end > 0 ? url.substring(0, end) : url;
    }

    /** 从 URL 解析 owner / repo（去掉 .git）。 */
    private static String[] ownerRepo(String url) {
        String u = url;
        if (u.endsWith(".git")) {
            u = u.substring(0, u.length() - 4);
        }
        int start = u.indexOf("://");
        start = start >= 0 ? u.indexOf('/', start + 3) + 1 : 0;
        String path = u.substring(start);
        String[] parts = path.split("/");
        String owner = parts.length > 0 ? parts[0] : "";
        String repo = parts.length > 1 ? parts[1] : "";
        return new String[]{owner, repo};
    }

    /** GitLab 项目路径（含 namespace，如 group/sub/project）。 */
    private static String projectPath(String url) {
        String[] og = ownerRepo(url);
        return og[0] + "/" + og[1];
    }

    private static CanonicalTreeParser prepareTreeParser(Repository repo, ObjectId id) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(id);
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, commit.getTree().getId());
            }
            return parser;
        }
    }

    private static String httpGet(String url, String authHeader, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "J-One-Bot");
        if ("Authorization".equals(authHeader)) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        } else {
            conn.setRequestProperty(authHeader, token);
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        StringBuilder body = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                body.append(line);
            }
        }
        if (code != 200) {
            throw new RuntimeException("HTTP " + code + " " + body);
        }
        return body.toString();
    }

    private static String formatIssues(String json, String platform) {
        JSONArray arr = JSONArray.parseArray(json);
        if (arr == null || arr.isEmpty()) {
            return "[无 issues] 未找到打开中的 issues。";
        }
        StringBuilder sb = new StringBuilder("Issues（共 ").append(arr.size()).append(" 条）：\n");
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String num = platform.equals("gitlab") ? String.valueOf(o.getIntValue("iid"))
                    : String.valueOf(o.getIntValue("number"));
            String title = o.getString("title");
            String state = o.getString("state");
            String link = platform.equals("gitlab") ? o.getString("web_url") : o.getString("html_url");
            sb.append("#").append(num).append(" [").append(state).append("] ").append(title);
            if (link != null) {
                sb.append("  ").append(link);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
