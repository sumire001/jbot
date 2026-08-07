# J-One

## 项目简介

J-One 是一个基于 **Spring Boot** 与 **OneBot** 协议构建的 QQ 机器人。它集成了 AI 角色对话、长期记忆系统、本地表情包管理、二次元图片搜索、词汇卡片管理以及 Web 管理后台。

## 主要功能

- **AI 角色对话**：配置大模型 API 后，机器人以"毒舌雌小鬼"角色人设参与群聊/私聊，支持多轮上下文与长期记忆（记录用户与群聊特征）。
- **表情包系统**：本地图片按分类管理，支持随机/指定发送、图片压缩，并提供 Web 端上传与管理。
- **二次元图片搜索**：集成 Lolicon API，支持按关键词搜索并发送动漫图片。
- **词汇卡片**：支持通过接口保存词汇/短语数据并推送到群聊或私聊。
- **命令系统**：支持自定义命令前缀与别名，涵盖闲聊、图片、记忆管理、系统设置等指令。
- **AI 工具调用（Function Calling）**：支持联网搜索、查时间等工具，AI 在回答需要实时/准确事实时主动调用，减少凭记忆编造。
- **远程 Git 仓库查询**：内置 `git_query` 工具，AI 可在对话中主动查看远程仓库的分支、标签、PR/MR、提交记录、文件内容与 diff（基于 JGit 直连 git 协议，默认不依赖任何平台 API）。
- **Web 管理后台**：提供登录认证、系统监控、配置热更新、内存状态查看等管理功能。
- **自动维护**：内置定时任务，支持临时文件清理、旧会话归档与长期记忆定期持久化。

## 环境要求

- **Java**: JDK 17 或更高版本
- **Maven**: 3.6+
- **OneBot 客户端**: 如 Go-CQHTTP / LLOneBot（通过 WebSocket 连接）

## 快速开始

### 1. 编译项目

在项目根目录下执行 Maven 编译：

```bash
mvn clean package -DskipTests
```

> **提示**：首次构建或遇到依赖解析失败（如 `Could not resolve ... jgit`）时，加 `-U` 强制刷新依赖：
> ```bash
> mvn -U clean package -DskipTests
> ```
> 重新打包前请先停止正在运行的 J-One——Windows 下旧的 `target/*.jar` 会被进程锁住，导致 `clean` 阶段删除失败。

### 2. 配置文件

运行时配置位于工作目录下的 `config/application.yml`（外部配置，不纳入版本库）。项目内置一份资源默认配置作为模板，真实配置（尤其是密钥与管理员 QQ）请写在 `config/application.yml` 中：

```yaml
server:
  port: 3939   # Web 与 WebSocket 端口

bot:
  admin:
    qq-list:   # 管理员 QQ 号列表（写在外部 config/application.yml，勿提交进仓库）
      - 你的管理员QQ
  command:
    prefix: "/"   # 命令前缀，可自定义
  ai:
    enabled: true
    api-url: "https://api.deepseek.com"   # API 基地址（不含 /chat/completions，代码会自动拼接）
    api-key: "你的API密钥"                  # 也可通过环境变量注入
    model: "deepseek-chat"
    max-tokens: 1024
    temperature: 0.7
    tools-enabled: true     # 是否启用工具调用（git_query / 联网搜索 / 查时间等）
    tool-max-rounds: 5      # 工具循环最大轮数，防止模型反复调用陷入死循环
    search-engine: "bing"   # 网页搜索后端：bing（默认，国内免 key）/ duckduckgo / baidu
  git:
    default-repo: "https://github.com/owner/repo.git"  # 默认仓库；git_query 缺省 repo 时使用
    # token: "你的PAT"      # 私有仓库填个人访问令牌；或设环境变量 GIT_TOKEN（推荐，勿提交进仓库）
    clone-depth: 20         # 浅克隆深度：log 能回溯的历史提交数
    cache-dir: "data/git-cache"   # 克隆缓存目录（删此目录可强制重新克隆）
    timeout-seconds: 30     # 网络超时（秒）
    issues-enabled: false   # issues 是平台概念，默认关闭（开启且配 token 后才调平台 API）
  images:
    root-dir: ./data/images   # 图片根目录
```

