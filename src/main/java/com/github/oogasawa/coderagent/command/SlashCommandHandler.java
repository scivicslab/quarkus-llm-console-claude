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

package com.github.oogasawa.coderagent.command;

import com.github.oogasawa.coderagent.claude.ClaudeConfig;
import com.github.oogasawa.coderagent.claude.ClaudeProcess;
import com.github.oogasawa.coderagent.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Handles slash commands from the Web UI (adapted from ReplCommandHandler).
 *
 * <p>Supported commands: /model, /session, /clear, /help</p>
 *
 * @author Osamu Ogasawara
 */
public class SlashCommandHandler {

    private final ClaudeProcess claudeProcess;

    /**
     * Constructs a SlashCommandHandler.
     *
     * @param claudeProcess the ClaudeProcess to configure
     */
    public SlashCommandHandler(ClaudeProcess claudeProcess) {
        this.claudeProcess = claudeProcess;
    }

    /**
     * Checks if the input is a slash command.
     *
     * @param input user input
     * @return true if the input starts with /
     */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /**
     * Handles a slash command and sends response messages via the callback.
     *
     * @param input the command input (e.g., "/model opus")
     * @param sender callback for sending ChatEvent responses
     */
    public void handle(String input, Consumer<ChatEvent> sender) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/model" -> handleModel(args, sender);
            case "/clear" -> handleClear(sender);
            case "/session" -> handleSession(args, sender);
            case "/help", "/?" -> handleHelp(sender);
            default -> sender.accept(ChatEvent.error("Unknown command: " + command + " (type /help for available commands)"));
        }
    }

    private void handleModel(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            sender.accept(ChatEvent.info("Current model: " + claudeProcess.getConfig().model()));
        } else {
            ClaudeConfig newConfig = claudeProcess.getConfig().withModel(args);
            claudeProcess.setConfig(newConfig);
            sender.accept(ChatEvent.info("Model changed to: " + args));
        }
    }

    private void handleClear(Consumer<ChatEvent> sender) {
        ClaudeConfig config = claudeProcess.getConfig();
        ClaudeConfig newConfig = new ClaudeConfig(
            config.model(), config.systemPrompt(), config.maxTurns(),
            config.workingDir(), null, false, config.allowedTools()
        );
        claudeProcess.setConfig(newConfig);
        sender.accept(ChatEvent.info("Session cleared. Starting fresh conversation."));
    }

    private void handleSession(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String sessionId = claudeProcess.getLastSessionId();
            if (sessionId != null) {
                sender.accept(ChatEvent.info("Current session: " + sessionId));
            } else {
                sender.accept(ChatEvent.info("No active session."));
            }
        } else {
            ClaudeConfig newConfig = claudeProcess.getConfig().withSessionId(args);
            claudeProcess.setConfig(newConfig);
            sender.accept(ChatEvent.info("Session set to: " + args));
        }
    }

    private void handleHelp(Consumer<ChatEvent> sender) {
        String help = """
            Available commands:
              /help, /?          Show this help
              /model [name]      Show or change the model
              /session [id]      Show or set session ID
              /clear             Clear session (start fresh)""";
        sender.accept(ChatEvent.info(help));
    }
}
