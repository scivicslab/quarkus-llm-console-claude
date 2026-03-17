/*
 * Copyright 2025 Osamu Ogasawara
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.llmconsole.claude.service;

import com.scivicslab.llmconsole.claude.claude.ClaudeConfig;
import com.scivicslab.llmconsole.claude.claude.ClaudeProcess;
import com.scivicslab.llmconsole.claude.command.SlashCommandHandler;
import com.scivicslab.llmconsole.claude.rest.ChatEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the Claude CLI process and chat session.
 *
 * <p>Singleton service that routes prompts to Claude CLI.</p>
 */
@ApplicationScoped
public class ChatService {

    private static final Logger logger = Logger.getLogger(ChatService.class.getName());

    private final ClaudeProcess claudeProcess;
    private final SlashCommandHandler commandHandler;
    private volatile boolean busy;
    private volatile Thread activeThread;

    // Authentication
    private final AuthMode authMode;
    private volatile String apiKey;

    // Session persistence
    private final Path sessionFile;

    // Conversation history buffer for /api/history
    private static final int MAX_HISTORY = 200;
    private final LinkedList<HistoryEntry> conversationHistory = new LinkedList<>();

    @Inject
    public ChatService(
            @ConfigProperty(name = "llm-console.allowed-tools")
            Optional<String> allowedTools,
            @ConfigProperty(name = "llm-console.api-key")
            Optional<String> configApiKeyOpt,
            @ConfigProperty(name = "llm-console.session-file", defaultValue = ".llm-console-session")
            String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090")
            int httpPort) {

        ClaudeConfig config = ClaudeConfig.defaults();
        if (allowedTools.isPresent() && !allowedTools.get().isBlank()) {
            String[] tools = allowedTools.get().split(",");
            for (int i = 0; i < tools.length; i++) {
                tools[i] = tools[i].trim();
            }
            config = config.withAllowedTools(tools);
            logger.info("Allowed tools (auto-approved): " + allowedTools.get());
        }

        // Detect authentication mode:
        // 1. Check if 'claude' CLI is on PATH
        // 2. Check for API key (env var or config property)
        // 3. Otherwise, require Web UI input
        if (isClaudeCliAvailable()) {
            this.authMode = AuthMode.CLI;
            this.apiKey = null;
            logger.info("Authentication: Claude CLI detected on PATH");
        } else {
            String envKey = System.getenv("ANTHROPIC_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = envKey;
                logger.info("Authentication: ANTHROPIC_API_KEY environment variable");
            } else if (configApiKeyOpt.isPresent() && !configApiKeyOpt.get().isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = configApiKeyOpt.get();
                logger.info("Authentication: API key from config property");
            } else {
                this.authMode = AuthMode.NONE;
                this.apiKey = null;
                logger.info("Authentication: none configured (will prompt via Web UI)");
            }
        }

        // Restore session from file if available.
        String pupsPath = System.getenv("PUPS_SESSION_PATH");
        if (pupsPath != null && !pupsPath.isBlank()) {
            String suffix = pupsPath.replaceAll("[^a-zA-Z0-9-]", "");
            this.sessionFile = Path.of(sessionFilePath + "-" + suffix);
        } else {
            this.sessionFile = Path.of(sessionFilePath + "-" + httpPort);
        }
        config = restoreSession(config);

        this.claudeProcess = new ClaudeProcess(config);
        this.commandHandler = new SlashCommandHandler(claudeProcess);
    }

    /** Returns the current authentication mode. */
    public AuthMode getAuthMode() {
        return authMode;
    }

