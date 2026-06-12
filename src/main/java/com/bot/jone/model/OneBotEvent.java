package com.bot.jone.model;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class OneBotEvent {
    // ========== 通用字段 ==========
    @JSONField(name = "self_id")
    private Long selfId;           // 机器人QQ号
    
    @JSONField(name = "user_id")
    private Long userId;           // 发送者QQ号
    
    private Long time;             // 时间戳
    
    @JSONField(name = "post_type")
    private String postType;       // 上报类型: "message", "notice", "request" 等
    
    // ========== 消息事件字段 (post_type = "message") ==========
    @JSONField(name = "message_id")
    private Integer messageId;     // 消息ID
    
    @JSONField(name = "message_seq")
    private Integer messageSeq;    // 消息序号
    
    @JSONField(name = "message_type")
    private String messageType;    // 消息类型: "group" 或 "private"
    
    @JSONField(name = "message_format")
    private String messageFormat;  // 消息格式: "array" 等
    
    private JSONArray message;     // 消息数组
    
    @JSONField(name = "raw_message")
    private String rawMessage;     // 原始消息文本
    
    @JSONField(name = "raw_pb")
    private String rawPb;          // 原始protobuf（通常为空）
    
    private Integer font;          // 字体
    
    @JSONField(name = "sub_type")
    private String subType;        // 子类型: "normal", "poke" 等
    
    // 群聊相关
    @JSONField(name = "group_id")
    private Long groupId;          // 群号
    
    @JSONField(name = "group_name")
    private String groupName;      // 群名称
    
    // 发送者信息
    private Sender sender;         // 发送者详细信息
    
    // ========== 通知事件字段 (post_type = "notice") ==========
    @JSONField(name = "notice_type")
    private String noticeType;     // 通知类型: "notify", "group_upload", "group_admin" 等
    
    @JSONField(name = "target_id")
    private Long targetId;         // 目标ID（如被戳的用户）
    
    @JSONField(name = "raw_info")
    private JSONArray rawInfo;     // 原始通知信息数组
    
    // ========== 请求事件字段 (post_type = "request") ==========
    @JSONField(name = "request_type")
    private String requestType;    // 请求类型: "friend", "group" 等
    
    private String comment;        // 验证信息
    
    private Long flag;             // 请求标识
    
    // ========== 内部类 ==========
    @Data
    public static class Sender {
        @JSONField(name = "user_id")
        private Long userId;       // 发送者QQ号
        
        private String nickname;   // 昵称
        
        private String card;       // 群名片
        
        private String role;       // 群角色: "owner", "admin", "member"
        
        private String level;      // 群等级
        
        private String title;      // 专属头衔
    }
}