### 3. 启动程序

```bash
java -jar target/J-One-0.0.1-SNAPSHOT.jar
```

### 4. 连接 OneBot

确保你的 OneBot 客户端已配置**正向 WebSocket** 连接到本项目：

```yaml
# OneBot 客户端配置示例
- url: ws://127.0.0.1:3939/ws
```

## 使用指南

### Web 后台

访问 `http://localhost:3939/admin` 进入管理界面（需登录）。后台提供命令配置、AI 配置、图库管理等面板。

### 聊天指令（默认前缀 `/`，别名可在 WebUI 修改）

- **`/测试`** (`test`)：测试机器人响应。
- **`/戳一戳`** (`poke`)：戳一戳机器人，随机回复表情包。
- **`/偷一下`** (`steal`, `上传`)：将聊天中的图片保存到本地图库（需管理员）。
- **`/随机图`** (`random`) `[关键词]`：通过 Lolicon API 搜索并发送二次元图片。
- **`/enable`** (`ai开关`)：开关 AI 对话；无参数查看状态（公开），带参数修改需管理员。
- **`/reset`** (`重置`)：重置当前会话的短期对话上下文。
- **`/history`** (`历史`)：查看近期对话历史。
- **`/summary`** (`摘要`)：强制触发对话摘要（需管理员）。
- **`/profile`** (`画像`)：查看用户/群聊画像（需管理员）。
- **`/clear-memory`** (`清空记忆`)：清空长期记忆（画像，需管理员）。无参同时清空当前会话；`all` 清空全部用户与群画像及所有会话；后接 QQ 号只删该用户画像。
- **`/categories`** (`分类列表`)：列出图片分类。
- **`/peek`**：查看上次浏览的图库（画廊）。
- **`/look`** (`看`)：按编号查看单张图片。

### Git 仓库查询（AI 工具）

项目内置 `git_query` 工具（基于 JGit 直连 git 协议，默认不依赖任何平台 API）。当对话涉及某个仓库的状态、历史、文件或变更时，AI 会主动调用该工具查证，而非凭记忆编造。

- 支持的动作：`branches`（分支）、`tags`（标签）、`pr_list`（PR/MR）、`log`（提交记录）、`read_file`（读文件）、`diff`（变更对比）、`issues`（需开启）。
- 用法：直接在聊天里问 bot，例如"我们仓库最近提交了啥""读一下 README.md""现在有哪些分支和 PR"。
- 该工具**由 AI 在对话中自动调用**，没有独立的斜杠命令；仓库地址取自 `bot.git.default-repo` 或你给的 URL。
- 私有仓库需在 `bot.git.token` 或环境变量 `GIT_TOKEN` 中配置个人访问令牌。

> 词汇卡片通过 REST 接口 `/api/vocabulary` 保存与推送，并非聊天命令。

## 项目结构

```
com.bot.jone/
├── ai/            # AI 对话、记忆管理、摘要、工具调用(tool/)
├── git/           # 远程 Git 仓库查询（JGit 直连协议）
├── auth/          # Web 后台登录认证
├── client/        # OneBot API 客户端
├── config/        # 配置加载、热更新
├── controller/    # REST API 与页面路由
├── handler/       # WebSocket 事件与命令处理
├── ioutils/       # 图片压缩、下载、临时文件清理
├── model/         # 数据模型
├── monitor/       # JVM 与内存监控
├── permission/    # 权限管理
├── repository/    # 文件持久化
└── service/       # 业务逻辑（图库、词汇、动漫图片等）
```

## 许可证

本项目基于 **MIT 许可证** 开源。

- 英文条款：[LICENSE](LICENSE)
- 中文条款：[LICENSE.zh-CN](LICENSE.zh-CN)
- 第三方组件许可证与版权声明：[THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES)

版权所有 (c) 2026 sumire。
