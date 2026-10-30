package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页搜索工具：联网搜索以获取实时、准确的事实信息，弥补 LLM 知识截止与“凭记忆编造”的短板。
 *
 * 搜索引擎后端通过配置 bot.ai.tools.search-engine 选择：
 *   - bing（全球版，推荐配合代理）：www.bing.com，免 key、解析稳定；直连国内会回重定向到 cn，
 *     配合出站代理（ProxyConfig）经海外出口即可稳定返回国际/英文结果。
 *   - duckduckgo：html.duckduckgo.com，无需 API key；直连国内通常不可达，需配合出站代理（ProxyConfig）使用，
 *     可达后 HTML 端点对自动化访问偶发返回 HTTP 202（软限流/挑战），searchDuckDuckGo 已做重试兜底。
 *   - baidu：www.baidu.com，国内可达，但反爬极严、裸请求常被验证码拦截、解析结果不稳定。
 *
 * 以上均为"尽力而为"的免 key 方案；若你的网络环境都无法访问，请自备搜索 API 并扩展 engine。
 * 解析失败或反爬拦截时，会返回友好提示而非异常，交由 LLM 自行决定是否如实告知用户查不到。
 *
 * 代理感知：借助 ProxyConfig 的全局 ProxySelector，DuckDuckGo / 全球必应等需代理的引擎在
 * 代理启用时返回国际结果；代理关闭时 resolveEngine() 会自动回退到国内可达的必应，搜索不硬失败。
 */
@Slf4j
@Component
public class WebSearchTool implements BotTool {

    @Autowired
    private com.bot.jone.ai.AiConfig aiConfig;

