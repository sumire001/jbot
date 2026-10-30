package com.bot.jone.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bot.ai")
public class AiConfig {
    private boolean enabled = false;
    private String apiKey;
    private String apiUrl;
    private String model;
    /** OpenAI 兼容接口的聊天补全路径，拼接在 api-url 之后，默认 /chat/completions */
    private String chatPath = "/chat/completions";
    /** 鉴权请求头名称，默认 Authorization（如 Anthropic 类平台可改 x-api-key） */
    private String authHeader = "Authorization";
    /** 鉴权方案，默认 Bearer */
    private String authScheme = "Bearer";
    private int maxTokens = 500;
    /**
     * 摘要(长期记忆)调用的 max_tokens 上限。
     * 与普通对话不同：摘要可能跑在推理模型(deepseek-v4-flash 等带 reasoning_content)上，
     * 模型会把大量 token 花在内部思考上，若上限过小（如 512）会被 finish_reason=length 截断、
     * 导致 content 为空、长期记忆写不进去。故默认给到 2048，并允许配置覆盖。
     */
    private int summaryMaxTokens = 2048;
    /**
     * 是否开启思考模式(thinking)。deepseek-v4-flash 等模型默认开启思考，
     * 开启时会忽略 temperature/top_p，并把 token 预算花在内部推理(reasoning_content)上，
     * 既更贵，又会让纯 JSON 抽取类任务(长期记忆摘要)把 max_tokens 吃光导致 content 为空被截断。
     * 关闭后：温度参数恢复生效、成本更低、摘要不再被推理占用预算。默认关闭。
     */
    private boolean thinkingEnabled = false;
    private double temperature = 0.7;
    private int timeoutSeconds = 60;
    private String systemPrompt;
    private int historyMaxContext = 10;

    // ===== 短期记忆（对话历史）持久化配置 =====
    /**
     * 是否把短期记忆落盘到文件，避免进程重启后对话历史全部丢失。默认开启。
     * 落盘为"临时缓存"性质：仅用于重启后恢复最近若干轮对话，不替代长期记忆。
     */
    private boolean conversationPersistEnabled = true;
    /**
     * 每满多少"轮"对话刷一次盘（1 轮 = 用户提问 + bot 回复，共 2 条消息）。
     * 默认 3 轮：平衡 IO 频率与崩溃丢失量（崩溃最多丢约 3 轮）。
     * 另：Spring 优雅停机时会强制全量落盘，正常重启几乎不丢。
     */
    private int conversationSaveEveryRounds = 3;
    /**
     * 会话空闲多久后判定过期并删除（小时）。默认 72 小时。
     * 判定依据：会话最后一条消息的时间距当前超过该值即清理，避免文件无限膨胀。
     */
    private int conversationMaxIdleHours = 72;
    /**
     * 短期记忆落盘文件路径（相对工作目录）。默认 data/conversation_history.json。
     */
    private String conversationStoragePath = "data/conversation_history.json";

    // ===== 工具调用（Function Calling）配置 =====
    /**
     * 是否启用工具调用。开启后，AI 聊天可在回答需要实时/准确事实时主动调用工具
     * （如 get_current_time 查时间、web_search 联网搜索），减少凭记忆编造、提升回答准确性。
     */
    private boolean toolsEnabled = true;
    /** 工具循环最大轮数，防止模型反复调用工具陷入死循环 */
    private int toolMaxRounds = 5;
    /** 网页搜索后端：bing（全球版，配合代理出国际结果）/ duckduckgo（国际，需代理，偶发限流）/ baidu（国内，反爬严）。可经 WebUI 热切换（无需重启） */
    private String searchEngine = "bing";
    /** 网页搜索返回结果条数上限 */
    private int searchMaxResults = 5;

    public int getTimeoutMillis() {
        return timeoutSeconds * 1000;
    }
}
