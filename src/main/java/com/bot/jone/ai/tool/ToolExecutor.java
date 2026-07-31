package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工具执行器：封装“查找工具 -> 解析参数 -> 调用 -> 异常兜底 -> 结果截断”全流程。
 * 任何失败都以安全的字符串形式返回给 LLM（而不是抛异常中断对话），
 * 这样 LLM 能感知到工具失败并自行决定如何回应，形成自愈闭环。
 */
@Slf4j
@Component
public class ToolExecutor {

    /** 工具结果最大长度，防止超长网页内容撑爆后续请求的上下文 */
    private static final int MAX_RESULT_CHARS = 3000;

    @Autowired
    private ToolRegistry registry;

    public String execute(String name, String argsJson) {
        BotTool tool = registry.get(name);
        if (tool == null) {
            return "[工具不存在] 未找到名为 " + name + " 的工具";
        }

        JSONObject args;
        try {
            args = (argsJson == null || argsJson.isEmpty()) ? new JSONObject() : JSONObject.parseObject(argsJson);
        } catch (Exception e) {
            return "[参数解析失败] 工具 " + name + " 的参数不是合法 JSON: " + argsJson;
        }

        try {
            String result = tool.execute(args);
            if (result == null) {
                result = "";
            }
            if (result.length() > MAX_RESULT_CHARS) {
                result = result.substring(0, MAX_RESULT_CHARS)
                        + "\n...[结果过长已截断，仅保留前 " + MAX_RESULT_CHARS + " 字]";
            }
            return result;
        } catch (Exception e) {
            log.error("[工具] 执行异常 name={}", name, e);
            return "[工具执行出错] " + name + " 执行时发生异常: " + e.getMessage();
        }
    }
}
