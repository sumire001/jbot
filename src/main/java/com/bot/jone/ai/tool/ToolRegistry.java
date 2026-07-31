package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。Spring 会自动把所有 BotTool 实现注入到构造器的 List<BotTool> 中，
 * 按 name 建立索引，并提供转成 OpenAI tools[] 数组的方法。
 */
@Component
public class ToolRegistry {

    private final Map<String, BotTool> tools = new LinkedHashMap<>();

    @Autowired
    public ToolRegistry(List<BotTool> toolList) {
        if (toolList != null) {
            for (BotTool t : toolList) {
                tools.put(t.name(), t);
            }
        }
    }

    public BotTool get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 转为 OpenAI 格式的 tools[] 数组，直接放进请求体的 "tools" 字段 */
    public JSONArray getAllToolsAsJson() {
        JSONArray arr = new JSONArray();
        for (BotTool t : tools.values()) {
            arr.add(new JSONObject()
                    .fluentPut("type", "function")
                    .fluentPut("function", new JSONObject()
                            .fluentPut("name", t.name())
                            .fluentPut("description", t.description())
                            .fluentPut("parameters", t.getParameters())));
        }
        return arr;
    }
}
