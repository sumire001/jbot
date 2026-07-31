package com.bot.jone.ai.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 查时间工具：返回当前日期、时间、星期与 Unix 时间戳。
 * 当用户问“现在几点”“今天几号”“星期几”“当前日期/时间戳”时使用。
 * 支持可选 timezone 参数，默认北京时间。
 */
@Component
public class GetTimeTool implements BotTool {

    private static final String DEFAULT_ZONE = "Asia/Shanghai";
    private static final String[] CN_WEEK = {"", "一", "二", "三", "四", "五", "六", "日"};

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前的日期与时间。当用户询问现在几点、今天是几号、星期几、当前日期、或需要 Unix 时间戳时使用。"
                + "可指定时区（默认 Asia/Shanghai，即北京时间）。";
    }

    @Override
    public JSONObject getParameters() {
        JSONObject params = new JSONObject();
        params.put("type", "object");

        JSONObject props = new JSONObject();
        props.put("timezone", new JSONObject()
                .fluentPut("type", "string")
                .fluentPut("description", "时区 ID，如 'Asia/Shanghai'（北京）、'America/New_York'、'UTC'。缺省用北京时间。"));

        params.put("properties", props);
        params.put("required", new JSONArray());
        return params;
    }

    @Override
    public String execute(JSONObject args) {
        String tzStr = args == null ? null : args.getString("timezone");
        ZoneId zone;
        try {
            zone = (tzStr == null || tzStr.isEmpty()) ? ZoneId.of(DEFAULT_ZONE) : ZoneId.of(tzStr);
        } catch (Exception e) {
            zone = ZoneId.of(DEFAULT_ZONE);
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int dow = now.getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
        long epochSec = now.toEpochSecond();
        long epochMs = now.toInstant().toEpochMilli();

        return "当前时间（" + zone.getId() + "）：" + now.format(fmt)
                + " 星期" + CN_WEEK[dow]
                + " | Unix 秒：" + epochSec
                + " | Unix 毫秒：" + epochMs;
    }
}
