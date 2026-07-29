# JBot

## Project Introduction

JBot is a high-performance QQ Bot framework built upon **Spring Boot** and the **OneBot** protocol. It integrates AI intelligent conversation, a long-term memory system, local sticker management, anime image search, and a comprehensive **Web Admin Panel**.

## Main Features

- **🤖 AI Chat**: Supports configuring mainstream Large Model APIs, featuring multi-turn conversation context management and long-term memory (can record user and group chat traits).
- **🎨 Sticker System**: Supports local image categorization management, random/conditional sending, image compression, and Web-based upload and management.
- **🖼️ Anime Image Search**: Integrates third-party APIs, supports searching and sending anime images via keywords.
- **📚 Vocabulary Cards**: A convenient vocabulary/phrase management tool, supporting saving and pushing to group chats or private chats for learning.
- **🔧 Powerful Command System**: Supports custom command prefixes and aliases, covering chit-chat, images, system administration, and various other instructions.
- **🌐 Web Admin Panel**: Provides login authentication, system monitoring, configuration hot-update, memory status viewing, and one-stop management functions.
- **⚙️ Auto Maintenance**: Built-in scheduled tasks, supporting temporary file cleanup, old session archiving, and regular memory persistence.

## Environment Requirements

- **Java**: JDK 17 or higher
- **Maven**: 3.6+
- **OneBot Client**: (e.g., Go-CQHTTP)

## Quick Start

### 1. Compile Project

Execute Maven compilation in the project root directory:

```bash
mvn clean package -DskipTests
```

### 2. Configure Application

Edit the configuration file `application.yml` (located in `src/main/resources` or the runtime directory):

```yaml
server:
  port: 8080 # Web Admin Port

bot:
  admin:
    qq-list: # Administrator QQ Number List
      - 123456
      - 789012
  command:
    prefix: "/" # Command prefix, customizable
  images:
    root-dir: ./data/images # Sticker Storage Root Directory
  ai:
    enabled: true
    api-url: "https://api.openai.com/v1" # AI API Address
    api-key: "YOUR_API_KEY" # API Key
```

### 3. Start Program

```bash
java -jar jbot.jar
```

### 4. Connect OneBot

Ensure your OneBot client (such as Go-CQHTTP) is configured with a **Forward WebSocket** connection to this project:

```yaml
# OneBot Client Configuration Example
- url: ws://127.0.0.1:8080/ws
```

## User Guide

### Web Admin Panel
Access `http://localhost:8080/admin` to enter the management interface. An admin password must be set upon first access.

### Chat Commands (Example Prefix `/`)

- **`/ai [query]`**: Wake up the AI to initiate conversation.
- **`/view [keyword]`**: Randomly fetch an image from the local sticker library to send.
- **`/anime [tag]`**: Search and send anime images via API.
- **`/poke`**: Nudge the bot; it will randomly reply with a sticker.
- **`/reset`**: Reset current conversation context.
- **`/vocab`**: View or manage vocabulary cards.

## Project Structure

```
com.bot.jone/
├── ai/                # AI Core Logic & Memory Management
├── config/            # Config Loading, Hot-Update & Web Config
├── controller/        # REST API & Frontend Page Controllers
├── handler/           # WebSocket Events, Messages & Command Handling
├── ioutils/           # Image Compression, Download & Temp File Management
├── monitor/           # JVM & System Resource Monitoring
└── service/           # Business Logic Services (Gallery, Vocab, etc.)
```

## License

This project follows the open-source license (specific details please refer to the project LICENSE file).