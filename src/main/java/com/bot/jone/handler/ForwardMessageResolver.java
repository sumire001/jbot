package com.bot.jone.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.client.OneBotApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合并转发消息解析器。
 *
 * <p>OneBot 的合并转发事件里，{@code forward} 段只携带一个 id，真正的多条消息要通过
 * {@code get_forward_msg} API 再去取。取回的每条 node 的消息段数组，标准实现放在 {@code message} 字段，
 * 而 LLOneBot 放在 {@code content} 字段（本项目两者兼容）；段内可能再嵌套 {@code forward} 段
 * （把"一个转发"又整体转发出去），因此必须递归展平。</p>
 *
 * <p>安全护栏：每次展平一层 forward = 一次 {@code get_forward_msg} API 调用。恶意/异常构造的
 * 深层或超长嵌套转发会放大调用量，故设两道闸：
 * <ul>
 *   <li>{@link #MAX_DEPTH}：递归深度上限，超过则折叠为占位，防无限下钻；</li>
 *   <li>{@link #MAX_NODES}：累计展开的 node 总数上限，超过则截断，防单次消息触发海量 API 调用。</li>
 * </ul>
 *
 * <p>兼容性：经实测，LLOneBot 的 get_forward_msg 返回的每个 node 用 {@code content} 字段承载消息段数组
 * （标准 OneBot 用 {@code message}），{@code sender} 为对象（含 nickname/user_id），{@code time} 为独立
 * 整数。解析器兼容两种字段名、递归下钻嵌套 forward，并对"正文仍为空"的节点打 WARN 并 dump 原始结构，
 * 便于后续适配其它实现差异。</p>
 */
@Slf4j
@Component
public class ForwardMessageResolver {

    /** 递归深度上限（正常转发嵌套不会超过 3 层） */
    private static final int MAX_DEPTH = 5;
    /** 单次解析累计展开的 node 总数上限 */
    private static final int MAX_NODES = 60;
    /** CQ 码段匹配：如 [CQ:text,text=hello] 或 [CQ:forward,id=xxx] */
    private static final Pattern CQ_PATTERN = Pattern.compile("\\[CQ:([a-zA-Z0-9_-]+)(?:,([^\\]]*))?\\]");

    @Autowired
    private OneBotApiClient apiClient;

    /**
     * 解析单个合并转发 id，返回可读文本（含任意层级的嵌套转发）。
     * 失败/超时/超限时返回降级占位串，绝不抛异常给上层。
     *
     * @param id       合并转发 id（字符串，原样传递）
     * @param session  用于调用 get_forward_msg 的 WebSocket 会话
     * @return 形如 "\n【发送者A】文本内容\n【发送者B】…" 的可读文本
     */
    public String resolveForward(String id, Session session) {
        StringBuilder sb = new StringBuilder();
        int[] counter = {0}; // 用数组以便在递归中共享累计计数
        flattenForward(id, session, 0, counter, sb);
        return sb.toString();
    }

    /**
     * 展平一组 segment（顶层调用，含嵌套转发/text/image/at/face 等）。
     * 主要用于"被引用消息"场景：把某条消息的正文、以及正文中再嵌套的合并转发
     * 一并转成可读文本。内部复用 {@link #flattenSegments}，受同样的深度/节点数护栏约束。
     *
     * @param segments segment 数组（来自事件 message 或 get_msg 回包的 data.message）
     * @param session  用于递归解析嵌套 forward 的 WebSocket 会话
     * @return 可读文本
     */
    public String resolveSegments(JSONArray segments, Session session) {
        StringBuilder sb = new StringBuilder();
        int[] counter = {0};
        flattenSegments(segments, session, 0, counter, sb);
        return sb.toString();
    }

    /** 递归：按 forward id 拉取内容并展平其下所有 node */
    private void flattenForward(String id, Session session, int depth, int[] counter, StringBuilder sb) {
        if (id == null || id.isEmpty()) {
            sb.append("[合并转发无id]");
            return;
        }
        if (depth >= MAX_DEPTH) {
            sb.append("[嵌套转发已折叠(超过深度限制)]");
            return;
        }
        if (counter[0] >= MAX_NODES) {
            sb.append("[转发内容过长,已截断]");
            return;
        }

        JSONObject resp = apiClient.getForwardMsg(session, id);
        if (resp == null) {
            sb.append("[合并转发获取失败]");
            return;
        }
        JSONObject data = resp.getJSONObject("data");
        if (data == null) {
            log.warn("[forward] 回包无 data 字段: id={}, status={}, retcode={}",
                    id, resp.getString("status"), resp.getInteger("retcode"));
            sb.append("[合并转发内容为空]");
            return;
        }
        JSONArray nodes = data.getJSONArray("messages");
        if (nodes == null || nodes.isEmpty()) {
            sb.append("[合并转发内容为空]");
            return;
        }

        for (int i = 0; i < nodes.size(); i++) {
            if (counter[0] >= MAX_NODES) {
                sb.append("[转发内容过长,已截断]");
                return;
            }
            counter[0]++;
            JSONObject node = nodes.getJSONObject(i);
            if (node == null) continue;

            // 发送者昵称：群名片优先，回退全局昵称
            JSONObject sender = node.getJSONObject("sender");
            String name = "未知";
            if (sender != null) {
                String card = sender.getString("card");
                String nick = sender.getString("nickname");
                name = (card != null && !card.isEmpty()) ? card : (nick != null ? nick : "未知");
            }
            // 发送时间（Unix 秒），格式化便于阅读
            String ts = formatNodeTime(node.getLong("time"));
            // 按递归深度缩进，使嵌套转发层级一目了然（避免"某人只转发、其名下内容其实是转发的子消息"被误读为空）
            sb.append("\n").append(indent(depth)).append("【").append(name).append(ts).append("】");

            // node 的消息段数组：LLOneBot 用 "content" 字段，标准 OneBot 用 "message"，两者都兼容；
            // content 内部是一组 segment，遇到 forward 段会递归下钻（见 flattenSegments）。
            Object msg = node.get("content");
            if (msg == null) msg = node.get("message");
            StringBuilder nodeSb = new StringBuilder();
            if (msg instanceof JSONArray) {
                flattenSegments((JSONArray) msg, session, depth + 1, counter, nodeSb);
            } else if (msg instanceof String) {
                // LLOneBot 偶尔把 node 正文以 CQ 码字符串形式返回
                appendCqString((String) msg, session, depth + 1, counter, nodeSb);
            }

            // 节点正文为空：记录原始 node 结构，便于排查（如客户端未序列化正文 / 内容为图片被剥离）
            if (nodeSb.length() == 0) {
                String raw = node.toString();
                if (raw.length() > 800) raw = raw.substring(0, 800) + "...(truncated)";
                log.warn("[forward] 节点正文为空, sender={}, depth={}, raw={}", name, depth, raw);
            }
            sb.append(nodeSb);
        }
    }

    /** 递归：展平一组 segment（遇到 forward 段再下钻一层） */
    private void flattenSegments(JSONArray segments, Session session, int depth, int[] counter, StringBuilder sb) {
        if (segments == null) return;
        for (int i = 0; i < segments.size(); i++) {
            JSONObject seg = segments.getJSONObject(i);
            if (seg == null) continue;
            String type = seg.getString("type");
            JSONObject data = seg.getJSONObject("data");
            if (data == null) data = new JSONObject();

            switch (type) {
                case "forward":
                    // 嵌套合并转发：递归下钻（depth 已在该方法入口检查上限）
                    flattenForward(data.getString("id"), session, depth, counter, sb);
                    break;
                case "text":
                    // 优先 data.text，回退段顶层 text（兼容部分实现）
                    String text = data.getString("text");
                    if (text == null) text = seg.getString("text");
                    if (text != null) sb.append(text);
                    break;
                case "image":
                    sb.append("[图片]");
                    break;
                case "at":
                    String qq = data.getString("qq");
                    sb.append(qq != null ? "[@" + qq + "]" : "[有人@我]");
                    break;
                case "face":
                case "bface":
                case "sface":
                case "emoji":
                    sb.append("[表情]");
                    break;
                case "reply":
                    // 转发内节点引用了另一条消息（如图中的 A2 引用 A1）：
                    // 标记引用，并对"指向全局真实消息"的引用尝试取回内容展平；
                    // 指向转发内部节点（内部 id）的引用 get_msg 多半取不到，则降级为纯标记。
                    // 受 depth / MAX_NODES 护栏，避免循环引用或引用链过长爆 API。
                    Long rid = data.getLong("id");
                    if (rid != null && rid > 0 && rid <= Integer.MAX_VALUE
                            && depth < MAX_DEPTH && counter[0] < MAX_NODES) {
                        counter[0]++;
                        JSONObject rmsg = apiClient.getMsg(session, rid.intValue());
                        if (rmsg != null) {
                            JSONObject rdata = rmsg.getJSONObject("data");
                            JSONArray rarr = rdata != null ? rdata.getJSONArray("message") : null;
                            if (rarr != null && !rarr.isEmpty()) {
                                sb.append("\n").append(indent(depth)).append("[↩ 引用]");
                                flattenSegments(rarr, session, depth + 1, counter, sb);
                                break;
                            }
                        }
                    }
                    sb.append("[↩ 引用了其他消息]");
                    break;
                default:
                    // 未知类型：尽量提取其文本，避免漏掉正文；实在没有再给类型占位
                    String dtext = data.getString("text");
                    if (dtext == null) dtext = seg.getString("text");
                    if (dtext != null && !dtext.isEmpty()) {
                        sb.append(dtext);
                    } else {
                        sb.append("[").append(type).append("]");
                    }
                    break;
            }
        }
    }

    /**
     * 解析 CQ 码字符串形式的消息（如 node.message 为 "[CQ:text,text=hi][CQ:forward,id=xxx]"）。
     * 仅提取对总结有意义的 text / forward / image / face / at；其它类型忽略，避免噪声。
     */
    private void appendCqString(String cq, Session session, int depth, int[] counter, StringBuilder sb) {
        if (cq == null || cq.isEmpty()) return;
        Matcher m = CQ_PATTERN.matcher(cq);
        while (m.find()) {
            String type = m.group(1);
            String params = m.group(2) != null ? m.group(2) : "";
            switch (type) {
                case "text":
                    sb.append(unescapeCq(cqParam(params, "text")));
                    break;
                case "forward":
                    flattenForward(cqParam(params, "id"), session, depth, counter, sb);
                    break;
                case "image":
                    sb.append("[图片]");
                    break;
                case "face":
                case "bface":
                case "sface":
                case "emoji":
                    sb.append("[表情]");
                    break;
                case "at":
                    sb.append("[@").append(cqParam(params, "qq")).append("]");
                    break;
                default:
                    // 未知 CQ 类型忽略
                    break;
            }
        }
    }

    /** 从 CQ 参数字符串中取某 key 的值（如 "text=hi,id=1" 中取 text） */
    private static String cqParam(String params, String key) {
        if (params == null || params.isEmpty()) return null;
        for (String kv : params.split(",")) {
            int idx = kv.indexOf('=');
            if (idx <= 0) continue;
            if (kv.substring(0, idx).equals(key)) {
                return kv.substring(idx + 1);
            }
        }
        return null;
    }

    /** CQ 码反转义（覆盖常见转义：逗号/方括号/换行/&） */
    private static String unescapeCq(String s) {
        if (s == null) return "";
        return s.replace("&#44;", ",")
                .replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#10;", "\n")
                .replace("&amp;", "&");
    }

    /** 将 node 的 Unix 秒时间戳格式化为 " · 08-10 17:25"（无效则返回空串）。固定东八区，避免服务器时区偏差。 */
    private static String formatNodeTime(Long epochSec) {
        if (epochSec == null) return "";
        try {
            java.time.LocalDateTime dt = java.time.LocalDateTime.ofEpochSecond(
                    epochSec, 0, java.time.ZoneOffset.ofHours(8));
            return " · " + dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        } catch (Exception e) {
            return "";
        }
    }

    /** 递归深度缩进：depth 级 = depth*2 个空格，顶层（depth<=0）不缩进。用于让嵌套转发层级清晰。 */
    private static String indent(int depth) {
        if (depth <= 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("  ");
        return b.toString();
    }
}