    /** Returns true if authentication is configured (CLI or API key). */
    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI || (authMode == AuthMode.API_KEY && apiKey != null)
                || (authMode == AuthMode.NONE && apiKey != null);
    }

    /** Sets the API key (from Web UI input). */
    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    /** Returns the API key for use in process environment, or null. */
    public String getApiKey() {
        return apiKey;
    }

    /** Checks if 'claude' CLI is available on PATH. */
    static boolean isClaudeCliAvailable() {
        try {
            Process p = new ProcessBuilder("which", "claude")
                    .redirectErrorStream(true).start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns whether a prompt is currently being processed. */
    public boolean isBusy() {
        return busy;
    }

    /** Gets the current model name. */
    public String getModel() {
        return claudeProcess.getConfig().model();
    }

    /** Gets the current session ID. */
    public String getSessionId() {
        return claudeProcess.getLastSessionId();
    }

    /** Checks if input is a slash command. */
    public boolean isCommand(String input) {
        return commandHandler.isCommand(input);
    }

    /** Returns the list of available Claude models. */
    public List<ClaudeModelSet.ModelEntry> getAvailableModels() {
        return new ClaudeModelSet().getAvailableModels();
    }

    /** Handles a slash command. */
    public void handleCommand(String input, Consumer<ChatEvent> sender) {
        commandHandler.handle(input, sender);

        if (input.trim().toLowerCase().startsWith("/clear")) {
            deleteSessionFile();
            clearHistory();
        }

        sender.accept(ChatEvent.status(
            claudeProcess.getConfig().model(),
            claudeProcess.getLastSessionId(),
            busy
        ));
    }

    /**
     * Sends a prompt to Claude CLI and streams response via callback.
     */
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> sender) {
        if (busy) {
            sender.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            return;
        }

        busy = true;
        activeThread = Thread.currentThread();
        try {
            sendToClaude(prompt, model, sender);
        } finally {
            activeThread = null;
            busy = false;
            sender.accept(ChatEvent.status(
                claudeProcess.getConfig().model(),
                claudeProcess.getLastSessionId(),
                false
            ));
        }
    }

    /** Sends a prompt to Claude CLI. */
    private void sendToClaude(String prompt, String model, Consumer<ChatEvent> sender) {
        sendToClaude(prompt, model, sender, false);
    }

    private void sendToClaude(String prompt, String model, Consumer<ChatEvent> sender,
                              boolean isRetry) {
        if (!isAuthenticated()) {
            sender.accept(ChatEvent.error(
                "No authentication configured. Please provide your Anthropic API key."));
            return;
        }

        // Record user prompt in history
        recordHistory("user", prompt);

        final boolean[] staleSession = {false};
        final StringBuilder assistantBuf = new StringBuilder();

        try {
            // Pass API key to ClaudeProcess if using API_KEY mode
            if (apiKey != null && authMode != AuthMode.CLI) {
                claudeProcess.setApiKey(apiKey);
            }

            if (!model.equals(claudeProcess.getConfig().model())) {
                claudeProcess.setConfig(claudeProcess.getConfig().withModel(model));
            }

            if (!claudeProcess.isAlive()) {
                String lastSessionId = claudeProcess.getLastSessionId();
                if (lastSessionId != null) {
                    ClaudeConfig config = claudeProcess.getConfig().withSessionId(lastSessionId);
                    claudeProcess.setConfig(config);
                }
            }

            sender.accept(ChatEvent.status(
                claudeProcess.getConfig().model(),
                claudeProcess.getLastSessionId(),
                true
            ));

            claudeProcess.sendPrompt(prompt, new ClaudeProcess.StreamCallback() {
                @Override
                public void onEvent(com.scivicslab.llmconsole.claude.claude.StreamEvent event) {
                    switch (event.type()) {
                        case "assistant" -> {
                            if (event.hasContent()) {
                                assistantBuf.append(event.content());
                                sender.accept(ChatEvent.delta(event.content()));
                            }
                        }
                        case "thinking" ->
                            sender.accept(ChatEvent.thinking("Thinking..."));
                        case "tool_activity" ->
                            sender.accept(ChatEvent.thinking(
                                "Using " + event.content() + "..."));
                        case "tool_result" ->
                            sender.accept(ChatEvent.thinking("Tool completed."));
                        case "system" -> {
                            if (event.sessionId() != null) {
                                saveSession(event.sessionId(), model);
                            }
                        }
                        case "result" -> {
                            if (event.sessionId() != null) {
                                saveSession(event.sessionId(), model);
                            }
                            // Record accumulated assistant response in history
                            if (!assistantBuf.isEmpty()) {
                                recordHistory("assistant", assistantBuf.toString());
                            }
                            sender.accept(ChatEvent.result(
                                event.sessionId(),
                                event.costUsd(),
                                event.durationMs(),
                                claudeProcess.getConfig().model(),
                                false
                            ));
                        }
                        case "error" -> {
                            if (!isRetry && event.content() != null
                                    && event.content().contains("No conversation found with session ID")) {
                                staleSession[0] = true;
                            } else {
                                sender.accept(ChatEvent.error(event.content()));
                            }
                        }
                        case "prompt" -> sender.accept(ChatEvent.prompt(
                            event.promptId(),
                            event.content(),
                            event.promptType(),
                            event.options()
                        ));
                        default -> {
                            // Ignore other event types
                        }
                    }
                }

                @Override
                public void onError(String line) {
                    logger.fine(() -> "stderr: " + line);
                }

                @Override
                public void onComplete(int exitCode) {
                    if (exitCode != 0 && !staleSession[0]) {
                        sender.accept(ChatEvent.error("Claude CLI exited with code: " + exitCode));
                    }
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Claude CLI failed", e);
            sender.accept(ChatEvent.error("Claude CLI error: " + e.getMessage()));
            return;
        }

        // Retry with a new session if the previous session was stale
        if (staleSession[0]) {
            logger.warning("Stale session detected, clearing session and retrying with new session");
            claudeProcess.cancel();
            deleteSessionFile();
            claudeProcess.setConfig(claudeProcess.getConfig().withSessionId(null));
            sender.accept(ChatEvent.thinking("Session expired. Starting new session..."));
            sendToClaude(prompt, model, sender, true);
        }
    }

    /**
     * Sends a user response to an interactive prompt.
     */
    public void respond(String promptId, String response) throws IOException {
        claudeProcess.writeUserMessage(response);
    }

    /** Cancels the currently running request. */
    public void cancel() {
        claudeProcess.cancel();
        Thread t = activeThread;
        if (t != null) {
            t.interrupt();
        }
    }

    // --- Conversation history buffer ---

    /** A single message in the conversation history. */
    public record HistoryEntry(String role, String content) {}

    /** Record a message into the conversation history buffer. */
    private void recordHistory(String role, String content) {
        if (content == null || content.isBlank()) return;
        synchronized (conversationHistory) {
            conversationHistory.addLast(new HistoryEntry(role, content));
            while (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.removeFirst();
            }
        }
    }

    /** Returns a snapshot of the conversation history (oldest first). */
    public List<HistoryEntry> getHistory(int limit) {
        synchronized (conversationHistory) {
            int size = conversationHistory.size();
            int from = Math.max(0, size - limit);
            return Collections.unmodifiableList(
                    new ArrayList<>(conversationHistory.subList(from, size)));
        }
    }

    /** Clears the conversation history buffer. */
    public void clearHistory() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
    }

    // --- Session file persistence ---

    ClaudeConfig restoreSession(ClaudeConfig config) {
        try {
            if (!Files.exists(sessionFile)) {
                return config;
            }
            String savedSessionId = null;
            String savedModel = null;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) {
                    savedSessionId = line.substring("sessionId=".length()).trim();
                } else if (line.startsWith("model=")) {
                    savedModel = line.substring("model=".length()).trim();
                }
            }
            if (savedSessionId != null && !savedSessionId.isEmpty()) {
                config = config.withSessionId(savedSessionId);
                logger.info("Restored session from file: " + savedSessionId);
            }
            if (savedModel != null && !savedModel.isEmpty()) {
                config = config.withModel(savedModel);
                logger.info("Restored model from file: " + savedModel);
            }
            return config;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file", e);
            return config;
        }
    }

    void saveSession(String sessionId, String model) {
        try {
            String content = "sessionId=" + sessionId + "\n"
                    + "model=" + (model != null ? model : "") + "\n";
            Files.writeString(sessionFile, content);
            logger.fine("Saved session to file: " + sessionId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file", e);
        }
    }

    void deleteSessionFile() {
        try {
            Files.deleteIfExists(sessionFile);
            logger.info("Session file deleted");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete session file", e);
        }
    }
}