    @Autowired
    private com.bot.jone.config.ProxyConfig proxyConfig;

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索网页，获取实时、准确的事实信息（如新闻、最新数据、人物/事件背景、技术文档等）。"
                + "当用户的问题涉及你不确定的、或可能过时的事实信息时，优先调用本工具查证，用搜索结果支撑回答以提高准确性。"
                + "仅返回搜索结果的标题、链接与摘要，不会抓取整页内容。";
    }

    @Override
    public JSONObject getParameters() {
        JSONObject params = new JSONObject();
        params.put("type", "object");

        JSONObject props = new JSONObject();
        props.put("query", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "搜索关键词或问题，例如 '2026 春节 日期'、'Java StringBuilder 用法'"));

        params.put("properties", props);
        params.put("required", new JSONArray().fluentAdd("query"));
        return params;
    }

    /**
     * 解析实际使用的搜索引擎，结合代理开关做运行时兜底：
     * - 代理启用时，使用用户在配置中指定的引擎（国际/英文结果）。
     * - 代理关闭时，DuckDuckGo 及未知引擎在国内直连不可达，自动回退到必应
     *   （www.bing.com 在国内会被重定向到 cn.bing.com，仍返回结果），保证搜索不硬失败；
     *   必应/百度本身国内可达，不受影响。
     */
    private String resolveEngine() {
        String engine = aiConfig.getSearchEngine();
        if (proxyConfig.isEnabled()) {
            return engine;
        }
        boolean domesticReachable = "bing".equalsIgnoreCase(engine) || "baidu".equalsIgnoreCase(engine);
        if (!domesticReachable) {
            log.warn("[web_search] 代理未启用，引擎 [{}] 直连不可达，回退到必应（国内可达）", engine);
            return "bing";
        }
        return engine;
    }

    @Override
    public String execute(JSONObject args) {
        String query = args == null ? null : args.getString("query");
        if (query == null || query.trim().isEmpty()) {
            return "[参数缺失] web_search 需要 query 参数";
        }
        int maxResults = aiConfig.getSearchMaxResults();
        String engine = resolveEngine();
        try {
            if ("bing".equalsIgnoreCase(engine)) {
                return searchBing(query, maxResults);
            }
            if ("baidu".equalsIgnoreCase(engine)) {
                return searchBaidu(query, maxResults);
            }
            // 默认走 duckduckgo；未知 engine 也回退到 duckduckgo
            return searchDuckDuckGo(query, maxResults);
        } catch (Exception e) {
            log.error("[web_search] 搜索异常 query={}", query, e);
            return "[搜索失败] 查询 '" + query + "' 时发生异常: " + e.getMessage();
        }
    }

    // ---------------- DuckDuckGo（无 key） ----------------

    private String searchDuckDuckGo(String query, int maxResults) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8");
        // DuckDuckGo 的 HTML 端点对自动化访问常返回 202（软限流/挑战），短暂重试通常可恢复；
        // 借助代理出口时该端点可达，故这里只处理 202 这类瞬时失败，而非“网络不可达”。
        int maxAttempts = 3;
        long retryDelayMs = 800;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 200) {
                StringBuilder html = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        html.append(line);
                    }
                }
                conn.disconnect();
                return parseDuckDuckGo(html.toString(), maxResults);
            }
            conn.disconnect();
            if (attempt < maxAttempts) {
                log.warn("[web_search] DuckDuckGo 返回 HTTP {}（第 {} 次），{}ms 后重试", code, attempt, retryDelayMs);
                Thread.sleep(retryDelayMs);
            } else {
                return "[搜索失败] DuckDuckGo 返回 HTTP " + code;
            }
        }
        return "[搜索失败] DuckDuckGo 搜索未成功";
    }

    private String parseDuckDuckGo(String html, int maxResults) {
        List<String[]> results = new ArrayList<>();
        // 标题：<a class="result__a" href="...">...</a>
        Pattern titlePat = Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher tm = titlePat.matcher(html);
        while (tm.find() && results.size() < maxResults) {
            String href = tm.group(1);
            String title = stripTags(decodeEntities(tm.group(2))).trim();
            results.add(new String[]{title, extractDdgUrl(href), ""});
        }
        // 摘要：<a class="result__snippet" ...>...</a>
        Pattern snipPat = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher sm = snipPat.matcher(html);
        int idx = 0;
        while (sm.find() && idx < results.size()) {
            results.get(idx)[2] = stripTags(decodeEntities(sm.group(1))).trim();
            idx++;
        }
        if (results.isEmpty()) {
            return "[搜索无结果] 未从 DuckDuckGo 找到相关条目（可能该引擎当前不可用或需更换关键词）。";
        }
        return formatResults(results);
    }

    private String extractDdgUrl(String ddgHref) {
        // DuckDuckGo 跳转链接形如 /redirect?uddg=<encoded_url>&...
        if (ddgHref != null && ddgHref.contains("uddg=")) {
            int i = ddgHref.indexOf("uddg=") + 5;
            int j = ddgHref.indexOf('&', i);
            String enc = j > 0 ? ddgHref.substring(i, j) : ddgHref.substring(i);
            try {
                return java.net.URLDecoder.decode(enc, "UTF-8");
            } catch (Exception e) {
                return ddgHref;
            }
        }
        return ddgHref;
    }

    // ---------------- 必应全球版（无 key，配合代理出国际结果） ----------------

    private String searchBing(String query, int maxResults) throws Exception {
        String url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code != 200) {
            return "[搜索失败] 必应返回 HTTP " + code;
        }
        StringBuilder html = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                html.append(line);
            }
        }
        return parseBing(html.toString(), maxResults);
    }

    private String parseBing(String html, int maxResults) {
        List<String[]> results = new ArrayList<>();
        // 每个自然结果位于 <li class="b_algo"> 块内
        Pattern blockPat = Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL);
        Matcher bm = blockPat.matcher(html);
        while (bm.find() && results.size() < maxResults) {
            String block = bm.group(1);
            // 标题与链接：<h2 class=""><a href="...">标题</a></h2>（h2 可能带 class 等属性）
            Matcher tm = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(block);
            if (!tm.find()) {
                continue;
            }
            String href = tm.group(1);
            String title = stripTags(decodeEntities(tm.group(2))).trim();
            // 摘要：块内第一个 <p> 标签
            String snippet = "";
            Matcher sm = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(block);
            if (sm.find()) {
                snippet = stripTags(decodeEntities(sm.group(1))).trim();
            }
            results.add(new String[]{title, href, snippet});
        }
        if (results.isEmpty()) {
            return "[搜索无结果] 必应未返回有效结果（可能该引擎当前不可用或需更换关键词）。";
        }
        return formatResults(results);
    }

    // ---------------- 百度（无 key，国内备选） ----------------

    private String searchBaidu(String query, int maxResults) throws Exception {
        String url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code != 200) {
            return "[搜索失败] 百度返回 HTTP " + code;
        }
        StringBuilder html = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                html.append(line);
            }
        }
        return parseBaidu(html.toString(), maxResults);
    }

    private String parseBaidu(String html, int maxResults) {
        List<String[]> results = new ArrayList<>();
        // 标题：<h3 class="t"><a href="...">...</a></h3>
        Pattern titlePat = Pattern.compile("class=\"t\"[^>]*>\\s*<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher tm = titlePat.matcher(html);
        while (tm.find() && results.size() < maxResults) {
            String href = tm.group(1);
            String title = stripTags(decodeEntities(tm.group(2))).trim();
            results.add(new String[]{title, href, ""});
        }
        // 摘要：class 含 abstract 的 div
        Pattern snipPat = Pattern.compile("class=\"[^\"]*abstract[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
        Matcher sm = snipPat.matcher(html);
        int idx = 0;
        while (sm.find() && idx < results.size()) {
            results.get(idx)[2] = stripTags(decodeEntities(sm.group(1))).trim();
            idx++;
        }
        if (results.isEmpty()) {
            return "[搜索无结果] 百度未返回有效结果（可能被反爬拦截或需更换关键词）。";
        }
        return formatResults(results);
    }

    // ---------------- 公共 ----------------

    private String formatResults(List<String[]> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索结果（共 ").append(results.size()).append(" 条）：\n");
        int n = 1;
        for (String[] r : results) {
            sb.append(n++).append(". ").append(r[0]).append("\n");
            sb.append("   URL: ").append(r[1]).append("\n");
            if (!r[2].isEmpty()) {
                sb.append("   摘要: ").append(r[2]).append("\n");
            }
        }
        return sb.toString();
    }

    private String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private String decodeEntities(String s) {
        s = s.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&ensp;", " ")
                .replace("&emsp;", " ").replace("&thinsp;", " ")
                .replace("&#0183;", "·");
        // 通用数字字符实体（如 &#nnn;）转空格，避免残留乱码
        s = s.replaceAll("&#\\d+;", " ");
        return s;
    }
}
