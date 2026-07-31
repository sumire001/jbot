package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONObject;

/**
 * 工具定义接口。任何实现了该接口并标注 @Component 的类，
 * 都会被 Spring 在启动时自动收集进 ToolRegistry，无需手工登记清单。
 *
 * 设计目标：让 LLM 在回答需要“实时/准确事实”时（如时间、网页信息）能主动调用，
 * 而不是凭记忆编造，从而提升 bot 说话的准确性。
 */
public interface BotTool {

    /** 工具名，必须全局唯一，会作为 tools[].function.name 发给 LLM */
    String name();

    /** 工具描述，告诉 LLM 何时用、干什么 */
    String description();

    /**
     * JSON Schema 参数定义（OpenAI 格式：{"type":"object","properties":{...},"required":[...]}）。
     * 无参数时返回空的 {@code {"type":"object","properties":{},"required":[]}}。
     */
    JSONObject getParameters();

    /**
     * 执行工具，返回结果字符串（将被塞回 LLM 作为 tool 消息的 content）。
     * 异常由 ToolExecutor 统一兜底，实现内通常无需再 try/catch。
     *
     * @param args 已解析的参数对象（来自 LLM 传入的 arguments JSON 字符串）
     */
    String execute(JSONObject args);
}
