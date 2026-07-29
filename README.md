

# JBot

## 项目简介

JBot 是一个基于 **Spring Boot** 与 **OneBot** 协议构建的高性能 QQ 机器人框架。它集成了 AI 智能对话、长期记忆系统、本地表情包管理、动漫图片搜索以及完善的 **Web 管理后台**。

## 主要功能

- **🤖 AI 对话**: 支持配置主流大模型 API，具备多轮对话上下文管理和长期记忆功能（可记忆用户和群聊特征）。
- **🎨 表情包系统**: 支持本地图片分类管理、随机/条件发送、图片压缩、Web 端上传与管理。
- **🖼️ 动漫图片搜索**: 集成第三方 API，支持通过关键词搜索并发送动漫图片。
- **📚 词汇卡片**: 便捷的词汇/短语管理工具，支持保存和推送到群聊或私聊进行学习。
- **🔧 强大的命令系统**: 支持自定义命令前缀和别名，涵盖闲聊、图片、系统管理等多种指令。
- **🌐 Web 管理后台**: 提供登录认证、系统监控、配置热更新、内存状态查看等一站式管理功能。
- **⚙️ 自动维护**: 内置定时任务，支持临时文件清理、旧会话归档和内存定期持久化。

## 环境要求

- **Java**: JDK 17 或更高版本
- **Maven**: 3.6+
- **OneBot 客户端**: (如 Go-CQHTTP)

## 快速开始

### 1. 编译项目

在项目根目录下执行 Maven 编译：

```bash
mvn clean package -DskipTests
```

### 2. 配置应用

编辑配置文件 `application.yml` (位于 `src/main/resources` 或运行目录)：

```yaml
server:
  port: 8080 # Web 后台端口

bot:
  admin:
    qq-list: # 管理员 QQ 号列表
      - 123456
      - 789012
  command:
    prefix: "/" # 命令前缀，可自定义
  images:
    root-dir: ./data/images # 表情包存储根目录
  ai:
    enabled: true
    api-url: "https://api.openai.com/v1" # AI API 地址
    api-key: "YOUR_API_KEY" # API 密钥
```

### 3. 启动程序

```bash
java -jar jbot.jar
```

### 4. 连接 OneBot

确保你的 OneBot 客户端 (如 Go-CQHTTP) 已配置**正向 WebSocket** 连接到本项目：

```yaml
# OneBot 客户端配置示例
- url: ws://127.0.0.1:8080/ws
```

## 使用指南

### Web 后台
访问 `http://localhost:8080/admin` 即可进入管理界面。首次访问需要设置管理员密码。

### 聊天指令 (示例前缀 `/`)

- **`/ai [问题]`**: 唤醒 AI 进行对话。
- **`/看 [关键词]`**: 从本地表情包库中随机获取图片发送。
- **`/来 [标签]`**: 通过 API 搜索并发送动漫图片。
- **`/poke`**: 戳一戳机器人，会随机回复表情包。
- **`/重置`**: 重置当前对话上下文。
- **`/词汇`**: 查看或管理词汇卡片。

## 项目结构

```
com.bot.jone/
├── ai/                # AI 核心逻辑与记忆管理
├── config/            # 配置加载、热更新与 Web 配置
├── controller/        # REST API 与前端页面控制器
├── handler/           # WebSocket 事件、消息与命令处理
├── ioutils/           # 图片压缩、下载与临时文件管理
├── monitor/           # JVM 与系统资源监控
└── service/           # 业务逻辑服务 (图库、词汇等)
```

## 许可证

本项目遵循开源协议（具体信息请查看项目 LICENSE 文件）。