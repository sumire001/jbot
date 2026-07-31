# J-One

## Project Introduction

J-One is a QQ bot built upon **Spring Boot** and the **OneBot** protocol. It integrates AI persona chat, a long-term memory system, local sticker management, anime image search, vocabulary card management, and a Web admin panel.

## Main Features

- **AI Persona Chat**: Once a model API is configured, the bot joins group/private chats as a "tsundere brat" persona, with multi-turn context and long-term memory (records user and group traits).
- **Sticker System**: Local images organized by category, with random/targeted sending, image compression, and Web-based upload & management.
- **Anime Image Search**: Integrates the Lolicon API to search and send anime images by keyword.
- **Vocabulary Cards**: Save vocabulary/phrase data via API and push it to group or private chats.
- **Command System**: Customizable command prefix and aliases, covering chat, images, memory management, and system settings.
- **Web Admin Panel**: Login authentication, system monitoring, configuration hot-reload, and memory status viewing.
- **Auto Maintenance**: Built-in scheduled tasks for temp file cleanup, old-session archiving, and periodic memory persistence.

## Environment Requirements

- **Java**: JDK 17 or higher
- **Maven**: 3.6+
- **OneBot Client**: e.g., Go-CQHTTP / LLOneBot (connected via WebSocket)

## Quick Start

### 1. Compile Project

Run Maven build in the project root:

```bash
mvn clean package -DskipTests
```

### 2. Configure Application

Runtime configuration lives in `config/application.yml` under the working directory (external config, not tracked by the repository). The project ships a resource default as a template; put real values (especially secrets and admin QQ) in `config/application.yml`:

```yaml
server:
  port: 3939   # Web & WebSocket port

bot:
  admin:
    qq-list:   # Administrator QQ list (in external config/application.yml, do not commit)
      - your_admin_qq
  command:
    prefix: "/"   # Command prefix, customizable
  ai:
    enabled: true
    api-url: "https://api.deepseek.com"   # API base URL (without /chat/completions; appended automatically)
    api-key: "your_api_key"                # or inject via environment variable
    model: "deepseek-chat"
    max-tokens: 1024
    temperature: 0.7
  images:
    root-dir: ./data/images   # Image root directory
```

### 3. Start Program

```bash
java -jar target/J-One-0.0.1-SNAPSHOT.jar
```

### 4. Connect OneBot

Ensure your OneBot client is configured with a **Forward WebSocket** connection to this project:

```yaml
# OneBot client config example
- url: ws://127.0.0.1:3939/ws
```

## User Guide

### Web Admin Panel

Access `http://localhost:3939/admin` to enter the management interface (login required). It provides command config, AI config, gallery management, and more.

### Chat Commands (Default Prefix `/`, aliases editable in WebUI)

- **`/测试`** (`test`): Test bot response.
- **`/戳一戳`** (`poke`): Nudge the bot; it replies with a random sticker.
- **`/偷一下`** (`steal`, `上传`): Save an image from chat into the local gallery (admin required).
- **`/随机图`** (`random`) `[keyword]`: Search and send anime images via the Lolicon API.
- **`/enable`** (`ai开关`): Toggle AI chat; viewing status is public, modifying requires admin.
- **`/reset`** (`重置`): Reset the current session's short-term context.
- **`/history`** (`历史`): View recent conversation history.
- **`/summary`** (`摘要`): Force a conversation summary (admin required).
- **`/profile`** (`画像`): View user/group profile (admin required).
- **`/clear-memory`** (`清空记忆`): Clear long-term memory (admin required).
- **`/categories`** (`分类列表`): List image categories.
- **`/peek`**: View the last browsed gallery.
- **`/look`** (`看`): View a single image by index.

> Vocabulary cards are saved and pushed via the REST endpoint `/api/vocabulary`, not as a chat command.

## Project Structure

```
com.bot.jone/
├── ai/            # AI chat, memory management, summarization
├── auth/          # Web admin login authentication
├── client/        # OneBot API client
├── config/        # Config loading & hot-reload
├── controller/    # REST API & page routing
├── handler/       # WebSocket events & command handling
├── ioutils/       # Image compression, download, temp file cleanup
├── model/         # Data models
├── monitor/       # JVM & memory monitoring
├── permission/    # Permission management
├── repository/    # File persistence
└── service/       # Business logic (gallery, vocabulary, anime images, etc.)
```

## License

No explicit license yet. Contact the author before use.
