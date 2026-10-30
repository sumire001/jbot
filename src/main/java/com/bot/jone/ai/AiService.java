package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.ai.tool.ToolExecutor;
import com.bot.jone.ai.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class AiService {

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private PromptManager promptManager;

    @Autowired
    private ConversationManager conversationManager;

    @Autowired
    private LongTermMemoryManager longTermMemoryManager;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolExecutor toolExecutor;

    /**
     * 工具使用指引：由 buildMessages 作为**独立 system 消息**插在"历史之后、当前消息之前"。
     *
     * 为什么不拼进最前面的系统提示词：实测（deepseek-v4-flash，"真实人设 + 含'我查不到'旧示范的历史"，
     * 各 5 次采样）放在最前面时 baidu_baike 命中率只有 1/5 —— 会被强势人设和模型自己的历史回答压过去；
     * 放到历史之后、问题之前则是 5/5。指令离当前问题越近越有效，不要挪回 systemPromptBuilder。
     */
    private static final String TOOLS_GUIDE =
            "# 工具使用指引\n"
            + "1) 遇到下面这类问题，必须先用工具查证再回答，不要凭记忆作答：\n"
            + "   · 具体的专有名词、人名、机构、作品名、术语、地名、历史事件 → 用 baidu_baike\n"
            + "   · 时效性信息：最新资讯、当下数值、近期发生的事 → 用 web_search\n"
            + "   · 当前日期与时间 → 用 get_current_time\n"
            + "   拿不准该不该查时，先查一遍再答，多花几秒也好过编造；不要因为一个名字看着生僻、"
            + "像地名拼凑出来的、像虚构词，就断定它不存在——越是这种拿不准的，越必须先查了再开口。\n"
            + "2) 严禁未查先认：只要你没有真正调用过工具，就不要说「我查不到」「没有相关信息」「我搜过了」"
            + "这类话——那等于编造。只有工具真的返回「未收录/无结果」时，才可以如实说没查到，并说明查的是哪个来源。\n"
            + "3) 日常闲聊（打招呼、接梗、玩笑、情绪回应、纯观点交流）不需要事实依据，直接正常聊天，不必调用工具。\n"
            + "4) 拿到工具结果后，用符合你人设的自然语言回答，不要在回复里堆砌原始 JSON 或工具参数。";

    /**
     * 发送对话请求
     * @param userId 用户ID（用于区分不同用户的历史）
     * @param messageType 消息类型：group 或 private
     * @param userMessage 用户消息
     * @param speaker 发言者昵称（群聊时使用）
     * @return AI回复文本，失败返回null
     */
    public String chat(Long userId, String messageType, Long senderId, String userMessage, String speaker) {
        try {
            // 构建消息上下文（系统提示 + 长期记忆 + 历史 + 当前问题）
            JSONArray messages = buildMessages(userId, messageType, speaker, userMessage);

            // 按配置分流：启用工具时走工具循环（可查时间/网页等），否则走普通单轮对话
            String content;
            if (aiConfig.isToolsEnabled()) {
                content = runToolLoop(messages);
            } else {
                content = doChatRequest(buildBody(messages, aiConfig.getMaxTokens(), aiConfig.getTemperature()), userMessage);
            }

            if (content == null) {
                return "AI服务暂时不可用，请稍后再试~";
            }
            if (content.isEmpty()) {
                return "AI没有生成回复~";
            }

            log.info("[AI] 回复: {}", content.length() > 100
                    ? content.substring(0, 100) + "..."
                    : content);

            // 保存到短期记忆（仅存最终用户问题与模型回复，工具循环的中间消息不污染长期历史）
            conversationManager.addMessage(userId, messageType, "user", userMessage, speaker);
            conversationManager.addMessage(userId, messageType, "assistant", content, "ichikaBot");

            // 检查是否需要触发长期记忆摘要
            JSONArray fullHistory = conversationManager.getHistory(userId, messageType);
            longTermMemoryManager.triggerSummary(userId, messageType, senderId, speaker, fullHistory);

            return content;

        } catch (Exception e) {
            log.error("[AI] 调用失败", e);
            return "AI服务暂时不可用，请稍后再试~";
        }
    }

    // ===== 工具调用相关私有方法 =====

    /**
     * 构建聊天的 messages 列表：系统提示词 + 长期记忆上下文 + 历史对话 + 当前用户问题。
     * 工具路径与非工具路径共用，保证两者看到一致的上下文。
     */
    private JSONArray buildMessages(Long userId, String messageType, String speaker, String userMessage) {
        JSONArray messages = new JSONArray();

        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append(promptManager.getSystemPrompt());

        // 注入当前时间（北京时间，年月日时分秒）：让模型具备真实时间感，
        // 解决时效性问题（如“X 发布了没”）误采信旧预览/旧闻而答错的 bug
        systemPromptBuilder.append("\n\n# 当前时间（北京时间）：").append(getCurrentTimeString());

        if ("group".equals(messageType)) {
            String groupContext = longTermMemoryManager.getGroupContext(userId);
            if (!groupContext.isEmpty()) {
                systemPromptBuilder.append("\n\n").append(groupContext);
            }
        } else {
            String userContext = longTermMemoryManager.getUserContext(userId);
            if (!userContext.isEmpty()) {
                systemPromptBuilder.append("\n\n").append(userContext);
            }
        }

        if (speaker != null && !speaker.isEmpty()) {
            StringBuilder speakerInfo = new StringBuilder();
            speakerInfo.append("\n\n# 对话场景说明");
            if ("group".equals(messageType)) {
                speakerInfo.append("\n当前是群聊，群里有多个成员。历史消息中每条用户发言都以 [昵称]: 开头标明说话者，")
                        .append("那些带不同昵称前缀的消息是别的成员说的，不要把别人之前做的事/说的话，")
                        .append("误当成当前说话者 ").append(speaker).append(" 的行为。");
            } else {
                speakerInfo.append("\n当前是私聊。");
            }
            speakerInfo.append("\n# 当前这条消息由 ").append(speaker).append(" 发出");
            speakerInfo.append("\n（这是你本来就认识的人，正常聊天即可；不要特意提起或复述对方的QQ号、ID、群号等标识，也不要像刚加上好友一样表演「记下你的资料」）");
            systemPromptBuilder.append(speakerInfo);
        }

        // 工具使用指引：仅在启用工具时注入，提示模型优先查证而非编造
        // （指引文本见 TOOLS_GUIDE，由下方在历史之后单独注入，勿在此拼进系统提示词）

        messages.add(new JSONObject()
                .fluentPut("role", "system")
                .fluentPut("content", systemPromptBuilder.toString()));

        JSONArray history = conversationManager.getHistory(userId, messageType);
        if (!history.isEmpty()) {
            messages.addAll(history);
        }

        // 当前用户消息必须标明说话者，与历史消息格式（[昵称]: ...）保持一致，
        // 避免 bot 把历史中其他成员的行为误归因到当前说话者（群聊身份混淆 bug）
        String currentUserContent = userMessage;
        if (speaker != null && !speaker.isEmpty()) {
            currentUserContent = "[" + speaker + "]: " + userMessage;
        }
        // 工具使用指引：作为独立 system 消息插在"历史之后、当前消息之前"。
        // 位置很关键——放在最前面的系统提示词里会被强势人设与历史中模型自己的旧回答压过去（实测命中率 1/5），
        // 挪到紧贴当前问题处后为 5/5。详见 TOOLS_GUIDE 的注释。
        if (aiConfig.isToolsEnabled()) {
            messages.add(new JSONObject()
                    .fluentPut("role", "system")
                    .fluentPut("content", TOOLS_GUIDE));
        }

        messages.add(new JSONObject()
                .fluentPut("role", "user")
                .fluentPut("content", currentUserContent));
        return messages;
    }

    /**
     * 获取当前北京时间，格式 "yyyy-MM-dd HH:mm:ss"（年-月-日 时:分:秒）。
     * 用于注入系统提示词，使模型在回答时效性问题时以真实当前时间为基准，
     * 避免凭记忆或采信过时的预览/旧闻作答。
     */
    private String getCurrentTimeString() {
        return ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 构建普通（非工具）对话请求体。
     */
    private JSONObject buildBody(JSONArray messages, int maxTokens, double temperature) {
        JSONObject body = new JSONObject();
        body.put("model", aiConfig.getModel());
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        applyThinking(body, aiConfig.isThinkingEnabled());
        return body;
    }

    /**
     * 工具调用循环（Function Calling）。
     * 流程：把工具清单交给模型 -> 若模型返回 tool_calls，则执行每个工具并把结果回填为 tool 消息
     * -> 再次询问模型 -> 直到模型不再调用工具或达到最大轮数。
     * 中间消息仅停留在本次调用的 messages 列表内，不会写入长期对话历史。
     *
     * 注意：工具路径强制关闭思考模式，因为 DeepSeek 等推理模型在 tool call 后要求原样回填
     * reasoning_content，否则会 400 报错；关闭思考可彻底规避该坑，且更快更省。
     *
     * @return 模型最终生成的文本回复；HTTP 失败返回 null
     */
    private String runToolLoop(JSONArray messages) {
        JSONArray toolsJson = toolRegistry.getAllToolsAsJson();
        int maxRounds = aiConfig.getToolMaxRounds();
        String lastContent = null;

        for (int round = 0; round < maxRounds; round++) {
            JSONObject body = new JSONObject();
            body.put("model", aiConfig.getModel());
            body.put("messages", messages);
            body.put("tools", toolsJson);
            body.put("tool_choice", "auto");
            body.put("max_tokens", aiConfig.getMaxTokens());
            body.put("temperature", aiConfig.getTemperature());
            applyThinking(body, false); // 工具路径强制关思考

            JSONObject choice = doChatRequestMessage(body, "tool-round-" + round);
            if (choice == null) {
                return null; // HTTP 失败 / 无 choices：终止循环，交由上层返回“服务不可用”
            }
            JSONObject message = choice.getJSONObject("message");
            if (message == null) {
                return null;
            }
            String content = message.getString("content");
            JSONArray toolCalls = message.getJSONArray("tool_calls");

            // 关键：把 assistant 消息（含 tool_calls）原样加回上下文，模型下一轮才能衔接
            messages.add(message);

            if (toolCalls == null || toolCalls.isEmpty()) {
                lastContent = (content == null ? "" : content.trim());
                break;
            }

            log.info("[工具] 第 {} 轮模型请求调用 {} 个工具", round + 1, toolCalls.size());
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String callId = tc.getString("id");
                JSONObject fn = tc.getJSONObject("function");
                String name = fn.getString("name");
                String argsStr = fn.getString("arguments");
                String result = toolExecutor.execute(name, argsStr);
                // 日志增强：记录工具参数与结果预览（超长截断，避免刷屏），
                // 便于排查"调了什么工具 / 传了什么参数 / 返回了什么"
                String argsPreview = (argsStr != null && argsStr.length() > 200)
                        ? argsStr.substring(0, 200) + "..." : argsStr;
                String resPreview = (result != null && result.length() > 200)
                        ? result.substring(0, 200) + "..." : result;
                log.info("[工具] 执行 {} | 参数={} | 结果长度={} | 结果预览={}",
                        name, argsPreview, (result != null ? result.length() : 0), resPreview);
                // 工具结果作为 tool 角色消息回填，与 tool_call_id 对应
                messages.add(new JSONObject()
                        .fluentPut("role", "tool")
                        .fluentPut("tool_call_id", callId)
                        .fluentPut("content", result));
            }
        }
        return lastContent;
    }

    /**
     * 调用AI进行摘要（不保存记忆，纯工具调用）
     */
    public String summarize(String prompt) {
        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", aiConfig.getModel());

            JSONArray messages = new JSONArray();
            messages.add(new JSONObject()
                .fluentPut("role", "user")
                .fluentPut("content", prompt));

            requestBody.put("messages", messages);
            requestBody.put("max_tokens", aiConfig.getSummaryMaxTokens());
            requestBody.put("temperature", 0.3);
            // 摘要是纯 JSON 抽取任务，思考模式纯浪费：既多花钱又把 max_tokens 预算吃光导致 content 为空(我们之前遇到的截断 bug)，
            // 故强制关闭思考模式，无论 thinkingEnabled 配置如何。
            applyThinking(requestBody, false);

            // 统一通过 doChatRequest 发送（自动拼接 chat-path，支持多平台切换）
            // 保留 null（调用失败）与 ""（HTTP 200 但内容为空）的区别，不在此处吞掉空内容，
            // 交由 LongTermMemoryManager 区分“失败”与“空返回”并分别诊断。
            return doChatRequest(requestBody, null);

        } catch (Exception e) {
            log.error("[AI] 摘要调用失败", e);
            return null;
        }
    }

    /**
     * 统一聊天请求：发送并解析，返回完整 choice（含 message + finish_reason），
     * 供工具路径解析 tool_calls 使用。HTTP 非 200 或无 choices 时返回 null（调用失败）。
     */
    private JSONObject doChatRequestMessage(JSONObject requestBody, String userMessagePreview) {
        try {
            String fullUrl = buildUrl(aiConfig.getApiUrl(), aiConfig.getChatPath());
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty(aiConfig.getAuthHeader(),
                    aiConfig.getAuthScheme() + " " + aiConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(aiConfig.getTimeoutMillis());
            conn.setReadTimeout(aiConfig.getTimeoutMillis());

            log.info("[AI] 请求URL: {}", fullUrl);
            log.info("[AI] 请求模型: {}", aiConfig.getModel());
            if (userMessagePreview != null) {
                log.info("[AI] 用户消息: {}", userMessagePreview.length() > 50
                        ? userMessagePreview.substring(0, 50) + "..."
                        : userMessagePreview);
            }
            log.debug("[AI] 请求体: {}", requestBody.toJSONString());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toJSONString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            StringBuilder response = new StringBuilder();
            java.io.InputStream inputStream = responseCode == 200
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            if (inputStream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }
            log.debug("[AI] 响应码: {}, 内容: {}", responseCode, response);

            if (responseCode != 200) {
                log.error("[AI] 请求失败: HTTP {} | 响应: {}", responseCode, response);
                return null;
            }

            JSONObject jsonResponse = JSONObject.parseObject(response.toString());
            JSONArray choices = jsonResponse.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[AI] 响应中没有choices: {}", response);
                return null;
            }
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            String content = message != null ? message.getString("content") : null;
            if (content == null || content.trim().isEmpty()) {
                // 盲区补全：HTTP 200 但 content 为空。常见两类根因需靠 finish_reason 区分：
                //  - "length"        ：命中 max_tokens 上限被截断（推理模型常把额度花在 reasoning_content 上，没留给输出）
                //  - "content_filter"：对话含敏感词被平台安全过滤
                //  - 其余/无 choices ：模型空输出或异常。显式打出 finish_reason 便于定位。
                String finishReason = choice.getString("finish_reason");
                log.warn("[AI] 响应 HTTP 200 但 content 为空，finish_reason={}（length=被max_tokens截断；content_filter=安全过滤），原始响应: {}",
                        finishReason, response);
            }
            return choice;
        } catch (Exception e) {
            log.error("[AI] 调用失败", e);
            return null;
        }
    }

    /**
     * 兼容旧调用（chat / summarize）：仅取 content 文本。
     * 保留 null（调用失败）与 ""（HTTP 200 但内容为空）的语义不变。
     */
    private String doChatRequest(JSONObject requestBody, String userMessagePreview) {
        JSONObject choice = doChatRequestMessage(requestBody, userMessagePreview);
        if (choice == null) {
            return null;
        }
        JSONObject message = choice.getJSONObject("message");
        String content = message != null ? message.getString("content") : null;
        return content == null ? "" : content.trim();
    }

    /**
     * 将 base-url 与 chat-path 拼接为完整请求地址，自动处理斜杠。
     * 若 chat-path 为空，则直接使用 base-url（兼容已填完整地址的旧配置）。
     */
    private String buildUrl(String base, String path) {
        if (path == null || path.isEmpty()) {
            return base;
        }
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    /**
     * 按开关向请求体注入思考模式参数（OpenAI 兼容格式）。
     * deepseek-v4-flash 等模型 thinking 默认 enabled，开启会忽略 temperature 并把 token 预算花在
     * 内部推理(reasoning_content)上；关闭后温度参数恢复生效、成本更低、也不会挤占输出预算。
     *
     * @param requestBody 已构建的请求体
     * @param enabled     true=开启思考，false=关闭思考
     */
    private void applyThinking(JSONObject requestBody, boolean enabled) {
        requestBody.put("thinking",
                new JSONObject().fluentPut("type", enabled ? "enabled" : "disabled"));
    }
}
