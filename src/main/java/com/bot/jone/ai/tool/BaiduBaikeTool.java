package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.config.BaikeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度百科工具：通过百度千帆 AppBuilder 的「百度百科」组件按词条名查询权威词条内容。
 *
 * 接口：GET https://appbuilder.baidu.com/v2/baike/lemma/get_content?search_type=lemmaTitle&search_key=<词条名>
 * 鉴权：Authorization: Bearer <AppBuilder API Key>
 *
 * 用途：回答"某人物/机构/产品/地名/术语/事件是什么"这类需要权威、结构化百科事实的问题。
 * 与 web_search 的分工：本工具给词条释义与概述，web_search 给时效性信息与多来源交叉印证。
 *
 * API Key 由 {@link BaikeConfig} 提供（配置项优先，回退环境变量 BAIDU_API_KEY）；
 * 未配置或未启用时不发请求，直接返回提示，交由 LLM 决定改用其它工具。
 */
@Slf4j
@Component
public class BaiduBaikeTool implements BotTool {

    private static final String ENDPOINT = "https://appbuilder.baidu.com/v2/baike/lemma/get_content";
    private static final int TIMEOUT_MS = 15000;
    /** 概述注入 LLM 的截断上限（字符），防止单次工具结果吃掉过多上下文 */
    private static final int MAX_OVERVIEW_CHARS = 1200;
    /** 关联词条最多返回条数 */
    private static final int MAX_RELATIONS = 8;

    @Autowired
    private BaikeConfig baikeConfig;

    @Override
    public String name() {
        return "baidu_baike";
    }

    @Override
    public String description() {
        return "查询百度百科词条，获取权威的词条释义、概述与关联信息（人物、机构、产品、地名、术语、历史事件等）。"
                + "当用户问及某个专有名词是什么、某个人/机构/产品的背景介绍时优先调用；"
                + "仅支持按词条名精确查询，不适用于时效性新闻或需要多来源印证的模糊问题（那些用 web_search）。";
    }

    @Override
    public JSONObject getParameters() {
        JSONObject params = new JSONObject();
        params.put("type", "object");

        JSONObject props = new JSONObject();
        props.put("keyword", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "要查询的百科词条名，例如 '刘德华'、'量子计算'、'清华大学'。用规范名称，不要带疑问句"));

        params.put("properties", props);
        params.put("required", new JSONArray().fluentAdd("keyword"));
        return params;
    }

    @Override
    public String execute(JSONObject args) {
        String keyword = args == null ? null : args.getString("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return "[参数缺失] baidu_baike 需要 keyword 参数";
        }
        if (!baikeConfig.isEnabled()) {
            return "[未启用] 百度百科工具当前已关闭，请在 WebUI「搜索源管理」页开启。";
        }
        String apiKey = baikeConfig.resolveApiKey();
        if (apiKey == null) {
            return "[未配置] 未找到百度 API Key：请到 WebUI「搜索源管理」页填写，或设置环境变量 BAIDU_API_KEY。";
        }
        try {
            return queryLemma(keyword.trim(), apiKey);
        } catch (Exception e) {
            log.error("[baidu_baike] 查询异常 keyword={}", keyword, e);
            return "[查询失败] 查询词条 '" + keyword + "' 时发生异常: " + e.getMessage();
        }
    }

    private String queryLemma(String keyword, String apiKey) throws Exception {
        String url = ENDPOINT + "?search_type=lemmaTitle&search_key=" + URLEncoder.encode(keyword, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        int status = conn.getResponseCode();
        String body = readStream(status == 200 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();

        if (status != 200) {
            return "[查询失败] 百度百科接口返回 HTTP " + status + (body.isEmpty() ? "" : "：" + truncate(body, 200));
        }

        JSONObject json = JSON.parseObject(body);
        JSONObject result = json == null ? null : json.getJSONObject("result");
        if (result == null) {
            String message = json == null ? null : json.getString("message");
            return "[查询无结果] 百度百科未收录词条 '" + keyword + "'"
                    + (message == null || message.isEmpty() ? "" : "（" + message + "）")
                    + "。可换用更规范的词条名，或改用 web_search 搜索。";
        }
        return format(keyword, result);
    }

    /** 把接口返回的 result 拼成给 LLM 读的纯文本（只保留摘要级信息，不塞图片/视频等字段） */
    private String format(String keyword, JSONObject result) {
        StringBuilder sb = new StringBuilder();
        String title = result.getString("lemma_title");
        if (title == null || title.isEmpty()) {
            title = keyword;
        }
        sb.append("【百度百科】").append(title);
        String desc = result.getString("lemma_desc");
        if (desc != null && !desc.isEmpty()) {
            sb.append("（").append(desc).append("）");
        }
        sb.append("\n");

        JSONArray classify = result.getJSONArray("classify");
        if (classify != null && !classify.isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < classify.size(); i++) {
                String tag = classify.getString(i);
                if (tag != null && !tag.isEmpty()) {
                    tags.add(tag);
                }
            }
            if (!tags.isEmpty()) {
                sb.append("分类: ").append(String.join("、", tags)).append("\n");
            }
        }

        String url = result.getString("url");
        if (url != null && !url.isEmpty()) {
            sb.append("链接: ").append(url).append("\n");
        }

        JSONArray relations = result.getJSONArray("relations");
        if (relations != null && !relations.isEmpty()) {
            List<String> rels = new ArrayList<>();
            for (int i = 0; i < relations.size() && rels.size() < MAX_RELATIONS; i++) {
                JSONObject relation = relations.getJSONObject(i);
                if (relation == null) {
                    continue;
                }
                String relationTitle = relation.getString("lemma_title");
                if (relationTitle == null || relationTitle.isEmpty()) {
                    continue;
                }
                String relationName = relation.getString("relation_name");
                rels.add(relationName == null || relationName.isEmpty()
                        ? relationTitle : relationName + "-" + relationTitle);
            }
            if (!rels.isEmpty()) {
                sb.append("相关: ").append(String.join("、", rels)).append("\n");
            }
        }

        // 概述优先取纯文本版；缺失时回退到 summary（口语化摘要）
        String overview = result.getString("abstract_plain");
        if (overview == null || overview.trim().isEmpty()) {
            overview = result.getString("summary");
        }
        if (overview != null && !overview.trim().isEmpty()) {
            sb.append("概述:\n").append(truncate(overview.trim(), MAX_OVERVIEW_CHARS));
        }
        return sb.toString().trim();
    }

    private String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
