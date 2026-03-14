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

package com.github.oogasawa.coderagent.claude;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Level 1 POJO that manages Claude CLI subprocess execution.
 *
 * <p>Launches {@code claude --print --output-format stream-json "prompt"} as a subprocess
 * and reads stdout line by line, parsing each line as a stream-json event.</p>
 *
 * @author Osamu Ogasawara
 */
public class ClaudeProcess {

    private static final Logger logger = Logger.getLogger(ClaudeProcess.class.getName());

    // Matches ANSI escape sequences (CSI, OSC, and other ESC sequences)
    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\x1b\\[[0-9;?]*[a-zA-Z]|\\x1b\\][^\u0007]*\u0007|\\x1b[^\\[\\]].?"
    );

    private ClaudeConfig config;
    private final StreamEventParser parser = new StreamEventParser();

    private String lastSessionId;
    private Process currentProcess;
    private OutputStream stdinStream;
    private BufferedReader stdoutReader;
    private volatile String apiKey;

    /**
     * Constructs a ClaudeProcess with the specified configuration.
     *
     * @param config the Claude CLI configuration
     */
    public ClaudeProcess(ClaudeConfig config) {
        this.config = config;
    }

    /**
     * Gets the current configuration.
     *
     * @return the current ClaudeConfig
     */
    public ClaudeConfig getConfig() {
        return config;
    }

    /**
     * Sets a new configuration.
     *
     * @param config the new configuration
     */
    public void setConfig(ClaudeConfig config) {
        this.config = config;
    }

    /**
     * Sets the Anthropic API key for use when Claude CLI is not installed.
     * The key is passed as ANTHROPIC_API_KEY environment variable to the subprocess.
     *
     * @param key the API key
     */
    public void setApiKey(String key) {
        this.apiKey = key;
    }

    /**
     * Gets the session ID from the last result event.
     *
     * @return the last session ID, or null if none
     */
    public String getLastSessionId() {
        return lastSessionId;
    }

    /**
     * Returns whether the Claude CLI process is currently running.
     *
     * @return true if the process is alive
     */
    public boolean isAlive() {
        return currentProcess != null && currentProcess.isAlive();
    }

    /**
     * Starts a new Claude CLI subprocess.
     *
     * @throws IOException if the subprocess fails to start
     */
    private void startProcess() throws IOException {
        List<String> claudeCmd = buildCommand();

        // With --input-format stream-json, PTY is not needed
        // because Claude CLI accepts piped JSON input directly.
        logger.fine(() -> "Starting Claude CLI: " + String.join(" ", claudeCmd));

        ProcessBuilder pb = new ProcessBuilder(claudeCmd);

        // Unset CLAUDECODE to prevent nested session detection
        pb.environment().remove("CLAUDECODE");
        pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");

        // Set API key if provided (for users without Claude CLI login)
        if (apiKey != null && !apiKey.isBlank()) {
            pb.environment().put("ANTHROPIC_API_KEY", apiKey);
        }

        if (config.workingDir() != null) {
            pb.directory(new File(config.workingDir()));
        }

        pb.redirectErrorStream(false);
        currentProcess = pb.start();

        // Keep stdin open for bidirectional communication
        stdinStream = currentProcess.getOutputStream();
        stdoutReader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));

        // Read stderr in background thread (for process lifetime)
        Process proc = currentProcess;
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String stderrLine;
                while ((stderrLine = reader.readLine()) != null) {
                    String msg = stderrLine;
                    logger.fine(() -> "stderr: " + msg);
                }
            } catch (IOException e) {
                // Ignore - process likely terminated
            }
        });
    }

    /**
     * Sends a prompt to Claude CLI and streams the response events.
     *
     * <p>With --input-format stream-json, the process stays alive across turns.
     * This method writes the prompt to stdin and reads stdout until a result
     * event is received (indicating the turn is complete). The process remains
     * running for future calls.</p>
     *
     * @param prompt the prompt to send
     * @param callback callback for receiving stream events
     * @return 0 on success, or the process exit code if the process terminated
     * @throws IOException if the subprocess fails to start or stream reading fails
     */
    public int sendPrompt(String prompt, StreamCallback callback) throws IOException {
        if (currentProcess == null || !currentProcess.isAlive()) {
            startProcess();
        }

        writeUserMessage(prompt);

        // Read stdout (stream-json) until result event or EOF
        String line;
        while ((line = stdoutReader.readLine()) != null) {
            // Strip ANSI escape sequences
            line = stripAnsi(line).trim();
            if (line.isEmpty()) {
                continue;
            }

            // Log all non-empty lines for debugging
            String logLine = line;
            logger.info(() -> "stdout: " + logLine);

            if (!line.startsWith("{")) {
                continue;
            }

            StreamEvent event = parser.parse(line);
            if (event == null) {
                continue;
            }

            logger.info(() -> "event: type=" + event.type()
                + (event.isPrompt() ? " [PROMPT promptType=" + event.promptType() + "]" : ""));

            // Track session ID from result events
            if ("result".equals(event.type()) && event.sessionId() != null) {
                lastSessionId = event.sessionId();
            }

            if (callback != null) {
                callback.onEvent(event);
            }

            // Turn complete - return without closing the process
            if ("result".equals(event.type())) {
                return 0;
            }
        }

        // readLine returned null - process has exited
        int exitCode = 0;
        Process proc = currentProcess;
        if (proc != null) {
            try {
                exitCode = proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (callback != null) {
            callback.onComplete(exitCode);
        }

        // Clean up references
        currentProcess = null;
        stdinStream = null;
        stdoutReader = null;

        return exitCode;
    }

    /**
     * Cancels the currently running Claude CLI process, if any.
     */
    public void cancel() {
        Process p = currentProcess;
        currentProcess = null;
        stdinStream = null;
        stdoutReader = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info("Claude CLI process cancelled");
        }
    }

    /**
     * Writes a user message to Claude's stdin in stream-json format.
     *
     * @param text the user message text
     * @throws IOException if writing to stdin fails
     */
    public void writeUserMessage(String text) throws IOException {
        if (stdinStream == null) {
            throw new IOException("No active process stdin");
        }
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + escapeJsonString(text) + "}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
    }

    /**
     * Escapes a string for use in JSON.
     */
    static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Wraps a command with 'script' to create a pseudo-TTY.
     * Claude CLI requires stdout to be a TTY for stream-json output.
     *
     * @param claudeCmd the claude command arguments
     * @return command list starting with 'script'
     */
    List<String> wrapWithPty(List<String> claudeCmd) {
        // Shell-escape each argument and join into a single command string
        String innerCommand = claudeCmd.stream()
            .map(arg -> "'" + arg.replace("'", "'\\''") + "'")
            .collect(Collectors.joining(" "));

        List<String> cmd = new ArrayList<>();
        cmd.add("script");
        cmd.add("-q");    // quiet (no "Script started/done" messages)
        cmd.add("-e");    // return exit code of child command
        cmd.add("-f");    // flush after each write (important for streaming)
        cmd.add("-c");    // command to run
        cmd.add(innerCommand);
        cmd.add("/dev/null");  // typescript output file (discarded)
        return cmd;
    }

    /**
     * Strips ANSI escape sequences from a string.
     * PTY wrapper adds terminal control codes that must be removed before JSON parsing.
     */
    static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    /**
     * Builds the Claude CLI command arguments.
     *
     * <p>With --input-format stream-json, the prompt is sent via stdin,
     * not as a command-line argument.</p>
     *
     * <p>Visible for testing.</p>
     *
     * @return the command as a list of strings
     */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        // NOTE: --print is intentionally omitted.
        // With --print, Claude CLI runs in non-interactive mode where tool
        // permissions are silently denied instead of prompting the user.
        // Using --input-format/--output-format stream-json provides
        // bidirectional JSON communication including permission prompts.
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (config.model() != null) {
            cmd.add("--model");
            cmd.add(config.model());
        }

        if (config.systemPrompt() != null) {
            cmd.add("--system-prompt");
            cmd.add(config.systemPrompt());
        }

        if (config.maxTurns() > 0) {
            cmd.add("--max-turns");
            cmd.add(String.valueOf(config.maxTurns()));
        }

        if (config.sessionId() != null) {
            cmd.add("--resume");
            cmd.add(config.sessionId());
        }

        if (config.continueSession()) {
            cmd.add("-c");
        }

        if (config.allowedTools() != null) {
            for (String tool : config.allowedTools()) {
                cmd.add("--allowedTools");
                cmd.add(tool);
            }
        }

        // No prompt argument; prompt is sent via stdin as stream-json
        return cmd;
    }

    /**
     * Callback interface for receiving stream events from Claude CLI.
     */
    public interface StreamCallback {

        /**
         * Called when a stream event is received.
         *
         * @param event the parsed stream event
         */
        void onEvent(StreamEvent event);

        /**
         * Called when stderr output is received.
         *
         * @param line the stderr line
         */
        default void onError(String line) {
            // Default: ignore stderr
        }

        /**
         * Called when the process completes.
         *
         * @param exitCode the process exit code
         */
        default void onComplete(int exitCode) {
            // Default: do nothing
        }
    }
}
