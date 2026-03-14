# quarkus-coder-agent-claude

A lightweight Web UI for Claude Code CLI. Chat with Claude models from your browser with real-time SSE streaming, prompt queuing, and session management.

**Queue prompts while the AI is still thinking.** Don't wait — type your next prompts and add them to the queue. Once the current response finishes, queued prompts are sent automatically in order.

## Features

- **Claude models** — sonnet, opus, haiku via Claude Code CLI
- **Prompt queue** — queue up prompts while the AI is responding; auto-send, reorder, remove
- **Tool execution** — Bash, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch (via Claude CLI)
- **Interactive prompts** — tool permission dialogs forwarded to the Web UI
- **Session persistence** — session ID saved to file; survives server restart
- Real-time streaming responses (SSE) with Markdown rendering
- Save conversation history as Markdown file
- 10 color themes (5 dark + 5 light)

## Authentication

Authentication is resolved in this order:

1. **Claude Code CLI** — if `claude` is on your PATH, it is used directly
2. **Environment variable / config property** — set `ANTHROPIC_API_KEY` or pass `-Dcoder-agent.api-key=sk-ant-...`
3. **Web UI prompt** — if none of the above, the browser shows an API key input dialog at startup

## Installation

There are two ways to install: using a pre-built native image binary, or building a JAR from source. The native image requires no Java runtime and starts instantly. The JAR requires Java 21+ but runs on any platform.

---

### Native Image

#### Prerequisites

- No Java required. The binary is self-contained.
- One of the authentication methods above.

#### Install

Download the binary for your platform from the [Releases](https://github.com/oogasawa/quarkus-coder-agent-claude/releases) page:

| File | Platform |
|------|----------|
| `quarkus-coder-agent-claude-vX.Y.Z-linux-x86_64` | Linux (x86_64) |
| `quarkus-coder-agent-claude-vX.Y.Z-linux-aarch64` | Linux (aarch64 / DGX Spark) |
| `quarkus-coder-agent-claude-vX.Y.Z-macos-aarch64` | macOS (Apple Silicon) |
| `quarkus-coder-agent-claude-vX.Y.Z-macos-x86_64` | macOS (Intel) |
| `quarkus-coder-agent-claude-vX.Y.Z-windows-x86_64.exe` | Windows |

```bash
chmod +x quarkus-coder-agent-claude-*
```

#### Run

```bash
./quarkus-coder-agent-claude-v1.0.0-linux-x86_64
```

Open `http://localhost:8090` in your browser.

Change the HTTP port:

```bash
./quarkus-coder-agent-claude-v1.0.0-linux-x86_64 -Dquarkus.http.port=9090
```

Provide API key:

```bash
ANTHROPIC_API_KEY=sk-ant-... ./quarkus-coder-agent-claude-v1.0.0-linux-x86_64
```

---

### JAR (JVM mode)

#### Prerequisites

- Java 21+
- Maven 3.9+
- One of the authentication methods above.

#### Build

```bash
git clone https://github.com/oogasawa/quarkus-coder-agent-claude.git
cd quarkus-coder-agent-claude
rm -rf target
mvn install
```

#### Run

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

Open `http://localhost:8090` in your browser.

Change the HTTP port:

```bash
java -Dquarkus.http.port=9090 -jar target/quarkus-app/quarkus-run.jar
```

Provide API key:

```bash
ANTHROPIC_API_KEY=sk-ant-... java -jar target/quarkus-app/quarkus-run.jar
```

Or via config property:

```bash
java -Dcoder-agent.api-key=sk-ant-... -jar target/quarkus-app/quarkus-run.jar
```

#### JBang launcher

```bash
jbang coder_agent.java
jbang coder_agent.java --port=9090
```

#### Build native image from source

Requires GraalVM 21+ and the zlib development library:

```bash
# Ubuntu / Debian
sudo apt install zlib1g-dev

# Fedora / RHEL
sudo dnf install zlib-devel
```

```bash
rm -rf target
mvn install -Dnative -DskipTests
./target/quarkus-coder-agent-claude-1.0.0-runner
```

---

## REST API

```bash
# List available models
curl http://localhost:8090/api/models

# Send a prompt
curl -X POST http://localhost:8090/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"text":"Hello","model":"sonnet"}'

# Check authentication status
curl http://localhost:8090/api/config
```

## Test

```bash
rm -rf target
mvn test
```

## License

Apache License 2.0
